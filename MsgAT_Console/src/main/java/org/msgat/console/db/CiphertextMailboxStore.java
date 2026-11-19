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
    public record Envelope(long cursor, String sender, String eventID, byte[] ciphertext, long expiresAt) {}
    public record StoredEnvelope(long cursor, long expiresAt) {}
    public static final class QuotaExceeded extends SQLException {
        public QuotaExceeded() { super("Ciphertext queue quota exceeded"); }
    }
    public record Acknowledgement(String sender, String eventID) {}
    public record PushSubscription(String recipient, String origin, String topic, String generation,
                                   byte[] publicKey, byte[] auth, byte[] wakeKey, byte[] challenge,
                                   long expiresAt, int failures, long mailBatch) {}
    public record PushStatus(String status, long expiresAt) {}
    public record Limits(long perMailboxBytes, long globalBytes, int retentionDays) {
        public Limits {
            if (perMailboxBytes < MAX_CIPHERTEXT_BYTES + ROW_COST || globalBytes < perMailboxBytes)
                throw new IllegalArgumentException("Invalid ciphertext mailbox budgets");
            if (retentionDays < 1 || retentionDays > MAX_RETENTION_DAYS)
                throw new IllegalArgumentException("Invalid ciphertext mailbox retention");
        }
    }

    private static final String DELETE_ENVELOPE = "DELETE FROM envelopes WHERE recipient=? AND sender=? AND event_id=?";
    private static final int MAX_CIPHERTEXT_BYTES = 262_000;
    private static final int ROW_COST = 1024;
    // Public prekeys and attachment leases allow at most seven days of relay retention.
    public static final int MAX_RETENTION_DAYS = 7;
    public static final Limits DEFAULT_LIMITS = new Limits(64L * 1024 * 1024, 2L * 1024 * 1024 * 1024, 7);
    private static final String EFFECTIVE_EXPIRY = "COALESCE(expires_at,created_at+" + (7 * 86_400L) + ")";
    private static final int ADMISSION_EXPIRY_BATCH = 16;
    private static final int EXPIRED_ENVELOPE_BATCH = 128;
    private static final int EXPIRED_SUBSCRIPTION_BATCH = 256;
    private static final long LIVE_DELIVERY_GRACE_SECONDS = 5;
    private static final String PUSH_INDEXED_WORK = "(challenge IS NOT NULL OR has_mail=1)";
    private static final String PUSH_HAS_PENDING_WORK = """
            (p.challenge IS NOT NULL OR EXISTS (
                SELECT 1 FROM envelopes
                WHERE recipient=p.recipient AND %s>? AND created_at<=?
            ))
            """.formatted(EFFECTIVE_EXPIRY);
    private static final String PUSH_SUBSCRIPTION_MATCH =
            "recipient=? AND origin=? AND generation=? AND challenge IS ? AND topic=? AND public_key=? AND auth=? AND wake_key=?";
    private static Connection connection;
    private static Limits limits = DEFAULT_LIMITS;
    private CiphertextMailboxStore() {}

    public static synchronized void initialize(Limits configuredLimits) throws Exception {
        if (connection != null) return;
        java.util.Objects.requireNonNull(configuredLimits);
        Path directory = Path.of(System.getProperty("msgat.data.dir", "data"));
        Files.createDirectories(directory);
        Connection candidate = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("msgat_v2_relay.db").toAbsolutePath());
        try (Statement statement = candidate.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA cache_size=-2048");
            statement.execute("BEGIN IMMEDIATE");
            try {
                createSchema(statement);
                statement.execute("COMMIT");
            } catch (SQLException | RuntimeException error) { rollback(statement, error); throw error; }
        } catch (Exception error) { candidate.close(); throw error; }
        limits = configuredLimits;
        connection = candidate;
    }

    private static void createSchema(Statement statement) throws SQLException {
        statement.execute("""
            CREATE TABLE IF NOT EXISTS envelopes (
              id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, recipient TEXT NOT NULL,
              event_id TEXT NOT NULL, ciphertext BLOB NOT NULL, wire_hash TEXT NOT NULL,
              created_at INTEGER NOT NULL, expires_at INTEGER, UNIQUE(sender,recipient,event_id))
            """);
        boolean hasExpiry = false;
        try (var columns = statement.executeQuery("PRAGMA table_info(envelopes)")) {
            while (columns.next()) {
                if ("expires_at".equals(columns.getString("name"))) {
                    hasExpiry = true;
                    break;
                }
            }
        }
        // Legacy rows retain their original deadline without rewriting their ciphertext.
        if (!hasExpiry) statement.execute("ALTER TABLE envelopes ADD COLUMN expires_at INTEGER");
        statement.execute("CREATE INDEX IF NOT EXISTS envelopes_recipient_cursor ON envelopes(recipient,id)");
        statement.execute("CREATE INDEX IF NOT EXISTS envelopes_recipient_age ON envelopes(recipient,created_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS envelopes_sender ON envelopes(sender,id)");
        statement.execute("CREATE INDEX IF NOT EXISTS envelopes_effective_expiry ON envelopes(" + EFFECTIVE_EXPIRY + ")");
        statement.execute("CREATE INDEX IF NOT EXISTS envelopes_recipient_expiry ON envelopes(recipient," + EFFECTIVE_EXPIRY + ")");
        statement.execute("DROP INDEX IF EXISTS envelopes_expiry");
        statement.execute("CREATE TABLE IF NOT EXISTS mailbox_usage (recipient TEXT PRIMARY KEY, stored_bytes INTEGER NOT NULL)");
        statement.execute("CREATE TABLE IF NOT EXISTS total_usage (id INTEGER PRIMARY KEY CHECK(id=1), stored_bytes INTEGER NOT NULL)");
        statement.execute("CREATE TABLE IF NOT EXISTS mailbox_schema (version INTEGER PRIMARY KEY)");
        statement.execute("""
            CREATE TABLE IF NOT EXISTS android_push (
              recipient TEXT PRIMARY KEY, origin TEXT NOT NULL, topic TEXT NOT NULL, generation TEXT NOT NULL,
              public_key BLOB NOT NULL, auth BLOB NOT NULL, wake_key BLOB NOT NULL, challenge BLOB,
              expires_at INTEGER NOT NULL, next_attempt INTEGER NOT NULL, updated_at INTEGER NOT NULL,
              failures INTEGER NOT NULL DEFAULT 0, UNIQUE(origin,topic)
            )
            """);
        initializePushWorkIndex(statement);
        statement.execute("CREATE INDEX IF NOT EXISTS android_push_expiry ON android_push(expires_at)");
        boolean countersReady;
        try (var status = statement.executeQuery("""
                SELECT EXISTS(SELECT 1 FROM mailbox_schema WHERE version=1)
                  AND COALESCE((SELECT stored_bytes FROM total_usage WHERE id=1),-1)
                        =(SELECT COALESCE(SUM(stored_bytes),0) FROM mailbox_usage)
                  AND (SELECT COUNT(*) FROM sqlite_schema WHERE type='trigger' AND tbl_name='envelopes'
                       AND name IN ('envelope_insert','envelope_delete'))=2
                """)) {
            countersReady = status.next() && status.getBoolean(1);
        }
        if (!countersReady) {
            statement.execute("DROP TRIGGER IF EXISTS envelope_insert");
            statement.execute("DROP TRIGGER IF EXISTS envelope_delete");
            statement.execute("DELETE FROM mailbox_usage");
            statement.execute("INSERT INTO mailbox_usage SELECT recipient,SUM(length(ciphertext)+1024) FROM envelopes GROUP BY recipient");
            statement.execute("INSERT OR REPLACE INTO total_usage(id,stored_bytes) SELECT 1,COALESCE(SUM(stored_bytes),0) FROM mailbox_usage");
        }
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
        if (!countersReady) statement.execute("INSERT OR IGNORE INTO mailbox_schema(version) VALUES(1)");
    }

    private static void initializePushWorkIndex(Statement statement) throws SQLException {
        boolean hasMailColumn = false;
        boolean hasBatchColumn = false;
        try (var columns = statement.executeQuery("PRAGMA table_info(android_push)")) {
            while (columns.next()) {
                String column = columns.getString("name");
                if ("has_mail".equals(column)) hasMailColumn = true;
                if ("mail_batch".equals(column)) hasBatchColumn = true;
            }
        }
        if (!hasMailColumn) {
            statement.execute("ALTER TABLE android_push ADD COLUMN has_mail INTEGER NOT NULL DEFAULT 0 CHECK(has_mail IN (0,1))");
            statement.execute("""
                    UPDATE android_push SET has_mail=1
                    WHERE EXISTS (SELECT 1 FROM envelopes WHERE recipient=android_push.recipient)
                    """);
        }
        if (!hasBatchColumn) {
            statement.execute("ALTER TABLE android_push ADD COLUMN mail_batch INTEGER NOT NULL DEFAULT 0");
            statement.execute("DROP TRIGGER IF EXISTS push_work_on_envelope_insert");
        }
        // These run in the same SQLite transaction as admission, acknowledgements and expiry.
        statement.execute("""
                CREATE TRIGGER IF NOT EXISTS push_work_on_envelope_insert AFTER INSERT ON envelopes BEGIN
                  UPDATE android_push SET has_mail=1, mail_batch=mail_batch+1,
                    next_attempt=CASE WHEN challenge IS NULL AND failures=0 THEN MIN(next_attempt,NEW.created_at)
                                      ELSE next_attempt END
                  WHERE recipient=NEW.recipient AND (has_mail=0 OR NOT EXISTS (
                    SELECT 1 FROM envelopes WHERE recipient=NEW.recipient AND id<>NEW.id AND %s>NEW.created_at
                  ));
                END
                """.formatted(EFFECTIVE_EXPIRY));
        statement.execute("""
                CREATE TRIGGER IF NOT EXISTS push_work_on_envelope_delete AFTER DELETE ON envelopes BEGIN
                  UPDATE android_push SET has_mail=0 WHERE recipient=OLD.recipient AND has_mail=1
                    AND NOT EXISTS (SELECT 1 FROM envelopes WHERE recipient=OLD.recipient);
                END
                """);
        statement.execute("""
                CREATE TRIGGER IF NOT EXISTS push_work_on_subscription_insert AFTER INSERT ON android_push BEGIN
                  UPDATE android_push SET has_mail=1 WHERE recipient=NEW.recipient
                    AND EXISTS (SELECT 1 FROM envelopes WHERE recipient=NEW.recipient);
                END
                """);
        statement.execute("CREATE INDEX IF NOT EXISTS android_push_work_due ON android_push(origin,next_attempt,recipient) WHERE " + PUSH_INDEXED_WORK);
        statement.execute("DROP INDEX IF EXISTS android_push_due");
    }

    public static StoredEnvelope put(String sender, String recipient, String eventID, byte[] ciphertext) throws SQLException {
        if (ciphertext == null || ciphertext.length == 0 || ciphertext.length > MAX_CIPHERTEXT_BYTES)
            throw new IllegalArgumentException("Invalid ciphertext size");
        return putHashed(sender, recipient, eventID, ciphertext, hash(ciphertext));
    }

    private static synchronized StoredEnvelope putHashed(String sender, String recipient, String eventID,
                                                         byte[] ciphertext, String hash) throws SQLException {
        Connection database = required();
        try (Statement transaction = database.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            StoredEnvelope stored;
            try {
                long cursor = 0;
                long now = Instant.now().getEpochSecond();
                long newExpiry = now + limits.retentionDays() * 86_400L;
                long expiresAt = newExpiry;
                try (var query = database.prepareStatement("""
                        SELECT id,wire_hash,%s FROM envelopes WHERE sender=? AND recipient=? AND event_id=?
                        """.formatted(EFFECTIVE_EXPIRY))) {
                    query.setString(1, sender); query.setString(2, recipient); query.setString(3, eventID);
                    try (var result = query.executeQuery()) {
                        if (result.next()) {
                            if (!result.getString(2).equals(hash)) throw new IllegalArgumentException("Conflicting ciphertext identifier");
                            cursor = result.getLong(1);
                            expiresAt = result.getLong(3);
                        }
                    }
                }
                if (cursor != 0 && expiresAt <= now) {
                    // An expired row is no longer replayable. Re-admit the exact retry with a fresh
                    // cursor so a drain that has passed the old row can still pick up the retry.
                    try (var delete = database.prepareStatement("DELETE FROM envelopes WHERE id=?")) {
                        delete.setLong(1, cursor);
                        delete.executeUpdate();
                    }
                    cursor = 0;
                    expiresAt = newExpiry;
                }
                if (cursor == 0 && makeRoom(database, recipient, ciphertext.length + ROW_COST, now)) {
                    try (var insert = database.prepareStatement("""
                            INSERT INTO envelopes(sender,recipient,event_id,ciphertext,wire_hash,created_at,expires_at)
                            VALUES(?,?,?,?,?,?,?)
                            """)) {
                        insert.setString(1, sender); insert.setString(2, recipient); insert.setString(3, eventID);
                        insert.setBytes(4, ciphertext); insert.setString(5, hash); insert.setLong(6, now);
                        insert.setLong(7, expiresAt);
                        insert.executeUpdate();
                    }
                    try (var inserted = transaction.executeQuery("SELECT last_insert_rowid()")) {
                        if (!inserted.next()) throw new SQLException("Ciphertext cursor is unavailable");
                        cursor = inserted.getLong(1);
                    }
                }
                stored = cursor == 0 ? null : new StoredEnvelope(cursor, expiresAt);
                transaction.execute("COMMIT");
            } catch (SQLException | RuntimeException error) { rollback(transaction, error); throw error; }
            // Keep expired rows reclaimed even when this packet still cannot fit.
            if (stored == null) throw new QuotaExceeded();
            return stored;
        }
    }

    private static boolean makeRoom(Connection database, String recipient, long cost, long now) throws SQLException {
        try (var usage = database.prepareStatement("""
                SELECT COALESCE((SELECT stored_bytes FROM mailbox_usage WHERE recipient=?),0),
                       COALESCE((SELECT stored_bytes FROM total_usage WHERE id=1),?)
                """)) {
            usage.setString(1, recipient);
            usage.setLong(2, limits.globalBytes());
            var space = quotaState(usage, cost);
            if (!space.mailboxFull() && !space.globalFull()) return true;
            // Keep the admission transaction bounded independently of the scheduled cleanup.
            int remaining = ADMISSION_EXPIRY_BATCH;
            if (space.mailboxFull()) {
                remaining -= deleteExpiredEnvelopes(database, recipient, now, remaining);
                space = quotaState(usage, cost);
            }
            if (!space.mailboxFull() && space.globalFull() && remaining > 0) {
                deleteExpiredEnvelopes(database, null, now, remaining);
                space = quotaState(usage, cost);
            }
            return !space.mailboxFull() && !space.globalFull();
        }
    }

    private record QuotaState(boolean mailboxFull, boolean globalFull) {}

    private static QuotaState quotaState(PreparedStatement usage, long cost) throws SQLException {
        try (var row = usage.executeQuery()) {
            if (!row.next()) throw new SQLException("Ciphertext queue usage is unavailable");
            return new QuotaState(row.getLong(1) > limits.perMailboxBytes() - cost,
                    row.getLong(2) > limits.globalBytes() - cost);
        }
    }

    private static int deleteExpiredEnvelopes(Connection database, String recipient, long cutoff, int limit)
            throws SQLException {
        String scope = recipient == null ? "" : " AND recipient=?";
        try (var prune = database.prepareStatement("""
                DELETE FROM envelopes WHERE id IN (
                    SELECT id FROM envelopes WHERE %s<=?%s ORDER BY %s,id LIMIT ?
                )
                """.formatted(EFFECTIVE_EXPIRY, scope, EFFECTIVE_EXPIRY))) {
            prune.setLong(1, cutoff);
            if (recipient != null) prune.setString(2, recipient);
            prune.setInt(recipient == null ? 2 : 3, limit);
            return prune.executeUpdate();
        }
    }

    public static synchronized long lastCursor(String recipient) throws SQLException {
        try (var query = required().prepareStatement("""
                SELECT id FROM envelopes WHERE recipient=? ORDER BY id DESC LIMIT 1
                """)) {
            query.setString(1, recipient);
            try (var result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    public static synchronized List<Envelope> batch(String recipient, long after, long through) throws SQLException {
        List<Envelope> rows = new ArrayList<>(8);
        try (var query = required().prepareStatement("""
                SELECT id,sender,event_id,ciphertext,%s FROM envelopes
                WHERE recipient=? AND id>? AND id<=? AND %s>? ORDER BY id LIMIT 8
                """.formatted(EFFECTIVE_EXPIRY, EFFECTIVE_EXPIRY))) {
            query.setString(1, recipient); query.setLong(2, after);
            query.setLong(3, through);
            query.setLong(4, Instant.now().getEpochSecond());
            try (var result = query.executeQuery()) {
                while (result.next()) rows.add(new Envelope(result.getLong(1), result.getString(2), result.getString(3),
                        result.getBytes(4), result.getLong(5)));
            }
        }
        return rows;
    }

    public static synchronized void acknowledge(String recipient, String sender, String eventID) throws SQLException {
        try (var delete = required().prepareStatement(DELETE_ENVELOPE)) {
            delete.setString(1, recipient); delete.setString(2, sender); delete.setString(3, eventID); delete.executeUpdate();
        }
    }

    public static synchronized void acknowledgeBatch(String recipient, java.util.Collection<Acknowledgement> acknowledgements) throws SQLException {
        if (acknowledgements.isEmpty()) return;
        if (acknowledgements.size() == 1) {
            Acknowledgement only = acknowledgements.iterator().next();
            acknowledge(recipient, only.sender(), only.eventID());
            return;
        }
        Connection database = required();
        try (var transaction = database.createStatement(); var delete = database.prepareStatement(DELETE_ENVELOPE)) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                delete.setString(1, recipient);
                for (Acknowledgement acknowledgement : acknowledgements) {
                    delete.setString(2, acknowledgement.sender());
                    delete.setString(3, acknowledgement.eventID());
                    delete.executeUpdate();
                }
                transaction.execute("COMMIT");
            } catch (SQLException | RuntimeException error) { rollback(transaction, error); throw error; }
        }
    }

    public static synchronized boolean cleanupExpired() throws SQLException {
        long now = Instant.now().getEpochSecond();
        int envelopes = deleteExpiredEnvelopes(required(), null, now, EXPIRED_ENVELOPE_BATCH);
        int subscriptions;
        try (var delete = required().prepareStatement("""
                DELETE FROM android_push WHERE recipient IN (
                    SELECT recipient FROM android_push WHERE expires_at<=? ORDER BY expires_at LIMIT ?
                )
                """)) {
            delete.setLong(1, now);
            delete.setInt(2, EXPIRED_SUBSCRIPTION_BATCH);
            subscriptions = delete.executeUpdate();
        }
        return envelopes == EXPIRED_ENVELOPE_BATCH || subscriptions == EXPIRED_SUBSCRIPTION_BATCH;
    }

    public static synchronized PushStatus registerPush(PushSubscription incoming, long now, long activeUntil) throws SQLException {
        Connection database = required();
        try (var query = database.prepareStatement("SELECT * FROM android_push WHERE recipient=?")) {
            query.setString(1, incoming.recipient());
            try (var row = query.executeQuery()) {
                if (row.next() && row.getLong("expires_at") > now) {
                    var current = pushSubscription(row);
                    if (current.generation().equals(incoming.generation()) && current.origin().equals(incoming.origin())) {
                        if (!current.topic().equals(incoming.topic()) ||
                                !MessageDigest.isEqual(current.publicKey(), incoming.publicKey()) ||
                                !MessageDigest.isEqual(current.auth(), incoming.auth()) ||
                                !MessageDigest.isEqual(current.wakeKey(), incoming.wakeKey())) return new PushStatus("invalid", 0);
                        if (current.challenge() != null) return new PushStatus("pending", current.expiresAt());
                    } else if (row.getLong("updated_at") > now - 30) return new PushStatus("retry", 0);
                }
            }
        }
        try (var renew = database.prepareStatement("""
                UPDATE android_push SET expires_at=?
                WHERE recipient=? AND origin=? AND generation=? AND expires_at>? AND challenge IS NULL
                """)) {
            renew.setLong(1, activeUntil);
            renew.setString(2, incoming.recipient());
            renew.setString(3, incoming.origin());
            renew.setString(4, incoming.generation());
            renew.setLong(5, now);
            if (renew.executeUpdate() != 0) return new PushStatus("active", activeUntil);
        }
        try (var acquire = database.prepareStatement("""
                INSERT INTO android_push(recipient,origin,topic,generation,public_key,auth,wake_key,challenge,expires_at,next_attempt,updated_at)
                SELECT ?,?,?,?,?,?,?,?,?,?,?
                WHERE (EXISTS (SELECT 1 FROM android_push WHERE recipient=?) OR (SELECT COUNT(*) FROM android_push)<8192)
                  AND NOT EXISTS (SELECT 1 FROM android_push WHERE origin=? AND topic=? AND recipient<>?)
                ON CONFLICT(recipient) DO UPDATE SET origin=excluded.origin, topic=excluded.topic,
                  generation=excluded.generation, public_key=excluded.public_key, auth=excluded.auth,
                  wake_key=excluded.wake_key, challenge=excluded.challenge, expires_at=excluded.expires_at,
                  next_attempt=excluded.next_attempt, updated_at=excluded.updated_at, failures=0
                """)) {
            acquire.setString(1, incoming.recipient());
            acquire.setString(2, incoming.origin());
            acquire.setString(3, incoming.topic());
            acquire.setString(4, incoming.generation());
            acquire.setBytes(5, incoming.publicKey());
            acquire.setBytes(6, incoming.auth());
            acquire.setBytes(7, incoming.wakeKey());
            acquire.setBytes(8, incoming.challenge());
            acquire.setLong(9, incoming.expiresAt());
            acquire.setLong(10, now);
            acquire.setLong(11, now);
            acquire.setString(12, incoming.recipient());
            acquire.setString(13, incoming.origin());
            acquire.setString(14, incoming.topic());
            acquire.setString(15, incoming.recipient());
            return acquire.executeUpdate() == 1 ? new PushStatus("pending", incoming.expiresAt()) : new PushStatus("retry", 0);
        }
    }

    public static synchronized PushStatus confirmPush(String recipient, String origin, String generation, byte[] challenge,
                                                       long now, long activeUntil) throws SQLException {
        try (var update = required().prepareStatement("""
                UPDATE android_push SET challenge=NULL,expires_at=?,next_attempt=?,failures=0
                WHERE recipient=? AND origin=? AND generation=? AND expires_at>? AND (challenge=? OR challenge IS NULL)
                """)) {
            update.setLong(1, activeUntil);
            update.setLong(2, now);
            update.setString(3, recipient);
            update.setString(4, origin);
            update.setString(5, generation);
            update.setLong(6, now);
            update.setBytes(7, challenge);
            return update.executeUpdate() == 1 ? new PushStatus("active", activeUntil) : new PushStatus("gone", 0);
        }
    }

    public static synchronized void removePush(String recipient, String origin, String generation) throws SQLException {
        try (var delete = required().prepareStatement("DELETE FROM android_push WHERE recipient=? AND origin=? AND generation=?")) {
            delete.setString(1, recipient); delete.setString(2, origin); delete.setString(3, generation);
            delete.executeUpdate();
        }
    }

    public static synchronized List<PushSubscription> duePush(String origin, long now) throws SQLException {
        List<PushSubscription> result = new ArrayList<>(16);
        try (var query = required().prepareStatement("""
                SELECT p.* FROM android_push p WHERE p.origin=? AND p.next_attempt<=? AND p.expires_at>?
                  AND %s AND %s
                ORDER BY p.next_attempt,p.recipient LIMIT 16
                """.formatted(PUSH_INDEXED_WORK, PUSH_HAS_PENDING_WORK))) {
            query.setString(1, origin);
            query.setLong(2, now);
            query.setLong(3, now);
            query.setLong(4, now);
            query.setLong(5, now - LIVE_DELIVERY_GRACE_SECONDS);
            try (var rows = query.executeQuery()) {
                while (rows.next()) result.add(pushSubscription(rows));
            }
        }
        return result;
    }

    public static synchronized boolean isPushStillDue(PushSubscription scheduled, long now) throws SQLException {
        try (var query = required().prepareStatement("""
                SELECT 1 FROM android_push p
                WHERE %s
                  AND (p.challenge IS NOT NULL OR p.mail_batch=?)
                  AND p.next_attempt<=? AND p.expires_at>? AND %s
                """.formatted(PUSH_SUBSCRIPTION_MATCH, PUSH_HAS_PENDING_WORK))) {
            int next = bindPushSubscription(query, 1, scheduled);
            query.setLong(next++, scheduled.mailBatch());
            query.setLong(next++, now);
            query.setLong(next++, now);
            query.setLong(next++, now);
            query.setLong(next, now - LIVE_DELIVERY_GRACE_SECONDS);
            try (var row = query.executeQuery()) { return row.next(); }
        }
    }

    private static PushSubscription pushSubscription(ResultSet row) throws SQLException {
        return new PushSubscription(row.getString("recipient"), row.getString("origin"), row.getString("topic"),
                row.getString("generation"), row.getBytes("public_key"), row.getBytes("auth"), row.getBytes("wake_key"),
                row.getBytes("challenge"), row.getLong("expires_at"), row.getInt("failures"), row.getLong("mail_batch"));
    }

    public static synchronized void finishPush(PushSubscription sent, long nextAttempt, int failures, boolean gone) throws SQLException {
        // A successful wake for an older batch cannot delay the next empty-to-nonempty transition.
        // Transport failures still back off the same subscription, even if its mail changed meanwhile.
        boolean successfulWake = !gone && failures == 0 && sent.challenge() == null;
        try (var write = required().prepareStatement((gone
                ? "DELETE FROM android_push"
                : "UPDATE android_push SET next_attempt=?,failures=?") + " WHERE " + PUSH_SUBSCRIPTION_MATCH +
                (successfulWake ? " AND mail_batch=?" : ""))) {
            int i = 1;
            if (!gone) {
                write.setLong(i++, nextAttempt);
                write.setInt(i++, failures);
            }
            i = bindPushSubscription(write, i, sent);
            if (successfulWake) write.setLong(i, sent.mailBatch());
            write.executeUpdate();
        }
    }

    private static int bindPushSubscription(PreparedStatement statement, int next, PushSubscription subscription) throws SQLException {
        statement.setString(next++, subscription.recipient());
        statement.setString(next++, subscription.origin());
        statement.setString(next++, subscription.generation());
        statement.setBytes(next++, subscription.challenge());
        statement.setString(next++, subscription.topic());
        statement.setBytes(next++, subscription.publicKey());
        statement.setBytes(next++, subscription.auth());
        statement.setBytes(next++, subscription.wakeKey());
        return next;
    }

    public static synchronized boolean eraseIdentity(String uuid) throws SQLException {
        try (var delete = required().prepareStatement("DELETE FROM android_push WHERE recipient=?")) {
            delete.setString(1, uuid); delete.executeUpdate();
        }
        try(var remove=required().prepareStatement("DELETE FROM envelopes WHERE id IN (SELECT id FROM envelopes WHERE sender=? OR recipient=? LIMIT 1000)")) {
            remove.setString(1,uuid); remove.setString(2,uuid); remove.executeUpdate();
        }
        try(var query=required().prepareStatement("SELECT 1 FROM envelopes WHERE sender=? OR recipient=? LIMIT 1")) {
            query.setString(1,uuid); query.setString(2,uuid); try(var row=query.executeQuery()) { return !row.next(); }
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
