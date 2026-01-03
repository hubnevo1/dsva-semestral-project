package cz.cvut.fel.dsva.core;

import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;

public class Token implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final AtomicLong generationCounter = new AtomicLong(0);

    @Getter
    private final long generationId;

    public Token() {
        this.generationId = generationCounter.incrementAndGet();
    }

    @Override
    public String toString() {
        return "Token{gen=" + generationId + "}";
    }
}