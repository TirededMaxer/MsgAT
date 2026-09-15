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
        org.msgat.console.core.CryptoUtils.validateConfiguration();
        org.msgat.console.db.DatabaseManager.init();

        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            org.msgat.console.db.DatabaseManager.cleanupOldOfflineMessages();
        }, 0, 1, java.util.concurrent.TimeUnit.HOURS);

        BlindRoutingTable routingTable = new BlindRoutingTable();

        KillSwitchMonitor killSwitch = new KillSwitchMonitor(routingTable);
        if (Boolean.parseBoolean(System.getenv("MSGAT_ENABLE_KILL_SWITCH"))) killSwitch.startMonitoring();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            routingTable.closeConnections();
        }));

        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

            GhostNettyServer server = new GhostNettyServer(port, routingTable);
            server.start();

        } catch (Exception e) {
            log.error("Failed to start server", e);
            scheduler.shutdownNow();
            routingTable.closeConnections();
            System.exit(1);
        }
    }
}
