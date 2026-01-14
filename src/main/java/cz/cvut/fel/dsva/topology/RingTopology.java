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

    public void addNode(NodeInfo node) {
        if (!allNodes.contains(node)) {
            allNodes.add(node);
            Logger.log("Topology table: Added node " + node + ". Total nodes: " + allNodes.size());
        }
    }

    public void removeNode(NodeInfo node) {
        allNodes.remove(node);
        Logger.log("Topology table: Removed node " + node + ". Total nodes: " + allNodes.size());
    }

    public List<NodeInfo> getAllNodes() {
        return new ArrayList<>(allNodes);
    }

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
            // Failed node not in list, return all except self and prevNode
            for (NodeInfo node : allNodes) {
                if (!node.equals(myself) && !node.equals(failedNode) && !node.equals(prevNode)) {
                    candidates.add(node);
                }
            }
            // Add prevNode last (fallback - only if all else fails)
            if (prevNode != null && !prevNode.equals(myself) && !prevNode.equals(failedNode)) {
                candidates.add(prevNode);
            }
            return candidates;
        }

        // Return nodes in ring order starting after failed node
        int size = allNodes.size();
        for (int i = 1; i < size; i++) {
            int idx = (failedIndex + i) % size;
            NodeInfo candidate = allNodes.get(idx);
            if (!candidate.equals(myself) && !candidate.equals(failedNode) && !candidate.equals(prevNode)) {
                candidates.add(candidate);
            }
        }

        // Add prevNode as last resort (fallback to close the ring if no other nodes available)
        if (prevNode != null && !prevNode.equals(myself) && !prevNode.equals(failedNode)) {
            candidates.add(prevNode);
        }

        return candidates;
    }
}