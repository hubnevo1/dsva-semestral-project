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

    /**
     * Start the node.
     * 
     * @param isLeader true if this is the first node (bootstrap), false if joining
     *                 existing network
     */
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
                            // If this happens multiple times, we're likely ORPHANED
                            // (not in the active ring)
                            if (consecutiveTimeouts >= 3) {
                                Logger.log(
                                        "Multiple timeouts while next is alive. I may be ORPHANED. Re-joining network...");
                                attemptRejoin();
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
     * Attempt to re-join the network when we detect we're orphaned.
     */
    private void attemptRejoin() {
        // Try to join any live node we know about
        for (NodeInfo node : topology.getAllNodes()) {
            if (node.equals(myself))
                continue;

            Message ping = new Message(Message.Type.PING, myself, node, null, logicalClock.incrementAndGet());
            if (socketClient.sendMessage(node, ping)) {
                Logger.log("Found live node " + node + ". Re-joining...");
                // Send a JOIN request to this node
                Message joinMsg = new Message(Message.Type.JOIN, myself, node, null, logicalClock.incrementAndGet());
                if (socketClient.sendMessage(node, joinMsg)) {
                    Logger.log("Re-join request sent to " + node + ". Waiting for token...");
                    lastTokenSeenTime = System.currentTimeMillis(); // Reset timeout
                    return;
                }
            }
        }

        // If we can't reach anyone, we're truly alone - regenerate token
        Logger.log("Cannot reach any nodes. Generating token as lone node.");
        topology.setNextNode(myself);
        mutex.regenerateToken();
        lastTokenSeenTime = System.currentTimeMillis();
    }

    /**
     * API: Kill the node (simulated failure).
     */
    public void kill() {
        if (!isKilled) {
            isKilled = true;
            socketServer.stop(); // Stop listening
            Logger.log("NODE KILLED (Simulated). Communication stopped.");
        }
    }

    /**
     * API: Revive the node.
     */
    public void revive() {
        if (isKilled) {
            isKilled = false;
            lastTokenSeenTime = System.currentTimeMillis(); // Reset timeout
            try {
                socketServer.start(); // Restart listening
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
        // Wrap in Message and send to Next.
        // Ring broadcast ensures it goes around.
        // We already added it locally? Yes, in sendChatMessage.
        Message netMsg = new Message(Message.Type.CHAT, myself, null, originalMsg, logicalClock.incrementAndGet());

        // Send to next
        NodeInfo next = topology.getNextNode();
        if (!next.equals(myself)) {
            boolean sent = socketClient.sendMessage(next, netMsg);
            if (!sent) {
                Logger.log("Failed to broadcast message to " + next + ". Re-queuing.");
                // Add back to head of queue if possible, or just add (order might flip, but
                // better than loss)
                // ConcurrentLinkedQueue doesn't support addFirst.
                // For simplicity, add to end.
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
                    Logger.log("Failed to pass token to " + next + ". Attempting repair...");
                    handleNeighborFailure(next);
                    // After repair, try passing again?
                    // For now, reclaim token so we don't lose it if we are the survivor
                    mutex.receiveToken(token);
                }
            }
        }
    }

    private void handleNeighborFailure(NodeInfo failedNode) {
        // Remove failed node from topology table
        topology.removeNode(failedNode);

        // Get candidate nodes to try (in ring order after failed node)
        java.util.List<NodeInfo> candidates = topology.getCandidatesAfter(failedNode);

        NodeInfo newNext = null;
        for (NodeInfo candidate : candidates) {
            Logger.log("Trying candidate: " + candidate);

            // Verify candidate is alive via PING
            Message ping = new Message(Message.Type.PING, myself, candidate, null, logicalClock.incrementAndGet());
            if (socketClient.sendMessage(candidate, ping)) {
                newNext = candidate;
                Logger.log("Found live node: " + candidate);
                break;
            } else {
                Logger.log("Candidate " + candidate + " is also unreachable.");
                topology.removeNode(candidate);
            }
        }

        if (newNext != null) {
            // Repair the ring
            topology.setNextNode(newNext);
            Logger.log("Ring REPAIRED. New next: " + newNext);

            // Broadcast updated topology to all nodes (so they know about removed node)
            broadcastTopology();

            // Regenerate token (the failed node may have had it)
            Logger.log("Regenerating token to ensure ring has exactly one token...");
            mutex.regenerateToken();
        } else {
            // No live nodes found - we are alone now
            Logger.log("No live nodes found. I am now alone in the ring.");
            topology.setNextNode(myself);
            mutex.regenerateToken();
        }
    }

    /**
     * Broadcast current topology table to all known nodes.
     */
    private void broadcastTopology() {
        java.util.List<NodeInfo> allNodes = topology.getAllNodes();
        for (NodeInfo node : allNodes) {
            if (!node.equals(myself)) {
                Message topoMsg = new Message(Message.Type.TOPOLOGY_UPDATE, myself, node, allNodes,
                        logicalClock.incrementAndGet());
                socketClient.sendMessage(node, topoMsg);
            }
        }
        Logger.log("Topology broadcast to " + (allNodes.size() - 1) + " nodes. Total nodes: " + allNodes.size());
    }

    @Override
    public void handleMessage(Message message) {
        if (isKilled)
            return; // Silent if killed

        logicalClock.update(message.logicalTime());
        // Logger.log("Received " + message.type + " from " + message.sender);

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
                    if (!cm.getFrom().equals(myself.ip() + ":" + myself.port())) { // Don't re-add own message if
                                                                                         // looped back
                        chatManager.addMessage(cm);
                    }
                    // Forward if not back to sender
                    if (!message.sender().equals(myself)) {
                        forwardMessage(message);
                    }
                }
                break;
            case UPDATE_NEIGHBORS:
                // This is sent by the node we joined, telling us our Next
                if (message.payload() instanceof NodeInfo myNewNext) {
                    topology.setNextNode(myNewNext);
                    // My prev is the sender (the node I joined)
                    topology.setPrevNode(message.sender());
                    Logger.log("Topology received from " + message.sender() + ". My Next: " + myNewNext);
                    // Reset timeout - topology is changing, give system time to stabilize
                    lastTokenSeenTime = System.currentTimeMillis();
                }
                break;
            case TOPOLOGY_UPDATE:
                // Received full topology table from another node
                if (message.payload() instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<NodeInfo> nodes = (java.util.List<NodeInfo>) message.payload();

                    NodeInfo currentNext = topology.getNextNode();
                    boolean nextWasRemoved = !currentNext.equals(myself) && !nodes.contains(currentNext);

                    topology.updateAllNodes(nodes);

                    // If our Next was removed, find a new Next from the updated node list
                    if (nextWasRemoved) {
                        Logger.log("My Next " + currentNext + " was removed. Finding new Next...");
                        for (NodeInfo node : nodes) {
                            if (!node.equals(myself)) {
                                topology.setNextNode(node);
                                Logger.log("New Next neighbor: " + node);
                                break;
                            }
                        }
                    }

                    // Reset timeout - topology is changing, give system time to stabilize
                    lastTokenSeenTime = System.currentTimeMillis();
                }
                break;
            case PING:
                // Heartbeat check - just acknowledge we're alive (no response needed for now)
                // The sender uses successful TCP connection as proof of liveness
                break;
            case UPDATE_PREV:
                // Another node is telling us our new prev
                if (message.payload() instanceof NodeInfo newPrev) {
                    topology.setPrevNode(newPrev);
                    Logger.log("Prev updated to: " + newPrev);
                }
                break;
            default:
                Logger.log("Unknown message type: " + message.type());
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
        // Me -> OldNext becomes Me -> S -> OldNext

        NodeInfo newNode = message.sender();
        NodeInfo oldNext = topology.getNextNode();

        // Add new node to topology table (if not already there)
        topology.addNode(newNode);

        // My new Next is S
        topology.setNextNode(newNode);

        // Determine S's new Next
        // If oldNext was the newNode itself (re-joining), S's next should be ME (close
        // the ring)
        // If oldNext was myself (I was alone), S's next should also be ME
        NodeInfo newNodeNext;
        if (oldNext.equals(newNode) || oldNext.equals(myself)) {
            newNodeNext = myself;
        } else {
            newNodeNext = oldNext;
            // Tell oldNext that their new prev is S (the joining node)
            Message updatePrevMsg = new Message(Message.Type.UPDATE_PREV, myself, newNode,
                    logicalClock.incrementAndGet());
            socketClient.sendMessage(oldNext, updatePrevMsg);
        }

        // Send UPDATE_NEIGHBORS to S with its new Next (payload = newNodeNext)
        // The sender (myself) becomes S's prev
        Message resp = new Message(Message.Type.UPDATE_NEIGHBORS, myself, newNodeNext, logicalClock.incrementAndGet());
        socketClient.sendMessage(newNode, resp);

        // Broadcast updated topology to ALL known nodes (including the new one)
        broadcastTopology();

        Logger.log("Node " + newNode + " joined the ring. Its next: " + newNodeNext);
    }

    private void handleLeave(Message message) {
        // L leaves.
        // Msg payload = L's next (N).
        // Msg sender = L.
        // If L was my next, N becomes my next.
        if (message.payload() instanceof NodeInfo newNext) {
            if (topology.getNextNode().equals(message.sender())) {
                topology.setNextNode(newNext);
                // We don't know new NextNext immediately, need update.
                Logger.log("Node leaved. New Next: " + newNext);
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
        if (!next.equals(myself)) {
            Message msg = new Message(Message.Type.LEAVE, myself, next, next, logicalClock.incrementAndGet());
            socketClient.sendMessage(next, msg);
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
        sb.append("Prev: ").append(topology.getPrevNode()).append("\n");
        sb.append("Token: ").append(mutex.hasToken()).append("\n");
        sb.append("LC: ").append(logicalClock.getTime()).append("\n");
        sb.append("Killed: ").append(isKilled).append("\n");
        sb.append("InCS: ").append(inCriticalSection).append("\n");
        sb.append("Nodes: ").append(topology.getAllNodes().size()).append("\n");
        sb.append("\nChat History:\n");
        for (ChatMessage msg : chatManager.getHistory()) {
            sb.append("  ").append(msg).append("\n");
        }
        return sb.toString();
    }
}