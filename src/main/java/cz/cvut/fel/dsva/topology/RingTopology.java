package cz.cvut.fel.dsva.topology;

import cz.cvut.fel.dsva.core.NodeInfo;
import cz.cvut.fel.dsva.utils.Logger;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Getter
public class RingTopology {
    private final NodeInfo myself;
    private NodeInfo nextNode;
    private NodeInfo prevNode;

    private final List<NodeInfo> allNodes = new CopyOnWriteArrayList<>();

    public RingTopology(NodeInfo myself) {
        this.myself = myself;
        this.nextNode = myself;
        this.prevNode = myself;
        this.allNodes.add(myself);
    }

    public synchronized void setNextNode(NodeInfo next) {
        this.nextNode = next;
        Logger.log("Topology update: Next neighbor is now " + next);
    }

    public synchronized void setPrevNode(NodeInfo prev) {
        this.prevNode = prev;
        Logger.log("Topology update: Previous neighbor is now " + prev);
    }

    public boolean isAlone() {
        return nextNode.equals(myself);
    }

    /**
     * Add a node to the topology table.
     */
    public void addNode(NodeInfo node) {
        if (!allNodes.contains(node)) {
            allNodes.add(node);
            Logger.log("Topology table: Added node " + node + ". Total nodes: " + allNodes.size());
        }
    }

    /**
     * Remove a failed node from the topology table.
     */
    public void removeNode(NodeInfo node) {
        allNodes.remove(node);
        Logger.log("Topology table: Removed node " + node + ". Total nodes: " + allNodes.size());
    }

    /**
     * Get all known nodes (for broadcasting topology).
     */
    public List<NodeInfo> getAllNodes() {
        return new ArrayList<>(allNodes);
    }

    /**
     * Update the full topology table (received from another node).
     * Syncs our list with the received list - removes nodes not in received list,
     * adds nodes that are in received list but not in ours.
     */
    public void updateAllNodes(List<NodeInfo> nodes) {
        int sizeBefore = allNodes.size();

        // Remove nodes that are not in the received list (they were removed)
        boolean removed = allNodes.removeIf(node -> !nodes.contains(node) && !node.equals(myself));

        // Add nodes that are in received list but not in ours
        boolean added = false;
        for (NodeInfo node : nodes) {
            if (!allNodes.contains(node)) {
                allNodes.add(node);
                added = true;
            }
        }

        // Only log if there was an actual change
        if (removed || added) {
            Logger.log("Topology table updated: " + sizeBefore + " -> " + allNodes.size() + " nodes.");
        }
    }

    /**
     * Find the next live node after the failed node.
     * Returns nodes in order after 'failedNode' (excluding self and failedNode).
     */
    public List<NodeInfo> getCandidatesAfter(NodeInfo failedNode) {
        List<NodeInfo> candidates = new ArrayList<>();

        // Find index of failed node
        int failedIndex = -1;
        for (int i = 0; i < allNodes.size(); i++) {
            if (allNodes.get(i).equals(failedNode)) {
                failedIndex = i;
                break;
            }
        }

        if (failedIndex == -1) {
            // Failed node not in list, return all except self
            for (NodeInfo node : allNodes) {
                if (!node.equals(myself) && !node.equals(failedNode)) {
                    candidates.add(node);
                }
            }
            return candidates;
        }

        // Return nodes in ring order starting after failed node
        int size = allNodes.size();
        for (int i = 1; i < size; i++) {
            int idx = (failedIndex + i) % size;
            NodeInfo candidate = allNodes.get(idx);
            if (!candidate.equals(myself) && !candidate.equals(failedNode)) {
                candidates.add(candidate);
            }
        }

        return candidates;
    }
}