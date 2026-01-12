package cz.cvut.fel.dsva.network;

import cz.cvut.fel.dsva.core.Message;
import cz.cvut.fel.dsva.utils.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketServer {
    private final int port;
    private final MessageHandler handler;
    private ServerSocket serverSocket;
    private volatile boolean running;
    private ExecutorService executor;

    public SocketServer(int port, MessageHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    public void start() throws IOException {
        // Create new executor (in case we're restarting after stop)
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newCachedThreadPool();
        }

        serverSocket = new ServerSocket(port);
        running = true;
        Logger.log("Socket server started on port " + port);

        new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    if (running && executor != null && !executor.isShutdown()) {
                        executor.submit(() -> handleConnection(clientSocket));
                    }
                } catch (IOException e) {
                    if (running) {
                        Logger.error("Error accepting connection: " + e.getMessage(), e);
                    }
                }
            }
        }).start();
    }

    private void handleConnection(Socket socket) {
        try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            Object obj = in.readObject();
            if (obj instanceof Message) {
                handler.handleMessage((Message) obj);
            }
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Error reading message from " + socket.getInetAddress(), e);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Logger.error("Error closing server socket", e);
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executor = null;
        }
    }
}