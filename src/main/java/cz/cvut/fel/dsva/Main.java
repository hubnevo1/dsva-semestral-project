package cz.cvut.fel.dsva;

import cz.cvut.fel.dsva.core.Node;
import cz.cvut.fel.dsva.utils.Logger;

public class Main {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java Main <local-ip> <local-port> <api-port> [join-ip] [join-port]");
            return;
        }

        String ip = args[0];
        int port = Integer.parseInt(args[1]);
        int apiPort = Integer.parseInt(args[2]);

        Node node = new Node(ip, port, apiPort);

        try {
            if (args.length >= 5) {
                // Joining an existing network - NOT a leader
                node.start(false);
                String joinIp = args[3];
                int joinPort = Integer.parseInt(args[4]);
                Logger.log("Attempting to join " + joinIp + ":" + joinPort);
                node.join(joinIp, joinPort);
            } else {
                // First node - IS the leader
                node.start(true);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}