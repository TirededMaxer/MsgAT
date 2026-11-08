package org.msgat.console;

import org.msgat.console.core.GhostNettyServer;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Initializes persistent routing and starts the server.
    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException("Set port in server-config.json beside the JAR; no positional arguments are accepted.");
            var configuration = org.msgat.console.core.ServerConfiguration.load();
            log.info("Configuration: {} (port {})", configuration.file(), configuration.port());
            run(configuration.port());
        } catch (Exception error) {
            log.error("Failed to start server: {}", error.getMessage(), error);
            System.exit(1);
        }
    }

    private static void run(int port) throws Exception {
        org.msgat.console.core.CryptoUtils.validateConfiguration();
        org.msgat.console.db.DatabaseManager.init();
        org.msgat.console.db.CiphertextMailboxStore.initialize();

        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                org.msgat.console.db.DatabaseManager.cleanupOldOfflineMessages();
            } catch (RuntimeException error) {
                log.error("Offline cleanup failed; the next scheduled cleanup will retry", error);
            }
        }, 0, 1, java.util.concurrent.TimeUnit.HOURS);
        scheduler.scheduleAtFixedRate(() -> {
            try { org.msgat.console.db.CiphertextMailboxStore.cleanupExpired(); }
            catch (java.sql.SQLException error) { log.error("V2 ciphertext cleanup failed; the next tick will retry", error); }
        }, 0, 1, java.util.concurrent.TimeUnit.MINUTES);

        BlindRoutingTable routingTable = new BlindRoutingTable();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            routingTable.closeConnections();
            org.msgat.console.db.CiphertextMailboxStore.close();
        }));

        try {
            GhostNettyServer server = new GhostNettyServer(port, routingTable);
            server.start();

        } finally {
            scheduler.shutdownNow();
            routingTable.closeConnections();
            org.msgat.console.db.CiphertextMailboxStore.close();
        }
    }
}
