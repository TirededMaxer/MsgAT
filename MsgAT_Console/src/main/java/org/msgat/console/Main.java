package org.msgat.console;

import ch.qos.logback.classic.LoggerContext;
import org.msgat.console.core.GhostNettyServer;
import org.msgat.console.core.AndroidPushRelay;
import org.msgat.console.core.RevocationCleanup;
import org.msgat.console.core.ServerConfiguration;
import org.msgat.console.routing.BlindRoutingTable;
import org.msgat.console.db.AttachmentStore;
import org.msgat.console.db.CiphertextMailboxStore;
import org.msgat.console.db.IdentityRevocations;
import org.msgat.console.db.SignalKeyDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Initialize durable ciphertext stores and the in-memory online routes.
    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException("Set port in server-config.json beside the JAR; no positional arguments are accepted.");
            var configuration = ServerConfiguration.load();
            if (configuration.logLevel() != null) {
                var context = (LoggerContext) LoggerFactory.getILoggerFactory();
                context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(configuration.logLevel());
            }
            log.info("Configuration: {} (listen {})", configuration.file(), configuration.listener());
            run(configuration);
        } catch (Exception error) {
            log.error("Failed to start server: {}", error.getMessage(), error);
            System.exit(1);
        }
    }

    private static void run(ServerConfiguration configuration) throws Exception {
        System.setProperty("msgat.data.dir", configuration.dataDirectory().toString());
        var resourcesStopped = new CountDownLatch(1);
        Thread shutdownHook = null;
        try {
            IdentityRevocations.initialize();
            CiphertextMailboxStore.initialize(configuration.mailboxLimits());
            SignalKeyDirectory.initialize();
            try (var attachments = new AttachmentStore(configuration.dataDirectory(), configuration.attachmentLimits());
                 var androidPush = configuration.pushOrigin() == null ? null : new AndroidPushRelay(configuration.pushOrigin())) {
                var routingTable = new BlindRoutingTable();
                var scheduler = new ScheduledThreadPoolExecutor(1);
                scheduler.setRemoveOnCancelPolicy(true);
                var revocationCleanup = new RevocationCleanup(scheduler, attachments);
                var server = new GhostNettyServer(configuration, routingTable, attachments, androidPush, revocationCleanup::wake);
                try {
                    scheduleMaintenance(scheduler, attachments);
                    revocationCleanup.wake();
                    var hook = new Thread(() -> {
                        server.requestStop();
                        awaitCompletion(resourcesStopped);
                    }, "msgat-shutdown");
                    Runtime.getRuntime().addShutdownHook(hook);
                    shutdownHook = hook;
                    server.start();
                } finally {
                    server.requestStop();
                    stopMaintenance(scheduler);
                    routingTable.closeConnections();
                }
            }
        } finally {
            try {
                SignalKeyDirectory.close();
                CiphertextMailboxStore.close();
                IdentityRevocations.close();
            } finally {
                resourcesStopped.countDown();
                if (shutdownHook != null) {
                    try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                    catch (IllegalStateException shuttingDown) { /* The hook is already draining. */ }
                }
            }
        }
    }

    private static void scheduleMaintenance(ScheduledExecutorService scheduler, AttachmentStore attachments) {
        scheduler.execute(() -> cleanAndReschedule(scheduler, "V2 ciphertext", CiphertextMailboxStore::cleanupExpired));
        scheduler.execute(() -> cleanAndReschedule(scheduler, "Directory", SignalKeyDirectory::cleanupExpired));
        scheduler.schedule(() -> cleanAndReschedule(scheduler, "Encrypted object", attachments::cleanupExpired),
                60, TimeUnit.SECONDS);
    }

    private static void stopMaintenance(ScheduledExecutorService scheduler) {
        scheduler.shutdownNow();
        boolean interrupted = false;
        while (!scheduler.isTerminated()) {
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @FunctionalInterface
    private interface CleanupBatch {
        boolean run() throws SQLException, IOException;
    }

    private static void cleanAndReschedule(ScheduledExecutorService scheduler, String store, CleanupBatch cleanup) {
        if (scheduler.isShutdown()) return;
        long delaySeconds = 60;
        try {
            if (cleanup.run()) delaySeconds = 2;
        } catch (SQLException | IOException error) {
            log.warn("{} cleanup will retry ({})", store, error.getClass().getSimpleName());
        }
        try {
            scheduler.schedule(() -> cleanAndReschedule(scheduler, store, cleanup), delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException stopped) {
            if (!scheduler.isShutdown()) throw stopped;
        }
    }

    private static void awaitCompletion(java.util.concurrent.CountDownLatch done) {
        boolean interrupted=false;
        while(true) { try { done.await(); break; } catch(InterruptedException error) { interrupted=true; } }
        if(interrupted) Thread.currentThread().interrupt();
    }
}
