package cz.cvut.fel.dsva.topology;

import cz.cvut.fel.dsva.core.NodeInfo;
import cz.cvut.fel.dsva.utils.Logger;
import lombok.Getter;

@Getter
public class RingTopology {
    private final NodeInfo myself;
    private NodeInfo nextNode;
    private NodeInfo nextNextNode;
    private NodeInfo prevNode;
    private NodeInfo prevPrevNode;

    public RingTopology(NodeInfo myself) {
        this.myself = myself;
        this.nextNode = myself;
        this.nextNextNode = myself;
        this.prevNode = myself;
        this.prevPrevNode = myself;
    }

    public synchronized void setNextNode(NodeInfo next) {
        this.nextNode = next;
        Logger.log("Topology update: Next neighbor is now " + next);
    }

    public synchronized void setNextNextNode(NodeInfo nextNext) {
        this.nextNextNode = nextNext;
        Logger.log("Topology update: NextNext neighbor is now " + nextNext);
    }

    public synchronized void setPrevNode(NodeInfo prev) {
        this.prevNode = prev;
        Logger.log("Topology update: Prev neighbor is now " + prev);
    }

    public synchronized void setPrevPrevNode(NodeInfo prevPrev) {
        this.prevPrevNode = prevPrev;
        Logger.log("Topology update: PrevPrev neighbor is now " + prevPrev);
    }

    public boolean isAlone() {
        return nextNode.equals(myself);
    }

    @Override
    public String toString() {
        return "RingTopology{" +
                "myself=" + myself +
                ", prev=" + prevNode +
                ", prevPrev=" + prevPrevNode +
                ", next=" + nextNode +
                ", nextNext=" + nextNextNode +
                '}';
    }
}