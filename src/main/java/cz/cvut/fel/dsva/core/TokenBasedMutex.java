package cz.cvut.fel.dsva.core;

import cz.cvut.fel.dsva.utils.Logger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TokenBasedMutex {
    private final Lock lock = new ReentrantLock();
    private final Condition tokenReceived = lock.newCondition();
    private boolean hasToken = false;
    private Token currentToken = null;
    private long lastSeenGenerationId = 0;

    public void receiveToken(Token token) {
        lock.lock();
        try {
            if (token.getGenerationId() < lastSeenGenerationId) {
                Logger.log("Discarding old token (gen=" + token.getGenerationId() + "), current gen="
                        + lastSeenGenerationId);
                return;
            }

            if (token.getGenerationId() == lastSeenGenerationId && currentToken != null) {
                int cmp = token.getHash().compareTo(currentToken.getHash());
                if (cmp > 0) {
                    Logger.log("Discarding duplicate token (gen=" + token.getGenerationId() +
                            ", hash=" + token.getHash().substring(0, 8) + "...), keeping current hash=" +
                            currentToken.getHash().substring(0, 8) + "...");
                    return;
                } else if (cmp < 0) {
                    Logger.log("Replacing token with smaller hash (gen=" + token.getGenerationId() + ")");
                }
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

    public boolean hasToken() {
        lock.lock();
        try {
            return hasToken;
        } finally {
            lock.unlock();
        }
    }

    public void regenerateToken() {
        lock.lock();
        try {
            Token newToken = new Token(lastSeenGenerationId);
            this.currentToken = newToken;
            this.hasToken = true;
            this.lastSeenGenerationId = newToken.getGenerationId();
            Logger.log("Token REGENERATED: " + newToken);
            tokenReceived.signalAll();
        } finally {
            lock.unlock();
        }
    }
}