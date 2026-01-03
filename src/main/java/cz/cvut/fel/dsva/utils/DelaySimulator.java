package cz.cvut.fel.dsva.utils;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicLong;

@Getter
public class DelaySimulator {
    private static final AtomicLong delayMs = new AtomicLong(0);

    public static void setDelayMs(long ms) {
        delayMs.set(ms);
        Logger.log("Delay set to " + ms + " ms");
    }

    public static void waitIfRequired() {
        long ms = delayMs.get();
        if (ms > 0) {
            try {
                Logger.log("Simulating delay of " + ms + " ms...");
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}