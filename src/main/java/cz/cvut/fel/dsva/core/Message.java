package cz.cvut.fel.dsva.core;

import java.io.Serializable;

public record Message(Type type, NodeInfo sender, NodeInfo target, Object payload, int logicalTime) implements Serializable {

    public enum Type {
        JOIN, // Request to join the ring
        LEAVE, // Graceful exit
        TOKEN, // Passing the mutex token
        CHAT, // Chat message
        UPDATE_NEIGHBORS, // Update next/prev/nextNext/prevPrev pointers
        PING, // Travels in Next direction
        PONG // Travels in Prev direction
    }

    // Target is optional, can be null for broadcast/ring-pass
    public Message(Type type, NodeInfo sender, Object payload, int logicalTime) {
        this(type, sender, null, payload, logicalTime);
    }

    @Override
    public String toString() {
        return String.format("Message{type=%s, sender=%s, time=%d, payload=%s}",
                type, sender, logicalTime, payload);
    }
}