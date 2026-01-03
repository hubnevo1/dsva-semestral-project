package cz.cvut.fel.dsva.network;

import cz.cvut.fel.dsva.core.Message;

public interface MessageHandler {
    void handleMessage(Message message);
}