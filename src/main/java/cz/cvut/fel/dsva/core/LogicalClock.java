package cz.cvut.fel.dsva.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe implementation of Lamport Logical Clock.
 */
public class LogicalClock {
    private final AtomicInteger time = new AtomicInteger(0);

    /**
     * Increment local logical clock.
     * Call this before sending a message or performing an internal event.
     */
    public int incrementAndGet() {
        return time.incrementAndGet();
    }

    /**
     * Update logical clock based on received timestamp.
     * Max(local, received) + 1.
     * Call this when receiving a message.
     */
    public void update(int receivedTime) {
        time.updateAndGet(current -> Math.max(current, receivedTime) + 1);
    }

    /**
     * Get current time without incrementing.
     */
    public int getTime() {
        return time.get();
    }
}
