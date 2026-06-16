package org.msgat.console.routing;

import io.netty.channel.Channel;
import org.msgat.console.model.GhostPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class BlindRoutingTable {
    private static final Logger log = LoggerFactory.getLogger(BlindRoutingTable.class);

    private final ConcurrentHashMap<String, Channel> activeChannels = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "OfflineVault-Cleaner");
        t.setDaemon(true);
        return t;
    });

    // Constructor initializing a scheduled task to periodically clean expired messages.
    public BlindRoutingTable() {
        cleanerExecutor.scheduleAtFixedRate(this::cleanExpiredMessages, 1, 1, TimeUnit.HOURS);
    }

    // Stores an encrypted offline message into the database bounded by a max cap per mailbox.
    public void storeOfflineMessage(String mailboxHash, GhostPacket packet) {
        try (java.sql.Connection conn = org.msgat.console.db.DatabaseManager.getOfflineConnection();
             java.sql.PreparedStatement countStmt = conn.prepareStatement("SELECT COUNT(*) FROM offline_messages WHERE target_mailbox = ?");
             java.sql.PreparedStatement delStmt = conn.prepareStatement("DELETE FROM offline_messages WHERE id IN (SELECT id FROM offline_messages WHERE target_mailbox = ? ORDER BY created_at ASC LIMIT 1)");
             java.sql.PreparedStatement insertStmt = conn.prepareStatement("INSERT INTO offline_messages (target_mailbox, packet_data) VALUES (?, ?)")) {
             
            countStmt.setString(1, mailboxHash);
            java.sql.ResultSet rs = countStmt.executeQuery();
            if (rs.next() && rs.getInt(1) >= 50) {
                delStmt.setString(1, mailboxHash);
                delStmt.executeUpdate();
            }

            insertStmt.setString(1, mailboxHash);
            insertStmt.setBytes(2, org.msgat.console.core.CryptoUtils.encryptField(packet.serialize()));
            insertStmt.executeUpdate();
        } catch (java.sql.SQLException e) {
        }
    }

    // Retrieves and subsequently deletes all pending offline messages for a specific mailbox.
    public List<GhostPacket> retrieveOfflineMessages(String mailboxHash) {
        List<GhostPacket> result = new ArrayList<>();
        try (java.sql.Connection conn = org.msgat.console.db.DatabaseManager.getOfflineConnection();
             java.sql.PreparedStatement selectStmt = conn.prepareStatement("SELECT packet_data FROM offline_messages WHERE target_mailbox = ? ORDER BY created_at ASC");
             java.sql.PreparedStatement delStmt = conn.prepareStatement("DELETE FROM offline_messages WHERE target_mailbox = ?")) {
             
            selectStmt.setString(1, mailboxHash);
            java.sql.ResultSet rs = selectStmt.executeQuery();
            while (rs.next()) {
                byte[] data = rs.getBytes("packet_data");
                if (data != null) {
                    byte[] decryptedData = org.msgat.console.core.CryptoUtils.decryptField(data);
                    if (decryptedData != null) {
                        result.add(GhostPacket.deserialize(decryptedData));
                    }
                }
            }

            if (!result.isEmpty()) {
                delStmt.setString(1, mailboxHash);
                delStmt.executeUpdate();
            }
        } catch (java.sql.SQLException e) {
        }
        return result.isEmpty() ? null : result;
    }

    // Deletes messages from the database that are older than three days.
    private void cleanExpiredMessages() {
        try (java.sql.Connection conn = org.msgat.console.db.DatabaseManager.getOfflineConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM offline_messages WHERE created_at < datetime('now', '-3 days')");
        } catch (java.sql.SQLException e) {
        }
    }

    // Forcibly deletes all pending offline messages associated with a given mailbox.
    public void clearAndWipeOfflineMessages(String mailboxHash) {
        try (java.sql.Connection conn = org.msgat.console.db.DatabaseManager.getOfflineConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement("DELETE FROM offline_messages WHERE target_mailbox = ?")) {
            stmt.setString(1, mailboxHash);
            stmt.executeUpdate();
        } catch (java.sql.SQLException e) {
        }
    }

    // Maps a specific mailbox ID to a connected Netty channel.
    public void register(String mailboxHashHex, Channel channel) {
        activeChannels.put(mailboxHashHex, channel);
    }

    // Looks up and returns the active Netty channel linked to the given mailbox ID.
    public Channel getChannel(String mailboxHashHex) {
        return activeChannels.get(mailboxHashHex);
    }

    // Unregisters and removes a specific mailbox ID mapping from the active channels registry.
    public void remove(String mailboxHashHex) {
        activeChannels.remove(mailboxHashHex);
    }

    // Erases all memory state by shutting down tasks, closing connections, and dropping all offline DB records.
    public void secureWipe() {
        cleanerExecutor.shutdownNow();
        activeChannels.values().forEach(Channel::close);
        activeChannels.clear();

        // Directly delete the database file during JVM shutdown to bypass JDBC limitations
        org.msgat.console.db.DatabaseManager.wipeOfflineDatabaseFile();
    }
}