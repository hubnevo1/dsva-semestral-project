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

        if (isLeader) {
            Logger.log("Started as LEADER. Generating initial token.");
            mutex.regenerateToken();
        } else {
            Logger.log("Started as FOLLOWER. Will receive token from network.");
        }

        // Start heartbeat thread for failure detection
        new Thread(this::heartbeatLoop).start();
    }

    private static final int TOKEN_TIMEOUT_MS = 10000; // 10 seconds without token = suspect failure
    private volatile long lastTokenSeenTime = System.currentTimeMillis();

    private void tokenLoop() {
        while (running) {
            try {
                if (isKilled) {
                    Thread.sleep(1000);
                    continue;
                }

                if (mutex.hasToken()) {
                    lastTokenSeenTime = System.currentTimeMillis(); // Reset timeout

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
     * Heartbeat loop - periodically check if neighbors are alive.
     * If we haven't seen the token in a while, the token holder may have crashed.
     */
    private void heartbeatLoop() {
        int consecutiveTimeouts = 0;

        while (running) {
            try {
                Thread.sleep(3000); // Check every 3 seconds

                if (isKilled || mutex.hasToken()) {
                    consecutiveTimeouts = 0;
                    continue;
                }

                // Check if token is taking too long
                long elapsed = System.currentTimeMillis() - lastTokenSeenTime;
                if (elapsed > TOKEN_TIMEOUT_MS && !topology.isAlone()) {
                    consecutiveTimeouts++;
                    Logger.log("Token timeout #" + consecutiveTimeouts + "! Last seen " + (elapsed / 1000) + "s ago.");

                    // Ping our next neighbor to see if they're alive
                    NodeInfo next = topology.getNextNode();
                    if (!next.equals(myself)) {
                        Message ping = new Message(Message.Type.PING, myself, next, null,
                                logicalClock.incrementAndGet());
                        if (!socketClient.sendMessage(next, ping)) {
                            Logger.log("Next neighbor " + next + " is dead! Initiating repair...");
                            handleNeighborFailure(next);
                            consecutiveTimeouts = 0;
                        } else {
                            // Next is alive, but we're not getting tokens
                            // If this happens multiple times, ping our prev to check if we're orphaned
                            if (consecutiveTimeouts >= 3) {
                                Logger.log("Multiple timeouts. Checking if I'm orphaned...");
                                NodeInfo prev = topology.getPrevNode();
                                if (!prev.equals(myself)) {
                                    Message pingPrev = new Message(Message.Type.PING, myself, prev, null,
                                            logicalClock.incrementAndGet());
                                    if (!socketClient.sendMessage(prev, pingPrev)) {
                                        Logger.log("Prev neighbor " + prev + " is also dead! I may be isolated.");
                                        // Try nextNext as last resort
                                        attemptRecovery();
                                    }
                                }
                                consecutiveTimeouts = 0;
                            }
                        }
                    }
                } else {
                    consecutiveTimeouts = 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Attempt recovery when we detect we're possibly isolated.
     */
    private void attemptRecovery() {
        NodeInfo nextNext = topology.getNextNextNode();
        if (!nextNext.equals(myself)) {
            Message ping = new Message(Message.Type.PING, myself, nextNext, null, logicalClock.incrementAndGet());
            if (socketClient.sendMessage(nextNext, ping)) {
                Logger.log("Found live node via nextNext: " + nextNext + ". Re-joining...");
                Message joinMsg = new Message(Message.Type.JOIN, myself, nextNext, null,
                        logicalClock.incrementAndGet());
                if (socketClient.sendMessage(nextNext, joinMsg)) {
                    Logger.log("Re-join request sent to " + nextNext + ". Waiting for token...");
                    lastTokenSeenTime = System.currentTimeMillis();
                    return;
                }
            }
        }

        // If we can't reach nextNext, try prevPrev
        NodeInfo prevPrev = topology.getPrevPrevNode();
        if (!prevPrev.equals(myself)) {
            Message ping = new Message(Message.Type.PING, myself, prevPrev, null, logicalClock.incrementAndGet());
            if (socketClient.sendMessage(prevPrev, ping)) {
                Logger.log("Found live node via prevPrev: " + prevPrev + ". Re-joining...");
                Message joinMsg = new Message(Message.Type.JOIN, myself, prevPrev, null,
                        logicalClock.incrementAndGet());
                if (socketClient.sendMessage(prevPrev, joinMsg)) {
                    Logger.log("Re-join request sent to " + prevPrev + ". Waiting for token...");
                    lastTokenSeenTime = System.currentTimeMillis();
                    return;
                }
            }
        }

        // If we can't reach anyone, we're truly alone - regenerate token
        Logger.log("Cannot reach any nodes. Generating token as lone node.");
        topology.setNextNode(myself);
        topology.setNextNextNode(myself);
        topology.setPrevNode(myself);
        topology.setPrevPrevNode(myself);
        mutex.regenerateToken();
        lastTokenSeenTime = System.currentTimeMillis();
    }

    /**
     * API: Kill the node (simulated failure).
     */
    public void kill() {
        if (!isKilled) {
            isKilled = true;
            socketServer.stop();
            Logger.log("NODE KILLED (Simulated). Communication stopped.");
        }
    }

    /**
     * API: Revive the node.
     */
    public void revive() {
        if (isKilled) {
            isKilled = false;
            lastTokenSeenTime = System.currentTimeMillis();
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
                    handleNeighborFailure(next);
                    // After repair, reclaim token so we don't lose it
                    mutex.receiveToken(token);
                }
            }
        }
    }

    /**
     * Handle failure of the next neighbor by using nextNextNode.
     */
    private void handleNeighborFailure(NodeInfo failedNode) {
        NodeInfo nextNext = topology.getNextNextNode();

        if (nextNext.equals(myself) || nextNext.equals(failedNode)) {
            // No backup available, we're alone now
            Logger.log("No backup next available. I am now alone in the ring.");
            topology.setNextNode(myself);
            topology.setNextNextNode(myself);
            mutex.regenerateToken();
            return;
        }

        // Verify nextNext is alive
        Message ping = new Message(Message.Type.PING, myself, nextNext, null, logicalClock.incrementAndGet());
        if (socketClient.sendMessage(nextNext, ping)) {
            // nextNext is alive, use it as our new next
            topology.setNextNode(nextNext);
            topology.setNextNextNode(myself); // Will be updated by nextNext
            Logger.log("Ring REPAIRED. New next: " + nextNext);

            // Tell nextNext that we are now its prev, and ask for its nextNode as our new
            // nextNext
            // Also tell nextNext to update its prevPrev
            Message updateMsg = new Message(Message.Type.UPDATE_NEIGHBORS, myself, nextNext,
                    new NeighborUpdate(null, null, myself, topology.getPrevNode()),
                    logicalClock.incrementAndGet());
            socketClient.sendMessage(nextNext, updateMsg);

            // Regenerate token (the failed node may have had it)
            Logger.log("Regenerating token to ensure ring has exactly one token...");
            mutex.regenerateToken();
        } else {
            // nextNext is also dead, we're alone
            Logger.log("nextNext " + nextNext + " is also unreachable. I am alone.");
            topology.setNextNode(myself);
            topology.setNextNextNode(myself);
            topology.setPrevNode(myself);
            topology.setPrevPrevNode(myself);
            mutex.regenerateToken();
        }
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
                // Heartbeat check - just acknowledge we're alive (no response needed for now)
                // The sender uses successful TCP connection as proof of liveness
                break;
            default:
                Logger.log("Unknown message type: " + message.type());
        }
    }

    private void handleNeighborUpdate(Message message) {
        if (message.payload() instanceof NeighborUpdate update) {
            NodeInfo newPrev = update.prev();

            if (update.next() != null) {
                topology.setNextNode(update.next());
            }
            if (update.nextNext() != null) {
                topology.setNextNextNode(update.nextNext());
            }
            if (newPrev != null) {
                topology.setPrevNode(newPrev);
            }
            if (update.prevPrev() != null) {
                topology.setPrevPrevNode(update.prevPrev());
            }

            // If our prev was updated (a new node joined between our old prev and us),
            // send our next to the new prev so it knows its nextNext
            if (newPrev != null && !newPrev.equals(myself)) {
                NodeInfo myNext = topology.getNextNode();
                Message response = new Message(Message.Type.UPDATE_NEIGHBORS, myself, newPrev,
                        new NeighborUpdate(null, myNext, null, null),
                        logicalClock.incrementAndGet());
                socketClient.sendMessage(newPrev, response);
            }

            Logger.log("Neighbors updated from " + message.sender());
            // Reset timeout - topology is changing, give system time to stabilize
            lastTokenSeenTime = System.currentTimeMillis();
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
        // Ring: ... -> Me -> OldNext -> OldNext.Next -> ...
        // After: ... -> Me -> S -> OldNext -> OldNext.Next -> ...

        NodeInfo newNode = message.sender();
        NodeInfo oldNext = topology.getNextNode();

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
            // We don't know oldNext's next yet, oldNext will tell us via UPDATE_NEIGHBORS
            // response
            newNodeNextNext = myself; // Placeholder, will be updated by oldNext's response

            // Tell oldNext that their new prev is S (the joining node)
            // And their new prevPrev is me
            // oldNext will respond with its next (which becomes S's nextNext)
            Message updateOldNext = new Message(Message.Type.UPDATE_NEIGHBORS, myself, oldNext,
                    new NeighborUpdate(null, null, newNode, myself), logicalClock.incrementAndGet());
            socketClient.sendMessage(oldNext, updateOldNext);
        }

        // Send UPDATE_NEIGHBORS to S with its neighbor info
        // Note: nextNextNode will be updated by oldNext's response forwarded to S
        Message resp = new Message(Message.Type.UPDATE_NEIGHBORS, myself, newNode,
                new NeighborUpdate(newNodeNext, newNodeNextNext, myself, topology.getPrevNode()),
                logicalClock.incrementAndGet());
        socketClient.sendMessage(newNode, resp);

        Logger.log("Node " + newNode + " joined the ring. Its next: " + newNodeNext);
    }

    private void handleLeave(Message message) {
        // L leaves gracefully.
        // Payload contains L's next node (who should become our new next if L was our
        // next)
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
        sb.append("Node: ").append(myself).append("\n");
        sb.append("Next: ").append(topology.getNextNode()).append("\n");
        sb.append("NextNext: ").append(topology.getNextNextNode()).append("\n");
        sb.append("Prev: ").append(topology.getPrevNode()).append("\n");
        sb.append("PrevPrev: ").append(topology.getPrevPrevNode()).append("\n");
        sb.append("Token: ").append(mutex.hasToken()).append("\n");
        sb.append("LC: ").append(logicalClock.getTime()).append("\n");
        sb.append("Killed: ").append(isKilled).append("\n");
        sb.append("InCS: ").append(inCriticalSection).append("\n");
        sb.append("Chat History:\n");
        for (ChatMessage msg : chatManager.getHistory()) {
            sb.append("  ").append(msg).append("\n");
        }
        return sb.toString();
    }
}