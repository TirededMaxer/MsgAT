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

    public BlindRoutingTable() {
    }

    // Persist before delivery. A single 100 MB attachment needs more than 2,000 packets.
    public synchronized void storeOfflineMessage(String mailboxHash, GhostPacket packet) {
        try (var conn = org.msgat.console.db.DatabaseManager.getOfflineConnection();
             var existing = conn.prepareStatement("SELECT packet_data FROM offline_messages WHERE target_mailbox = ? AND (packet_hash = ? OR packet_hash IS NULL)");
             var size = conn.prepareStatement("SELECT COALESCE(SUM(length(packet_data)), 0) FROM offline_messages WHERE target_mailbox = ?");
             var insert = conn.prepareStatement("INSERT INTO offline_messages(target_mailbox, packet_data, packet_hash) VALUES (?, ?, ?)")) {
            byte[] serialized = packet.serialize();
            String hash = packetHash(packet);
            existing.setString(1, mailboxHash);
            existing.setString(2, hash);
            try (var rs = existing.executeQuery()) {
                while (rs.next()) {
                    // The ACK digest narrows candidates; only identical envelopes share storage.
                    byte[] stored = org.msgat.console.core.CryptoUtils.decryptField(rs.getBytes(1));
                    if (java.util.Arrays.equals(serialized, stored)) return;
                }
            }
            size.setString(1, mailboxHash);
            byte[] encrypted = org.msgat.console.core.CryptoUtils.encryptField(serialized);
            if (encrypted == null) throw new IllegalStateException("Packet encryption failed");
            try (var rs = size.executeQuery()) {
                if (rs.next() && rs.getLong(1) + encrypted.length > 256L * 1024 * 1024) {
                    throw new IllegalStateException("Recipient mailbox is full");
                }
            }
            insert.setString(1, mailboxHash);
            insert.setBytes(2, encrypted);
            insert.setString(3, hash);
            insert.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Unable to persist packet", e);
        }
    }

    private static String packetHash(GhostPacket packet) {
        return org.msgat.console.core.CryptoUtils.identityUuid(packet.getEncryptedData());
    }

    // Retain packets until the recipient confirms them; reconnects may replay them.
    public synchronized List<GhostPacket> retrieveOfflineMessages(String mailboxHash) {
        List<GhostPacket> result = new ArrayList<>();
        try (var conn = org.msgat.console.db.DatabaseManager.getOfflineConnection();
             var select = conn.prepareStatement("SELECT id, packet_data, packet_hash FROM offline_messages WHERE target_mailbox = ? ORDER BY id");
             var migrate = conn.prepareStatement("UPDATE offline_messages SET packet_hash = ? WHERE id = ?")) {
            select.setString(1, mailboxHash);
            try (var rs = select.executeQuery()) {
                while (rs.next()) {
                    byte[] bytes = org.msgat.console.core.CryptoUtils.decryptField(rs.getBytes("packet_data"));
                    if (bytes == null) throw new IllegalStateException("Stored packet cannot be decrypted");
                    GhostPacket packet = GhostPacket.deserialize(bytes);
                    result.add(packet);
                    if (rs.getString("packet_hash") == null) {
                        migrate.setString(1, packetHash(packet));
                        migrate.setLong(2, rs.getLong("id"));
                        migrate.addBatch();
                    }
                }
            }
            migrate.executeBatch();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Unable to retrieve packets", e);
        }
        return result;
    }

    // ACK payload is one SHA-256 digest of the encrypted payload. Never trust its target mailbox.
    public synchronized void acknowledge(String mailboxHash, byte[] digest) {
        if (digest == null || digest.length != 32) return; // Legacy empty ACKs are non-destructive.
        try (var conn = org.msgat.console.db.DatabaseManager.getOfflineConnection();
             var delete = conn.prepareStatement("DELETE FROM offline_messages WHERE target_mailbox = ? AND packet_hash = ?")) {
            delete.setString(1, mailboxHash);
            delete.setString(2, java.util.HexFormat.of().formatHex(digest));
            delete.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Unable to acknowledge packet", e);
        }
    }

    // Maps a specific mailbox ID to a connected Netty channel.
    public void register(String mailboxHashHex, Channel channel) {
        Channel previous = activeChannels.put(mailboxHashHex, channel);
        if (previous != null && previous != channel) previous.close();
    }

    // Looks up and returns the active Netty channel linked to the given mailbox ID.
    public Channel getChannel(String mailboxHashHex) {
        return activeChannels.get(mailboxHashHex);
    }

    // Unregisters and removes a specific mailbox ID mapping from the active channels registry.
    public void remove(String mailboxHashHex, Channel channel) {
        activeChannels.remove(mailboxHashHex, channel);
    }

    public void closeConnections() {
        activeChannels.values().forEach(Channel::close);
        activeChannels.clear();
    }

    // Emergency wipe is distinct from an ordinary server restart.
    public synchronized void secureWipe() {
        closeConnections();
        org.msgat.console.db.DatabaseManager.wipeOfflineDatabaseFile();
    }
}
