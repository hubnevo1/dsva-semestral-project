package cz.cvut.fel.dsva.core;

import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class Token implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final AtomicLong generationCounter = new AtomicLong(0);

    @Getter
    private final long generationId;
    private final String hash;

    public Token(long minGenerationId) {
        long newId;
        long current;

        do {
            current = generationCounter.get();
            newId = Math.max(current + 1, minGenerationId + 1);
        } while (!generationCounter.compareAndSet(current, newId));

        this.generationId = newId;
        this.hash = UUID.randomUUID().toString();
    }

    @Override
    public String toString() {
        return "Token{id=" + generationId + ", hash=" + hash + "}";
    }
}