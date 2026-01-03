package cz.cvut.fel.dsva.utils;

import cz.cvut.fel.dsva.core.LogicalClock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    private static String nodeId;
    private static LogicalClock logicalClock;

    public static void init(String id, LogicalClock lc) {
        nodeId = id;
        logicalClock = lc;
    }

    public static void log(String message) {
        String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        int lcTime = (logicalClock != null) ? logicalClock.getTime() : 0;
        String idInfo = (nodeId != null) ? "[" + nodeId + "]" : "[UNKNOWN]";

        System.out.printf("%s %s [LC:%d] %s%n", timestamp, idInfo, lcTime, message);
    }

    public static void error(String message, Throwable e) {
        log("ERROR: " + message);
        if (e != null) {
            e.printStackTrace();
        }
    }
}