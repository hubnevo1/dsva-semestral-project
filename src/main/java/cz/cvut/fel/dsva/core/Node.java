package cz.cvut.fel.dsva.core;

import cz.cvut.fel.dsva.api.RestApiServer;
import cz.cvut.fel.dsva.chat.ChatManager;
import cz.cvut.fel.dsva.chat.ChatMessage;
import cz.cvut.fel.dsva.network.MessageHandler;
import cz.cvut.fel.dsva.network.SocketClient;
import cz.cvut.fel.dsva.network.SocketServer;
import cz.cvut.fel.dsva.topology.RingTopology;
import cz.cvut.fel.dsva.utils.Logger;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Node implements MessageHandler {
    private final NodeInfo myself;
    private final LogicalClock logicalClock;
    private final RingTopology topology;
    private final TokenBasedMutex mutex;
    private final ChatManager chatManager;
    private final SocketServer socketServer;
    private final SocketClient socketClient;
    private final RestApiServer apiServer;
    private volatile boolean running = true;
    private final Queue<ChatMessage> pendingMessages = new ConcurrentLinkedQueue<>();

    private volatile boolean isKilled = false; // "Killed" state - stop comms
    private volatile boolean inCriticalSection = false; // If true, hold token
    private volatile boolean wantCriticalSection = false; // API request to enter CS

    private volatile int m = 0; // Last seen counter from PING or PONG
    private volatile int nbPing = 0; // PING counter (positive)
    private volatile int nbPong = 0; // PONG counter (negative)
    private volatile boolean hasPing = false;
    private volatile boolean hasPong = false;
    private final Object misraLock = new Object();

    private static final int MODULO_P = 1_000_000_000;

    public Node(String ip, int port, int apiPort) {
        this.myself = new NodeInfo(ip, port);
        this.logicalClock = new LogicalClock();
        Logger.init(ip + ":" + port, logicalClock);

        this.topology = new RingTopology(myself);
        this.mutex = new TokenBasedMutex();
        this.chatManager = new ChatManager();
        this.socketClient = new SocketClient();
        this.socketServer = new SocketServer(port, this);
        this.apiServer = new RestApiServer(apiPort, this);
    }

    public void start(boolean isLeader) throws IOException {
        socketServer.start();
        apiServer.start();

        new Thread(this::tokenLoop).start();
        new Thread(this::misraLoop).start(); // Start Misra Ping-Pong loop

        if (isLeader) {
            Logger.log("Started as LEADER. Generating initial token and Misra tokens.");
            mutex.regenerateToken();
            initializeMisraAsLeader();
        } else {
            Logger.log("Started as FOLLOWER. Will receive tokens from network.");
            initializeMisraAsFollower();
        }
    }

    private void initializeMisraAsLeader() {
        synchronized (misraLock) {
            m = 1;
            nbPing = 1;
            nbPong = -1;
            hasPing = true;
            hasPong = true;
            Logger.log("Misra initialized as LEADER: m=" + m + ", nbPing=" + nbPing + ", nbPong=" + nbPong);
        }
    }

    private void initializeMisraAsFollower() {
        synchronized (misraLock) {
            m = 0;
            nbPing = 0;
            nbPong = 0;
            hasPing = false;
            hasPong = false;
            Logger.log("Misra initialized as FOLLOWER: m=" + m);
        }
    }

    private void tokenLoop() {
        while (running) {
            try {
                if (isKilled) {
                    Thread.sleep(1000);
                    continue;
                }

                if (mutex.hasToken()) {
                    // 1. Send pending chat messages
                    while (!pendingMessages.isEmpty()) {
                        ChatMessage msg = pendingMessages.poll();
                        broadcastMessage(msg);
                    }

                    // 2. Check if we want to stay in CS explicitly
                    if (wantCriticalSection) {
                        if (!inCriticalSection) {
                            Logger.log("Entered Critical Section (API requested). Holding token.");
                            inCriticalSection = true;
                        }
                        Thread.sleep(500);
                        continue;
                    } else if (inCriticalSection) {
                        Logger.log("Leaving Critical Section.");
                        inCriticalSection = false;
                    }

                    Thread.sleep(500);
                    passToken();
                } else {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Misra Ping-Pong loop - circulates PING and PONG tokens.
     * Detects token loss and regenerates missing tokens.
     */
    private void misraLoop() {
        while (running) {
            try {
                Thread.sleep(1000); // Check every second

                if (isKilled || topology.isAlone()) {
                    continue;
                }

                synchronized (misraLock) {
                    // Check for meeting: if we hold both PING and PONG
                    if (hasPing && hasPong) {
                        handleMisraMeeting();
                    }

                    // Pass PING to next (if we have it)
                    if (hasPing && !hasPong) { // Only pass if not meeting
                        passPing();
                    }

                    // Pass PONG to prev (if we have it)
                    if (hasPong && !hasPing) { // Only pass if not meeting
                        passPong();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Handle meeting of PING and PONG at this node.
     * Increment nbPing, decrement nbPong.
     */
    private void handleMisraMeeting() {
        nbPing = modulo(nbPing + 1);
        nbPong = modulo(nbPong - 1);
        Logger.log("Misra MEETING: nbPing=" + nbPing + ", nbPong=" + nbPong);

        // After meeting, pass both tokens
        passPing();
        passPong();
    }

    /**
     * Pass PING token to next neighbor.
     */
    private void passPing() {
        if (!hasPing)
            return;

        NodeInfo next = topology.getNextNode();
        if (next.equals(myself)) {
            // Alone, keep the token
            return;
        }

        hasPing = false;
        Message msg = new Message(Message.Type.PING, myself, next,
                new MisraToken(nbPing), logicalClock.incrementAndGet());
        boolean sent = socketClient.sendMessage(next, msg);
        if (!sent) {
            // Failed to send, reclaim and handle failure
            hasPing = true;
            Logger.log("Failed to pass PING to " + next + ". Handling failure...");
            handleNextNeighborFailure(next);
        } else {
            Logger.log("Passed PING(" + nbPing + ") to " + next);
        }
    }

    /**
     * Pass PONG token to prev neighbor.
     */
    private void passPong() {
        if (!hasPong)
            return;

        NodeInfo prev = topology.getPrevNode();
        if (prev.equals(myself)) {
            // Alone, keep the token
            return;
        }

        hasPong = false;
        Message msg = new Message(Message.Type.PONG, myself, prev,
                new MisraToken(nbPong), logicalClock.incrementAndGet());
        boolean sent = socketClient.sendMessage(prev, msg);
        if (!sent) {
            // Failed to send - prev is dead! Handle failure.
            hasPong = true;
            Logger.log("Failed to pass PONG to " + prev + ". Handling prev failure...");
            handlePrevNeighborFailure(prev);
        } else {
            Logger.log("Passed PONG(" + nbPong + ") to " + prev);
        }
    }

    /**
     * Handle receiving PING token.
     * Implements Misra detection: if m == nbPing, PONG was lost.
     */
    private void handlePingAlg(Message message) {
        if (!(message.payload() instanceof MisraToken(int receivedNbPing)))
            return;

        synchronized (misraLock) {
            if (m == receivedNbPing) {
                // PONG was lost! Regenerate it.
                Logger.log(
                        "Misra: PONG LOST detected (m=" + m + " == nbPing=" + receivedNbPing + "). Regenerating PONG.");
                nbPing = modulo(receivedNbPing + 1);
                nbPong = -nbPing;
                hasPong = true;

                // Also regenerate the mutex token (the main token may have been lost with the
                // node)
                mutex.regenerateToken();
                Logger.log("Regenerated mutex TOKEN due to PONG loss.");
            } else {
                m = receivedNbPing;
            }

            nbPing = receivedNbPing;
            hasPing = true;
            Logger.log("Received PING(" + receivedNbPing + "), m=" + m);
        }
    }

    /**
     * Handle receiving PONG token.
     * Implements Misra detection: if m == nbPong, PING was lost.
     */
    private void handlePongAlg(Message message) {
        if (!(message.payload() instanceof MisraToken(int receivedNbPong)))
            return;

        synchronized (misraLock) {
            if (m == receivedNbPong) {
                // PING was lost! Regenerate it.
                Logger.log(
                        "Misra: PING LOST detected (m=" + m + " == nbPong=" + receivedNbPong + "). Regenerating PING.");
                nbPong = modulo(receivedNbPong - 1);
                nbPing = -nbPong;
                hasPing = true;

                // Also regenerate the mutex token
                mutex.regenerateToken();
                Logger.log("Regenerated mutex TOKEN due to PING loss.");
            } else {
                m = receivedNbPong;
            }

            nbPong = receivedNbPong;
            hasPong = true;
            Logger.log("Received PONG(" + receivedNbPong + "), m=" + m);
        }
    }

    private int modulo(int value) {
        return ((value % MODULO_P) + MODULO_P) % MODULO_P;
    }

    public void kill() {
        if (!isKilled) {
            isKilled = true;
            socketServer.stop();
            Logger.log("NODE KILLED (Simulated). Communication stopped.");
        }
    }

    public void revive() {
        if (isKilled) {
            isKilled = false;
            try {
                socketServer.start();
                Logger.log("NODE REVIVED. Communication restored.");
            } catch (IOException e) {
                Logger.error("Failed to restart server", e);
            }
        }
    }

    public void enterCS() {
        this.wantCriticalSection = true;
        Logger.log("Requesting input to Critical Section...");
    }

    public void leaveCS() {
        this.wantCriticalSection = false;
        Logger.log("Requesting leave of Critical Section...");
    }

    private void broadcastMessage(ChatMessage originalMsg) {
        Message netMsg = new Message(Message.Type.CHAT, myself, null, originalMsg, logicalClock.incrementAndGet());

        // Send to next
        NodeInfo next = topology.getNextNode();
        if (!next.equals(myself)) {
            boolean sent = socketClient.sendMessage(next, netMsg);
            if (!sent) {
                Logger.log("Failed to broadcast message to " + next + ". Re-queuing.");
                pendingMessages.add(originalMsg);
            }
        } else {
            // If alone, we already "delivered" it to ourselves locally.
            Logger.log("Broadcasting to self (Alone).");
        }
    }

    private void passToken() {
        Token token = mutex.yieldToken();
        if (token != null) {
            NodeInfo next = topology.getNextNode();

            if (isKilled) {
                Logger.log("Node killed while holding token! Token lost.");
                return;
            }

            if (next.equals(myself)) {
                mutex.receiveToken(token);
            } else {
                Message msg = new Message(Message.Type.TOKEN, myself, next, token, logicalClock.incrementAndGet());
                boolean sent = socketClient.sendMessage(next, msg);
                if (!sent) {
                    Logger.log("Failed to pass token to " + next + ". Using nextNext as fallback...");
                    handleNextNeighborFailure(next);
                    // After repair, reclaim token so we don't lose it
                    mutex.receiveToken(token);
                }
            }
        }
    }

    private void handleNextNeighborFailure(NodeInfo failedNode) {
        NodeInfo nextNext = topology.getNextNextNode();

        if (nextNext.equals(myself) || nextNext.equals(failedNode)) {
            // No backup available, we're alone now
            Logger.log("No backup next available. I am now alone in the ring.");
            topology.setNextNode(myself);
            topology.setNextNextNode(myself);
            topology.setPrevNode(myself);
            topology.setPrevPrevNode(myself);
            // Reinitialize Misra as leader (we're alone, need to hold both PING and PONG)
            initializeMisraAsLeader();
            // Regenerate mutex token only when truly alone
            mutex.regenerateToken();
            return;
        }

        // Use nextNext as our new next (skip the failed node)
        topology.setNextNode(nextNext);
        topology.setNextNextNode(myself); // Will be updated by nextNext's response
        Logger.log("Ring REPAIRED. New next: " + nextNext + " (failed: " + failedNode + ")");

        // Tell nextNext that we are now its prev, and update its prevPrev
        // nextNext will respond with its next (our new nextNext)
        Message updateMsg = new Message(Message.Type.UPDATE_NEIGHBORS, myself, nextNext,
                new NeighborUpdate(null, null, myself, topology.getPrevNode()),
                logicalClock.incrementAndGet());
        socketClient.sendMessage(nextNext, updateMsg);

        // Also notify our prev that their nextNext is now nextNext (not the failed
        // node)
        NodeInfo myPrev = topology.getPrevNode();
        if (!myPrev.equals(myself)) {
            Message updatePrev = new Message(Message.Type.UPDATE_NEIGHBORS, myself, myPrev,
                    new NeighborUpdate(null, nextNext, null, null),
                    logicalClock.incrementAndGet());
            socketClient.sendMessage(myPrev, updatePrev);
        }

        // The failed node may have been holding tokens (PING, PONG, or mutex TOKEN)
        // Regenerate them to ensure circulation continues
        synchronized (misraLock) {
            if (!hasPing) {
                nbPing = modulo(nbPing + 1);
                hasPing = true;
                Logger.log("Regenerated PING after neighbor failure. nbPing=" + nbPing);
            }
            if (!hasPong) {
                nbPong = -nbPing;
                hasPong = true;
                Logger.log("Regenerated PONG after neighbor failure. nbPong=" + nbPong);
            }
        }
        // Regenerate mutex token to ensure ring has exactly one token
        mutex.regenerateToken();
        Logger.log("Regenerated mutex TOKEN after neighbor failure.");
    }

    private void handlePrevNeighborFailure(NodeInfo failedNode) {
        NodeInfo prevPrev = topology.getPrevPrevNode();

        if (prevPrev.equals(myself) || prevPrev.equals(failedNode)) {
            // No backup available, we're alone now
            Logger.log("No backup prev available. I am now alone in the ring.");
            topology.setNextNode(myself);
            topology.setNextNextNode(myself);
            topology.setPrevNode(myself);
            topology.setPrevPrevNode(myself);
            // Reinitialize Misra as leader (we're alone)
            initializeMisraAsLeader();
            mutex.regenerateToken();
            return;
        }

        // Use prevPrev as our new prev (skip the failed node)
        topology.setPrevNode(prevPrev);
        topology.setPrevPrevNode(myself); // Will be updated by prevPrev's response
        Logger.log("Ring REPAIRED (prev). New prev: " + prevPrev + " (failed: " + failedNode + ")");

        // Tell prevPrev that we are now its next, and update its nextNext
        // prevPrev will respond with its prev (our new prevPrev)
        Message updateMsg = new Message(Message.Type.UPDATE_NEIGHBORS, myself, prevPrev,
                new NeighborUpdate(myself, topology.getNextNode(), null, null),
                logicalClock.incrementAndGet());
        socketClient.sendMessage(prevPrev, updateMsg);

        // Also notify our next that their prevPrev is now prevPrev (not the failed
        // node)
        NodeInfo myNext = topology.getNextNode();
        if (!myNext.equals(myself)) {
            Message updateNext = new Message(Message.Type.UPDATE_NEIGHBORS, myself, myNext,
                    new NeighborUpdate(null, null, null, prevPrev),
                    logicalClock.incrementAndGet());
            socketClient.sendMessage(myNext, updateNext);
        }

        // The failed node may have been holding tokens (PING, PONG, or mutex TOKEN)
        // Regenerate them to ensure circulation continues
        synchronized (misraLock) {
            if (!hasPing) {
                nbPing = modulo(nbPing + 1);
                hasPing = true;
                Logger.log("Regenerated PING after prev neighbor failure. nbPing=" + nbPing);
            }
            if (!hasPong) {
                nbPong = -nbPing;
                hasPong = true;
                Logger.log("Regenerated PONG after prev neighbor failure. nbPong=" + nbPong);
            }
        }
        // Regenerate mutex token to ensure ring has exactly one token
        mutex.regenerateToken();
        Logger.log("Regenerated mutex TOKEN after prev neighbor failure.");
    }

    @Override
    public void handleMessage(Message message) {
        if (isKilled)
            return;

        logicalClock.update(message.logicalTime());

        switch (message.type()) {
            case JOIN:
                handleJoin(message);
                break;
            case LEAVE:
                handleLeave(message);
                break;
            case TOKEN:
                if (message.payload() instanceof Token) {
                    mutex.receiveToken((Token) message.payload());
                }
                break;
            case CHAT:
                if (message.payload() instanceof ChatMessage cm) {
                    if (!cm.getFrom().equals(myself.ip() + ":" + myself.port())) {
                        chatManager.addMessage(cm);
                    }
                    // Forward if not back to sender
                    if (!message.sender().equals(myself)) {
                        forwardMessage(message);
                    }
                }
                break;
            case UPDATE_NEIGHBORS:
                handleNeighborUpdate(message);
                break;
            case PING:
                handlePingAlg(message);
                break;
            case PONG:
                handlePongAlg(message);
                break;
            default:
                Logger.log("Unknown message type: " + message.type());
        }
    }

    private void handleNeighborUpdate(Message message) {
        if (message.payload() instanceof NeighborUpdate(NodeInfo newNext, NodeInfo newNextNext, NodeInfo newPrev, NodeInfo newPrevPrev)) {

            if (newNext != null) {
                topology.setNextNode(newNext);
            }
            if (newNextNext != null) {
                topology.setNextNextNode(newNextNext);
            }
            if (newPrev != null) {
                topology.setPrevNode(newPrev);
            }
            if (newPrevPrev != null) {
                topology.setPrevPrevNode(newPrevPrev);
            }

            // If our prev was updated (a new node joined between our old prev and us),
            // send our newNext to the new prev so it knows its newNextNext
            if (newPrev != null && !newPrev.equals(myself)) {
                NodeInfo myNext = topology.getNextNode();
                Message response = new Message(Message.Type.UPDATE_NEIGHBORS, myself, newPrev,
                        new NeighborUpdate(null, myNext, null, null),
                        logicalClock.incrementAndGet());
                socketClient.sendMessage(newPrev, response);

                // Also tell our newNext that their newPrevPrev is now newPrev
                // Before: ... -> oldPrev -> Me -> Next -> ...
                // After: ... -> newPrev -> Me -> Next -> ...
                // So Next's newPrevPrev changes from oldPrev to newPrev
                if (!myNext.equals(myself)) {
                    Message updateNext = new Message(Message.Type.UPDATE_NEIGHBORS, myself, myNext,
                            new NeighborUpdate(null, null, null, newPrev),
                            logicalClock.incrementAndGet());
                    socketClient.sendMessage(myNext, updateNext);
                }
            }

            // If our newNext was updated (ring repair in prev direction),
            // send our prev to the new newNext so it knows its newPrevPrev
            // AND tell our prev that their newNextNext is now our new newNext
            if (newNext != null && !newNext.equals(myself)) {
                NodeInfo myPrev = topology.getPrevNode();

                // Tell new newNext about its newPrevPrev
                Message response = new Message(Message.Type.UPDATE_NEIGHBORS, myself, newNext,
                        new NeighborUpdate(null, null, null, myPrev),
                        logicalClock.incrementAndGet());
                socketClient.sendMessage(newNext, response);

                // Tell our prev that their newNextNext is now our new newNext
                // Before: ... -> Prev -> Me -> [failed] -> newNext -> ...
                // After: ... -> Prev -> Me -> newNext -> ...
                // So Prev's newNextNext should be 'newNext' (skipping the failed node)
                if (!myPrev.equals(myself)) {
                    Message updatePrev = new Message(Message.Type.UPDATE_NEIGHBORS, myself, myPrev,
                            new NeighborUpdate(null, newNext, null, null),
                            logicalClock.incrementAndGet());
                    socketClient.sendMessage(myPrev, updatePrev);
                }
            }

            Logger.log("Neighbors updated from " + message.sender());
        }
    }

    private void forwardMessage(Message message) {
        NodeInfo next = topology.getNextNode();
        if (!next.equals(myself)) {
            socketClient.sendMessage(next, message);
        }
    }

    private void handleJoin(Message message) {
        // S wants to join Me.
        // Ring: ... -> Prev -> Me -> OldNext -> ...
        // After: ... -> Prev -> Me -> S -> OldNext -> ...
        // Prev's nextNext must change from OldNext to S!

        NodeInfo newNode = message.sender();
        NodeInfo oldNext = topology.getNextNode();
        NodeInfo myPrev = topology.getPrevNode();

        // My new Next is S, my new NextNext is oldNext
        topology.setNextNode(newNode);
        topology.setNextNextNode(oldNext);

        // Determine S's neighbors
        NodeInfo newNodeNext;
        NodeInfo newNodeNextNext;
        if (oldNext.equals(myself)) {
            // I was alone, S's next is me
            newNodeNext = myself;
            newNodeNextNext = newNode; // Ring: Me -> S -> Me

            // Also update my prev pointers since S is now my prev too
            topology.setPrevNode(newNode);
            topology.setPrevPrevNode(newNode);
        } else {
            newNodeNext = oldNext;
            // oldNext will send the correct nextNext value to newNode via
            // handleNeighborUpdate response
            // We set null here to avoid race condition (our message might arrive after
            // oldNext's response)
            newNodeNextNext = null;

            // Tell myPrev that their nextNext is now S (the new node)
            // This is crucial: Prev -> Me -> S, so Prev's nextNext should be S
            if (!myPrev.equals(myself)) {
                Message updatePrev = new Message(Message.Type.UPDATE_NEIGHBORS, myself, myPrev,
                        new NeighborUpdate(null, newNode, null, null), logicalClock.incrementAndGet());
                socketClient.sendMessage(myPrev, updatePrev);
            }

            // Tell oldNext that their new prev is S (the joining node)
            // oldNext will respond to S with its next (S's nextNext)
            Message updateOldNext = new Message(Message.Type.UPDATE_NEIGHBORS, myself, oldNext,
                    new NeighborUpdate(null, null, newNode, myself), logicalClock.incrementAndGet());
            socketClient.sendMessage(oldNext, updateOldNext);
        }

        // Send UPDATE_NEIGHBORS to S with its neighbor info
        // Note: nextNext may be null here, will be filled by oldNext's response
        Message resp = new Message(Message.Type.UPDATE_NEIGHBORS, myself, newNode,
                new NeighborUpdate(newNodeNext, newNodeNextNext, myself, myPrev),
                logicalClock.incrementAndGet());
        socketClient.sendMessage(newNode, resp);

        Logger.log("Node " + newNode + " joined the ring. Its next: " + newNodeNext);
    }

    private void handleLeave(Message message) {
        // L leaves gracefully.
        if (message.payload() instanceof NeighborUpdate update) {
            if (topology.getNextNode().equals(message.sender())) {
                if (update.next() != null) {
                    topology.setNextNode(update.next());
                }
                if (update.nextNext() != null) {
                    topology.setNextNextNode(update.nextNext());
                }
                Logger.log("Node " + message.sender() + " left. New Next: " + topology.getNextNode());
            }
        }
    }

    public void join(String ip, int port) {
        NodeInfo target = new NodeInfo(ip, port);
        Message msg = new Message(Message.Type.JOIN, myself, target, null, logicalClock.incrementAndGet());
        boolean success = socketClient.sendMessage(target, msg);
        if (success) {
            Logger.log("Join request sent to " + target);
        } else {
            Logger.error("Failed to contact " + target + " for join.", null);
        }
    }

    public void leave() {
        NodeInfo next = topology.getNextNode();
        NodeInfo prev = topology.getPrevNode();

        if (!next.equals(myself)) {
            // Tell prev that its new next is our next, and new nextNext is our nextNext
            if (!prev.equals(myself)) {
                Message updatePrev = new Message(Message.Type.UPDATE_NEIGHBORS, myself, prev,
                        new NeighborUpdate(next, topology.getNextNextNode(), null, null),
                        logicalClock.incrementAndGet());
                socketClient.sendMessage(prev, updatePrev);
            }

            // Tell next that its new prev is our prev, and new prevPrev is our prevPrev
            Message updateNext = new Message(Message.Type.UPDATE_NEIGHBORS, myself, next,
                    new NeighborUpdate(null, null, prev, topology.getPrevPrevNode()),
                    logicalClock.incrementAndGet());
            socketClient.sendMessage(next, updateNext);
        }

        running = false;
        socketServer.stop();
        apiServer.stop();
    }

    public void sendChatMessage(String content) {
        ChatMessage chatMsg = new ChatMessage(myself.ip() + ":" + myself.port(), content);
        chatManager.addMessage(chatMsg);
        pendingMessages.add(chatMsg);
        Logger.log("Message queued. Waiting for token...");
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("PrevPrev: ").append(topology.getPrevPrevNode()).append("\n");
        sb.append("Prev: ").append(topology.getPrevNode()).append("\n");
        sb.append("This Node: ").append(myself).append("\n");
        sb.append("Next: ").append(topology.getNextNode()).append("\n");
        sb.append("NextNext: ").append(topology.getNextNextNode()).append("\n");
        sb.append("Token: ").append(mutex.hasToken()).append("\n");
        sb.append("LC: ").append(logicalClock.getTime()).append("\n");
        sb.append("Killed: ").append(isKilled).append("\n");
        sb.append("InCS: ").append(inCriticalSection).append("\n");
        synchronized (misraLock) {
            sb.append("Misra: m=").append(m)
                    .append(", hasPing=").append(hasPing).append("(").append(nbPing).append(")")
                    .append(", hasPong=").append(hasPong).append("(").append(nbPong).append(")\n");
        }
        sb.append("Chat History:\n");
        for (ChatMessage msg : chatManager.getHistory()) {
            sb.append("  ").append(msg).append("\n");
        }
        return sb.toString();
    }
}