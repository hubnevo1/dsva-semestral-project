package cz.cvut.fel.dsva.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import cz.cvut.fel.dsva.core.Node;
import cz.cvut.fel.dsva.utils.DelaySimulator;
import cz.cvut.fel.dsva.utils.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RestApiServer {
    private final int port;
    private final Node node;
    private HttpServer server;

    public RestApiServer(int port, Node node) {
        this.port = port;
        this.node = node;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/status", new StatusHandler());
        server.createContext("/join", new JoinHandler());
        server.createContext("/leave", new LeaveHandler());
        server.createContext("/chat", new ChatHandler());
        server.createContext("/setDelayMs", new DelayHandler());
        server.createContext("/kill", new KillHandler());
        server.createContext("/revive", new ReviveHandler());
        server.createContext("/enterCS", new EnterCSHandler());
        server.createContext("/leaveCS", new LeaveCSHandler());

        server.setExecutor(null);
        server.start();
        Logger.log("REST API Server started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> queryToMap(String query) {
        if (query == null)
            return Map.of();
        return Stream.of(query.split("&"))
                .map(s -> s.split("="))
                .filter(a -> a.length == 2)
                .collect(Collectors.toMap(a -> a[0], a -> a[1]));
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendResponse(exchange, 200, node.getStatus());
        }
    }

    private class JoinHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String ip = params.get("ip");
            String portStr = params.get("port");
            if (ip != null && portStr != null) {
                try {
                    node.join(ip, Integer.parseInt(portStr));
                    sendResponse(exchange, 200, "Join initiated");
                } catch (Exception e) {
                    sendResponse(exchange, 400, "Error: " + e.getMessage());
                }
            } else {
                sendResponse(exchange, 400, "Missing ip or port");
            }
        }
    }

    private class LeaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            node.leave();
            sendResponse(exchange, 200, "Leave initiated");
        }
    }

    private class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String msg = params.get("msg");
            if (msg != null) {
                node.sendChatMessage(msg);
                sendResponse(exchange, 200, "Message queued");
            } else {
                sendResponse(exchange, 400, "Missing msg");
            }
        }
    }

    private class DelayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String ms = params.get("ms");
            if (ms != null) {
                long val = Long.parseLong(ms);
                DelaySimulator.setDelayMs(val);
                sendResponse(exchange, 200, "Delay set to " + val);
            } else {
                sendResponse(exchange, 400, "Missing ms");
            }
        }
    }

    private class KillHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            node.kill();
            sendResponse(exchange, 200, "Node KILLED");
        }
    }

    private class ReviveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            node.revive();
            sendResponse(exchange, 200, "Node REVIVED");
        }
    }

    private class EnterCSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            node.enterCS();
            sendResponse(exchange, 200, "Requesting CS Entry");
        }
    }

    private class LeaveCSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            node.leaveCS();
            sendResponse(exchange, 200, "Leaving CS");
        }
    }
}