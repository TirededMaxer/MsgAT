package org.msgat.console;

import org.msgat.console.core.GhostNettyServer;
import org.msgat.console.guard.KillSwitchMonitor;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final int DEFAULT_PORT = 34478;

    // Entry point of the application; initializes database, routing, security monitors, and starts the server.
    public static void main(String[] args) {
        org.msgat.console.db.DatabaseManager.init();

        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            org.msgat.console.db.DatabaseManager.cleanupOldOfflineMessages();
        }, 0, 1, java.util.concurrent.TimeUnit.HOURS);

        BlindRoutingTable routingTable = new BlindRoutingTable();

        KillSwitchMonitor killSwitch = new KillSwitchMonitor(routingTable);
        killSwitch.startMonitoring();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            routingTable.secureWipe();
        }));

        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

            GhostNettyServer server = new GhostNettyServer(port, routingTable);
            server.start();

        } catch (Exception e) {
            log.error("Failed to start server", e);
            routingTable.secureWipe();
            System.exit(1);
        }
    }
}