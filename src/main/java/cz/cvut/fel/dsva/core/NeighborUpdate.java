package cz.cvut.fel.dsva.core;

import java.io.Serializable;

public record NeighborUpdate(NodeInfo next, NodeInfo prev) implements Serializable {
}
