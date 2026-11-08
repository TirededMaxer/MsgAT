// SPDX-License-Identifier: MIT
package org.msgat.console.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Durable V2 queue: ciphertext and routing metadata only, with no decryption key. */
public final class CiphertextMailboxStore {
    public record Envelope(long cursor, String sender, String recipient, String eventID, byte[] ciphertext) {}
    private static final long MAILBOX_LIMIT = 64L * 1024 * 1024;
    private static final long GLOBAL_LIMIT = 2L * 1024 * 1024 * 1024;
    private static final int ROW_COST = 1024;
    private static Connection connection;
    private CiphertextMailboxStore() {}

    public static synchronized void initialize() throws Exception {
        if (connection != null) return;
        Path directory = Path.of(System.getProperty("msgat.data.dir", "data"));
        Files.createDirectories(directory);
        Connection candidate = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("msgat_v2_relay.db").toAbsolutePath());
        try (Statement statement = candidate.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA cache_size=-2048");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS envelopes (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, recipient TEXT NOT NULL,
                  event_id TEXT NOT NULL, ciphertext BLOB NOT NULL, wire_hash TEXT NOT NULL,
                  created_at INTEGER NOT NULL, UNIQUE(sender,recipient,event_id))
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS envelopes_recipient_cursor ON envelopes(recipient,id)");
            statement.execute("CREATE INDEX IF NOT EXISTS envelopes_expiry ON envelopes(created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS mailbox_usage (recipient TEXT PRIMARY KEY, stored_bytes INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS total_usage (id INTEGER PRIMARY KEY CHECK(id=1), stored_bytes INTEGER NOT NULL)");
            statement.execute("INSERT INTO total_usage(id,stored_bytes) VALUES(1,0) ON CONFLICT(id) DO NOTHING");
            statement.execute("DROP TRIGGER IF EXISTS envelope_insert");
            statement.execute("DROP TRIGGER IF EXISTS envelope_delete");
            statement.execute("DELETE FROM mailbox_usage");
            statement.execute("INSERT INTO mailbox_usage SELECT recipient,SUM(length(ciphertext)+1024) FROM envelopes GROUP BY recipient");
            statement.execute("UPDATE total_usage SET stored_bytes=(SELECT COALESCE(SUM(length(ciphertext)+1024),0) FROM envelopes) WHERE id=1");
            statement.execute("""
                CREATE TRIGGER IF NOT EXISTS envelope_insert AFTER INSERT ON envelopes BEGIN
                  INSERT INTO mailbox_usage VALUES(NEW.recipient,length(NEW.ciphertext)+1024)
                    ON CONFLICT(recipient) DO UPDATE SET stored_bytes=stored_bytes+length(NEW.ciphertext)+1024;
                  UPDATE total_usage SET stored_bytes=stored_bytes+length(NEW.ciphertext)+1024 WHERE id=1;
                END
                """);
            statement.execute("""
                CREATE TRIGGER IF NOT EXISTS envelope_delete AFTER DELETE ON envelopes BEGIN
                  UPDATE mailbox_usage SET stored_bytes=stored_bytes-length(OLD.ciphertext)-1024 WHERE recipient=OLD.recipient;
                  DELETE FROM mailbox_usage WHERE recipient=OLD.recipient AND stored_bytes<=0;
                  UPDATE total_usage SET stored_bytes=stored_bytes-length(OLD.ciphertext)-1024 WHERE id=1;
                END
                """);
            connection = candidate;
        } catch (Exception error) { candidate.close(); throw error; }
    }

    public static synchronized void put(String sender, String recipient, String eventID, byte[] ciphertext) throws SQLException {
        if (ciphertext == null || ciphertext.length == 0 || ciphertext.length > 262000) throw new IllegalArgumentException("Invalid ciphertext size");
        Connection database = required();
        String hash = hash(ciphertext);
        try (Statement transaction = database.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                boolean duplicate = false;
                try (var query = database.prepareStatement("SELECT wire_hash FROM envelopes WHERE sender=? AND recipient=? AND event_id=?")) {
                    query.setString(1, sender); query.setString(2, recipient); query.setString(3, eventID);
                    try (var result = query.executeQuery()) {
                        if (result.next()) {
                            if (!result.getString(1).equals(hash)) throw new IllegalArgumentException("Conflicting ciphertext identifier");
                            duplicate = true;
                        }
                    }
                }
                if (!duplicate) {
                    long mailboxBytes = 0;
                    try (var query = database.prepareStatement("SELECT stored_bytes FROM mailbox_usage WHERE recipient=?")) {
                        query.setString(1, recipient);
                        try (var result = query.executeQuery()) { if (result.next()) mailboxBytes = result.getLong(1); }
                    }
                    long totalBytes;
                    try (var query = transaction.executeQuery("SELECT stored_bytes FROM total_usage WHERE id=1")) { totalBytes = query.next() ? query.getLong(1) : GLOBAL_LIMIT; }
                    if (mailboxBytes + ciphertext.length + ROW_COST > MAILBOX_LIMIT || totalBytes + ciphertext.length + ROW_COST > GLOBAL_LIMIT)
                        throw new IllegalArgumentException("Ciphertext queue quota exceeded");
                    try (var insert = database.prepareStatement("INSERT INTO envelopes(sender,recipient,event_id,ciphertext,wire_hash,created_at) VALUES(?,?,?,?,?,?)")) {
                        insert.setString(1, sender); insert.setString(2, recipient); insert.setString(3, eventID);
                        insert.setBytes(4, ciphertext); insert.setString(5, hash); insert.setLong(6, Instant.now().getEpochSecond()); insert.executeUpdate();
                    }
                }
                transaction.execute("COMMIT");
            } catch (SQLException | RuntimeException error) { rollback(transaction, error); throw error; }
        }
    }

    public static synchronized List<Envelope> batch(String recipient, long after) throws SQLException {
        List<Envelope> rows = new ArrayList<>(8);
        try (var query = required().prepareStatement("SELECT id,sender,event_id,ciphertext FROM envelopes WHERE recipient=? AND id>? ORDER BY id LIMIT 8")) {
            query.setString(1, recipient); query.setLong(2, after);
            try (var result = query.executeQuery()) {
                while (result.next()) rows.add(new Envelope(result.getLong(1), result.getString(2), recipient, result.getString(3), result.getBytes(4)));
            }
        }
        return rows;
    }

    public static synchronized void acknowledge(String recipient, String sender, String eventID) throws SQLException {
        try (var delete = required().prepareStatement("DELETE FROM envelopes WHERE recipient=? AND sender=? AND event_id=?")) {
            delete.setString(1, recipient); delete.setString(2, sender); delete.setString(3, eventID); delete.executeUpdate();
        }
    }

    public static synchronized void cleanupExpired() throws SQLException {
        // Bounded work per scheduled tick; traffic cannot hold the cleanup lock indefinitely.
        try (var delete = required().prepareStatement("DELETE FROM envelopes WHERE id IN (SELECT id FROM envelopes WHERE created_at<? ORDER BY created_at LIMIT 1000)")) {
            delete.setLong(1, Instant.now().getEpochSecond() - 7 * 86400); delete.executeUpdate();
        }
    }

    public static synchronized void close() {
        if (connection == null) return;
        try { connection.close(); } catch (SQLException ignored) { /* JVM is shutting down. */ }
        finally { connection = null; }
    }

    private static Connection required() throws SQLException {
        if (connection == null) throw new SQLException("Ciphertext store is not initialized");
        return connection;
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static void rollback(Statement transaction, Exception original) {
        try { transaction.execute("ROLLBACK"); } catch (SQLException error) { original.addSuppressed(error); }
    }
}
