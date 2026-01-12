package cz.cvut.fel.dsva.core;

import java.io.Serializable;

public record NodeInfo(String ip, int port) implements Serializable {

    @Override
    public String toString() {
        return ip + ":" + port;
    }
}