package cz.cvut.fel.dsva.core;

import lombok.Getter;

import java.io.Serializable;
import java.util.Objects;

@Getter
public class NodeInfo implements Serializable {
    private final String ip;
    private final int port;
    private final long id; // Hashed ID for ring placement

    public NodeInfo(String ip, int port) {
        this.ip = ip;
        this.port = port;
        this.id = hash(ip, port);
    }

    private long hash(String ip, int port) {
        return Math.abs((ip + ":" + port).hashCode());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        NodeInfo nodeInfo = (NodeInfo) o;
        return port == nodeInfo.port && Objects.equals(ip, nodeInfo.ip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip, port);
    }

    @Override
    public String toString() {
        return ip + ":" + port + "(ID:" + id + ")";
    }
}
