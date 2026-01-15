package cz.cvut.fel.dsva.core;

import java.io.Serializable;

public record NeighborUpdate(NodeInfo next, NodeInfo nextNext, NodeInfo prev, NodeInfo prevPrev) implements Serializable {
}