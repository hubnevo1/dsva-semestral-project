package cz.cvut.fel.dsva.core;

import java.io.Serializable;

public record MisraToken(int counter) implements Serializable {

    @Override
    public String toString() {
        return "MisraToken{counter=" + counter + "}";
    }
}