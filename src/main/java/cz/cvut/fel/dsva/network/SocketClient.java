package cz.cvut.fel.dsva.network;

import cz.cvut.fel.dsva.core.Message;
import cz.cvut.fel.dsva.core.NodeInfo;
import cz.cvut.fel.dsva.utils.DelaySimulator;
import cz.cvut.fel.dsva.utils.Logger;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class SocketClient {

    public boolean sendMessage(NodeInfo target, Message message) {
        // Simulate delay before sending
        DelaySimulator.waitIfRequired();

        try (Socket socket = new Socket(target.getIp(), target.getPort());
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            out.writeObject(message);
            out.flush();
            return true;
        } catch (IOException e) {
            // Don't print full stack trace for expected connection failures
            Logger.log("Send failed to " + target + ": " + e.getMessage());
            return false;
        }
    }
}