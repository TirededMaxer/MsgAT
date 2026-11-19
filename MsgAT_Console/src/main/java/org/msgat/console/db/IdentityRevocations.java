// SPDX-License-Identifier: MIT
package org.msgat.console.db;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.msgat.console.core.IdentityAddresses;

/** Permanent public revocations. The gate orders revocation against active authenticated work. */
public final class IdentityRevocations {
    private static final ReentrantReadWriteLock GATE=new ReentrantReadWriteLock(true);
    private static final Object SQL=new Object();
    private static final Pattern IDENTITY_UUID = Pattern.compile("[a-f0-9]{64}");
    private static Connection connection;
    private static PreparedStatement revocationLookup;
    private record CleanupJob(String uuid, int failures) { }
    public record RecordedRevocation(String mailbox, boolean changed) { }
    private IdentityRevocations() { }
    public static void initialize() throws Exception {
        synchronized(SQL) {
            if(connection!=null) return;
            var dir=Path.of(System.getProperty("msgat.data.dir","data")); Files.createDirectories(dir);
            var candidate=DriverManager.getConnection("jdbc:sqlite:"+dir.resolve("msgat_v2_revocations.db").toAbsolutePath());
            PreparedStatement lookup;
            try(var statement=candidate.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000"); statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL"); statement.execute("PRAGMA cache_size=-1024");
                statement.execute("PRAGMA max_page_count=65536");
                statement.execute("CREATE TABLE IF NOT EXISTS revoked_identities (uuid TEXT PRIMARY KEY, mailbox TEXT NOT NULL, cleanup_pending INTEGER NOT NULL DEFAULT 1 CHECK(cleanup_pending IN (0,1)))");
                var columns = new HashSet<String>();
                try (var rows = statement.executeQuery("PRAGMA table_info(revoked_identities)")) {
                    while (rows.next()) columns.add(rows.getString("name"));
                }
                if (!columns.contains("proof"))
                    statement.execute("ALTER TABLE revoked_identities ADD COLUMN proof BLOB");
                if (!columns.contains("cleanup_due_at"))
                    statement.execute("ALTER TABLE revoked_identities ADD COLUMN cleanup_due_at INTEGER NOT NULL DEFAULT 0");
                if (!columns.contains("cleanup_failures"))
                    statement.execute("ALTER TABLE revoked_identities ADD COLUMN cleanup_failures INTEGER NOT NULL DEFAULT 0");
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS revoked_cleanup_due ON revoked_identities(cleanup_due_at,uuid)
                    WHERE cleanup_pending=1
                    """);
                statement.execute("DROP INDEX IF EXISTS revoked_cleanup");
                lookup = candidate.prepareStatement("SELECT 1 FROM revoked_identities WHERE uuid=?");
            } catch(Exception error) { candidate.close(); throw error; }
            revocationLookup = lookup;
            connection = candidate;
        }
    }
    private static Connection required() throws SQLException {
        if(connection==null) throw new SQLException("Revocation store unavailable"); return connection;
    }
    public static boolean revoked(String uuid) throws SQLException {
        synchronized(SQL) {
            required();
            revocationLookup.setString(1, uuid);
            try (var row = revocationLookup.executeQuery()) { return row.next(); }
        }
    }
    public static byte[] proof(String uuid) throws SQLException {
        synchronized(SQL) {
            try(var query=required().prepareStatement("SELECT proof FROM revoked_identities WHERE uuid=?")) {
                query.setString(1,uuid); try(var row=query.executeQuery()) {
                    if(!row.next()) return null;
                    byte[] proof=row.getBytes(1); return proof!=null && proof.length==113 ? proof : null;
                }
            }
        }
    }
    public static final class Active implements AutoCloseable {
        private boolean closed;
        private Active() { }
        @Override public void close() { if(!closed) { closed=true; GATE.readLock().unlock(); } }
    }
    public static Active active(String... identities) throws SQLException {
        GATE.readLock().lock();
        try {
            for(var identity:identities) if(identity==null || !IDENTITY_UUID.matcher(identity).matches() || revoked(identity))
                throw new IllegalStateException("Identity is unavailable");
            return new Active();
        } catch(SQLException | RuntimeException error) { GATE.readLock().unlock(); throw error; }
    }
    /** Returns the revoked mailbox only after the tombstone has committed durably. */
    public static RecordedRevocation record(byte[] proof) throws SQLException {
        if(proof==null || proof.length!=113 || proof[0]!=2) throw new IllegalArgumentException("Invalid revocation");
        var root=Arrays.copyOfRange(proof,1,33);
        var domain="MsgAT/v2/revoke-identity\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var verifier=new Ed25519Signer(); verifier.init(false,new Ed25519PublicKeyParameters(root,0));
        verifier.update(domain,0,domain.length); verifier.update(proof,0,49);
        if(!verifier.verifySignature(Arrays.copyOfRange(proof,49,113))) throw new IllegalArgumentException("Invalid revocation");
        var uuid=IdentityAddresses.identityUuid(root); var mailbox=IdentityAddresses.calculateMailboxId(uuid);
        GATE.writeLock().lock();
        try { synchronized(SQL) {
            try(var insert=required().prepareStatement("""
                INSERT INTO revoked_identities(uuid,mailbox,proof,cleanup_due_at) VALUES(?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET proof=excluded.proof WHERE revoked_identities.proof IS NULL
                """)) {
                insert.setString(1,uuid); insert.setString(2,mailbox); insert.setBytes(3,proof);
                insert.setLong(4, System.currentTimeMillis());
                return new RecordedRevocation(mailbox, insert.executeUpdate() != 0);
            }
        } } finally { GATE.writeLock().unlock(); }
    }

    public static OptionalLong nextCleanupDueAt() throws SQLException {
        synchronized (SQL) {
            try (var statement = required().createStatement();
                 var row = statement.executeQuery("""
                     SELECT cleanup_due_at FROM revoked_identities
                     WHERE cleanup_pending=1 ORDER BY cleanup_due_at,uuid LIMIT 1
                     """)) {
                return row.next() ? OptionalLong.of(row.getLong(1)) : OptionalLong.empty();
            }
        }
    }
    /** Resume bounded cleanup after a reset, process crash or failed disk operation. */
    public static void cleanup(AttachmentStore objects) throws SQLException, IOException {
        var pending = new ArrayList<CleanupJob>(8);
        synchronized(SQL) {
            try (var query = required().prepareStatement("""
                SELECT uuid,cleanup_failures FROM revoked_identities
                WHERE cleanup_pending=1 AND cleanup_due_at<=?
                ORDER BY cleanup_due_at,uuid LIMIT 8
                """)) {
                query.setLong(1, System.currentTimeMillis());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) pending.add(new CleanupJob(rows.getString(1), rows.getInt(2)));
                }
            }
        }
        Exception firstFailure = null;
        for (var job : pending) {
            if (Thread.currentThread().isInterrupted()) break;
            boolean complete = false;
            int failures = 0;
            long due = System.currentTimeMillis();
            try {
                boolean directory = SignalKeyDirectory.eraseIdentity(job.uuid());
                boolean mailbox = CiphertextMailboxStore.eraseIdentity(job.uuid());
                boolean attachments = objects.eraseIdentity(job.uuid());
                complete = directory && mailbox && attachments;
            } catch (SQLException | IOException error) {
                if (firstFailure == null) firstFailure = error;
                failures = Math.min(16, job.failures() + 1);
                due = System.currentTimeMillis() + Math.min(300_000L, 5_000L << Math.min(failures - 1, 6));
            }
            synchronized (SQL) {
                try (var update = required().prepareStatement("""
                    UPDATE revoked_identities SET cleanup_pending=?,cleanup_due_at=?,cleanup_failures=?
                    WHERE uuid=? AND cleanup_pending=1
                    """)) {
                    update.setInt(1, complete ? 0 : 1);
                    update.setLong(2, complete ? 0 : due);
                    update.setInt(3, failures);
                    update.setString(4, job.uuid());
                    update.executeUpdate();
                }
            }
        }
        if (firstFailure instanceof SQLException error) throw error;
        if (firstFailure instanceof IOException error) throw error;
    }
    public static void close() { synchronized(SQL) {
        if(connection!=null) {
            try { connection.close(); }
            catch(SQLException ignored) { }
            finally { connection=null;revocationLookup=null; }
        }
    } }
}
