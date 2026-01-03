package cz.cvut.fel.dsva.chat;

import lombok.Getter;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
public class ChatMessage implements Serializable {
    private final String from;
    private final String content;
    private final LocalDateTime timestamp;

    public ChatMessage(String from, String content) {
        this.from = from;
        this.content = content;
        this.timestamp = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + from + ": " + content;
    }
}