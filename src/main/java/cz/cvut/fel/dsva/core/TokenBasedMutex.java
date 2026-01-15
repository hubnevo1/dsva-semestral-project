package cz.cvut.fel.dsva.core;

import cz.cvut.fel.dsva.utils.Logger;
import lombok.Getter;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBasedMutex {
    private final Lock lock = new ReentrantLock();
    private final Condition tokenReceived = lock.newCondition();
    private boolean hasToken = false;
    private Token currentToken = null;
    @Getter
    private long lastSeenGenerationId = 0;

    public void receiveToken(Token token) {
        lock.lock();
        try {
            // Check if this token is newer than what we've seen
            if (token.getGenerationId() < lastSeenGenerationId) {
                Logger.log("Discarding old token (gen=" + token.getGenerationId() + "), current gen="
                        + lastSeenGenerationId);
                return;
            }

            lastSeenGenerationId = token.getGenerationId();
            this.hasToken = true;
            this.currentToken = token;
            Logger.log("Token received: " + token);
            tokenReceived.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public Token yieldToken() {
        lock.lock();
        try {
            if (!hasToken) {
                return null;
            }
            hasToken = false;
            Token t = currentToken;
            currentToken = null;
            return t;
        } finally {
            lock.unlock();
        }
    }

    public void waitForToken() throws InterruptedException {
        lock.lock();
        try {
            while (!hasToken) {
                tokenReceived.await();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean hasToken() {
        lock.lock();
        try {
            return hasToken;
        } finally {
            lock.unlock();
        }
    }

    public Token regenerateToken() {
        lock.lock();
        try {
            Token newToken = new Token(lastSeenGenerationId);
            this.currentToken = newToken;
            this.hasToken = true;
            this.lastSeenGenerationId = newToken.getGenerationId();
            Logger.log("Token REGENERATED: " + newToken);
            tokenReceived.signalAll();
            return newToken;
        } finally {
            lock.unlock();
        }
    }
}