package cz.cvut.fel.dsva.core;

import java.io.Serializable;

/**
 * Neighbor update message containing all four neighbor pointers.
 * null values mean "no change" for that field.
 */
public record NeighborUpdate(NodeInfo next, NodeInfo nextNext, NodeInfo prev, NodeInfo prevPrev)
        implements Serializable {
}
