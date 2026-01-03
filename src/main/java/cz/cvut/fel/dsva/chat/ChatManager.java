package cz.cvut.fel.dsva.chat;

import cz.cvut.fel.dsva.utils.Logger;
import java.util.ArrayList;
import java.util.List;

public class ChatManager {
    private final List<ChatMessage> history = new ArrayList<>();

    public void addMessage(ChatMessage msg) {
        synchronized (history) {
            history.add(msg);
            System.out.println(">>> CHAT " + msg.toString());
            Logger.log("Chat received from " + msg.getFrom());
        }
    }

    public List<ChatMessage> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }
}