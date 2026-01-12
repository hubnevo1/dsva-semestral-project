package cz.cvut.fel.dsva.core;

import lombok.Getter;

import java.io.Serializable;
import java.util.Objects;

@Getter
public class NodeInfo implements Serializable {
    private final String ip;
    private final int port;

    public NodeInfo(String ip, int port) {
        this.ip = ip;
        this.port = port;
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
    public String toString() {
        return ip + ":" + port;
    }
}
