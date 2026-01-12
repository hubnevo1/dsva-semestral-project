package cz.cvut.fel.dsva.core;

import java.util.concurrent.atomic.AtomicInteger;

public class LogicalClock {
    private final AtomicInteger time = new AtomicInteger(0);

    public int incrementAndGet() {
        return time.incrementAndGet();
    }

    public void update(int receivedTime) {
        time.updateAndGet(current -> Math.max(current, receivedTime) + 1);
    }

    public int getTime() {
        return time.get();
    }
}
