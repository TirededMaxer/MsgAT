// SPDX-License-Identifier: MIT
package org.msgat.console.db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Resumable ciphertext objects. Neither filenames nor content keys enter this store. */
public final class AttachmentStore implements AutoCloseable {
    public static final int CHUNK_BYTES = 65_536 + 16;
    private static final int ROW_COST = 4096;
    private static final int EXPIRY_BATCH = 16;
    private static final long PARTIAL_RETENTION_SECONDS = 86_400;
    private static final Pattern HEX_IDENTIFIER = Pattern.compile("[a-f0-9]{64}");
    public record Limits(long maximumPlaintextBytes, long ownerBytes, long totalBytes, int retentionDays) { }
    public record Status(long nextChunk, boolean complete, long expiresAt) { }
    public static final class DownloadUnavailable extends IOException {
        private DownloadUnavailable() { super("Encrypted object unavailable"); }
    }
    private record Row(String owner, String hash, String capability, long size, long next, boolean complete, long expiry) {
        long chunks() { return size / CHUNK_BYTES; }
        Status status() { return new Status(next, complete, expiry); }
    }
    @FunctionalInterface private interface Query<T> { T run(Connection connection) throws SQLException; }
    private final Connection connection;
    private final PreparedStatement objectLookup;
    private final PreparedStatement advanceUpload;
    private final Path directory;
    private final Limits limits;
    private final Object[] locks = new Object[64];

    public AttachmentStore(Path dataDirectory, Limits limits) throws IOException, SQLException {
        if (limits.maximumPlaintextBytes() < 1 || limits.maximumPlaintextBytes() > 2L*1024*1024*1024
                || limits.ownerBytes() < ((limits.maximumPlaintextBytes()+65535)/65536)*CHUNK_BYTES+ROW_COST
                || limits.totalBytes() < limits.ownerBytes() || limits.retentionDays() < 8 || limits.retentionDays() > 30)
            throw new IllegalArgumentException("Invalid encrypted object budgets");
        this.limits = limits;
        this.directory = dataDirectory.resolve("objects").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Object directory must not be a symbolic link");
        forceDirectory(directory.getParent());
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        connection = DriverManager.getConnection("jdbc:sqlite:" + dataDirectory.resolve("msgat_v2_objects.db").toAbsolutePath());
        try (Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA busy_timeout=5000");
            sql.execute("PRAGMA journal_mode=WAL");
            sql.execute("PRAGMA synchronous=FULL");
            sql.execute("PRAGMA cache_size=-1024");
            sql.execute("BEGIN IMMEDIATE");
            try {
                createSchema(sql);
                sql.execute("COMMIT");
            } catch (SQLException | RuntimeException error) {
                try { sql.execute("ROLLBACK"); }
                catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
                throw error;
            }
            objectLookup = connection.prepareStatement(
                    "SELECT owner,cipher_hash,capability_hash,size,next_chunk,complete,expires_at FROM objects WHERE id=?");
            advanceUpload = connection.prepareStatement(
                    "UPDATE objects SET next_chunk=?,expires_at=? WHERE id=? AND next_chunk=? AND complete=0");
        } catch (SQLException | RuntimeException error) {
            try { connection.close(); }
            catch (SQLException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    private static void createSchema(Statement sql) throws SQLException {
        sql.execute("""
            CREATE TABLE IF NOT EXISTS objects (
                id TEXT PRIMARY KEY, owner TEXT NOT NULL, cipher_hash TEXT NOT NULL,
                capability_hash TEXT NOT NULL, size INTEGER NOT NULL, next_chunk INTEGER NOT NULL DEFAULT 0,
                complete INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)
            """);
        sql.execute("CREATE INDEX IF NOT EXISTS objects_owner ON objects(owner,id)");
        sql.execute("CREATE INDEX IF NOT EXISTS objects_expiry ON objects(expires_at)");
        sql.execute("CREATE TABLE IF NOT EXISTS object_owner_usage (owner TEXT PRIMARY KEY, bytes INTEGER NOT NULL)");
        sql.execute("CREATE TABLE IF NOT EXISTS object_total_usage (id INTEGER PRIMARY KEY CHECK(id=1), bytes INTEGER NOT NULL)");
        sql.execute("CREATE TABLE IF NOT EXISTS object_schema (version INTEGER PRIMARY KEY)");
        boolean uploadDeadlinesReady;
        try (var status = sql.executeQuery("SELECT 1 FROM object_schema WHERE version=2")) {
            uploadDeadlinesReady = status.next();
        }
        if (!uploadDeadlinesReady) {
            sql.execute("UPDATE objects SET expires_at=MIN(expires_at,created_at+" + PARTIAL_RETENTION_SECONDS + ") WHERE complete=0");
            sql.execute("DROP INDEX IF EXISTS objects_partial_expiry");
            sql.execute("INSERT INTO object_schema(version) VALUES(2)");
        }
        boolean countersReady;
        try (var status = sql.executeQuery("""
                SELECT EXISTS(SELECT 1 FROM object_schema WHERE version=1)
                  AND COALESCE((SELECT bytes FROM object_total_usage WHERE id=1),-1)
                        =(SELECT COALESCE(SUM(bytes),0) FROM object_owner_usage)
                  AND NOT EXISTS(SELECT 1 FROM object_owner_usage WHERE bytes<=0)
                  AND (SELECT COUNT(*) FROM sqlite_schema WHERE type='trigger' AND tbl_name='objects'
                       AND name IN ('object_insert','object_delete'))=2
                """)) {
            countersReady = status.next() && status.getBoolean(1);
        }
        if (!countersReady) {
            sql.execute("DROP TRIGGER IF EXISTS object_insert");
            sql.execute("DROP TRIGGER IF EXISTS object_delete");
            sql.execute("DELETE FROM object_owner_usage");
            sql.execute("INSERT INTO object_owner_usage SELECT owner,SUM(size+" + ROW_COST + ") FROM objects GROUP BY owner");
            sql.execute("INSERT OR REPLACE INTO object_total_usage SELECT 1,COALESCE(SUM(bytes),0) FROM object_owner_usage");
        }
        sql.execute("""
            CREATE TRIGGER IF NOT EXISTS object_insert AFTER INSERT ON objects BEGIN
                INSERT INTO object_owner_usage VALUES(NEW.owner,NEW.size+@row_cost@)
                  ON CONFLICT(owner) DO UPDATE SET bytes=bytes+NEW.size+@row_cost@;
                UPDATE object_total_usage SET bytes=bytes+NEW.size+@row_cost@ WHERE id=1;
            END
            """.replace("@row_cost@", Integer.toString(ROW_COST)));
        sql.execute("""
            CREATE TRIGGER IF NOT EXISTS object_delete AFTER DELETE ON objects BEGIN
                UPDATE object_owner_usage SET bytes=bytes-OLD.size-@row_cost@ WHERE owner=OLD.owner;
                DELETE FROM object_owner_usage WHERE owner=OLD.owner AND bytes<=0;
                UPDATE object_total_usage SET bytes=bytes-OLD.size-@row_cost@ WHERE id=1;
            END
            """.replace("@row_cost@", Integer.toString(ROW_COST)));
        if (!countersReady) sql.execute("INSERT OR IGNORE INTO object_schema(version) VALUES(1)");
    }

    public Status begin(String owner, String id, long size, String hash, String capability) throws SQLException, IOException {
        identifier(owner); identifier(id); identifier(hash); identifier(capability);
        long maximum = Math.max(1, (limits.maximumPlaintextBytes()+65_535)/65_536) * CHUNK_BYTES;
        if (size < CHUNK_BYTES || size > maximum || size % CHUNK_BYTES != 0) throw invalid();
        synchronized (lock(id)) {
            Row row = database(db -> {
                db.setAutoCommit(false);
                try {
                    Row previous = row(id);
                    if (previous != null) {
                        if (!previous.owner.equals(owner) || previous.size != size || !previous.hash.equals(hash)
                                || !previous.capability.equals(capability)) throw invalid();
                        db.commit(); return previous;
                    }
                    long total = usage(db, "SELECT bytes FROM object_total_usage WHERE id=1", null);
                    long owned = usage(db, "SELECT bytes FROM object_owner_usage WHERE owner=?", owner);
                    long cost = size + ROW_COST;
                    if (owned > limits.ownerBytes() - cost || total > limits.totalBytes() - cost)
                        throw new IllegalArgumentException("Encrypted object quota exceeded");
                    long started = now();
                    long expiry = started + PARTIAL_RETENTION_SECONDS;
                    try (var insert = db.prepareStatement("INSERT INTO objects(id,owner,cipher_hash,capability_hash,size,created_at,expires_at) VALUES(?,?,?,?,?,?,?)")) {
                        insert.setString(1,id); insert.setString(2,owner); insert.setString(3,hash); insert.setString(4,capability);
                        insert.setLong(5,size); insert.setLong(6,started); insert.setLong(7,expiry); insert.executeUpdate();
                    }
                    db.commit(); return new Row(owner,hash,capability,size,0,false,expiry);
                } catch (SQLException | RuntimeException error) { rollback(db,error); throw error; }
                finally { db.setAutoCommit(true); }
            });
            long finalSize = fileSize(path(id,true));
            if (row.complete && finalSize == row.size) {
                long expiry = now() + limits.retentionDays() * 86400L;
                updateProgress(id, row.next, true, expiry);
                return new Status(row.next, true, expiry);
            }

            long committed = row.next * CHUNK_BYTES;
            long partialSize = finalSize < 0 ? fileSize(path(id,false)) : -1;
            if (row.complete || (finalSize >= 0 && (finalSize != row.size || committed != row.size)) ||
                    (finalSize < 0 && (partialSize < 0 ? committed != 0 : partialSize < committed)) ||
                    row.expiry <= now()) {
                row = restartUpload(id, row);
                finalSize = -1;
                partialSize = -1;
                committed = 0;
            }
            if (finalSize < 0 && partialSize != committed) {
                Path file = path(id,false);
                Files.createDirectories(file.getParent());
                if (Files.isSymbolicLink(file.getParent())) throw new IOException("Invalid object shard directory");
                try (FileChannel stream = FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    if (stream.size() < committed) throw new IOException("Incomplete durable object data");
                    stream.truncate(committed);
                    stream.force(true);
                }
            }
            return row.status();
        }
    }

    private Row restartUpload(String id, Row row) throws IOException, SQLException {
        Files.deleteIfExists(path(id,false));
        Files.deleteIfExists(path(id,true));
        long started = now();
        long expires = started + PARTIAL_RETENTION_SECONDS;
        database(db -> {
            try (var restart = db.prepareStatement(
                    "UPDATE objects SET next_chunk=0,complete=0,created_at=?,expires_at=? WHERE id=?")) {
                restart.setLong(1, started);
                restart.setLong(2, expires);
                restart.setString(3, id);
                if (restart.executeUpdate() != 1) throw invalid();
            }
            return null;
        });
        return new Row(row.owner, row.hash, row.capability, row.size, 0, false, expires);
    }

    private void updateProgress(String id, long next, boolean complete, long expiry) throws SQLException {
        database(db -> {
            try (var update = db.prepareStatement(
                    "UPDATE objects SET next_chunk=?,complete=?,expires_at=? WHERE id=?")) {
                update.setLong(1, next);
                update.setBoolean(2, complete);
                update.setLong(3, expiry);
                update.setString(4, id);
                if (update.executeUpdate() != 1) throw invalid();
            }
            return null;
        });
    }

    public Status put(String owner, String id, long index, byte[] ciphertext) throws SQLException, IOException {
        identifier(id);
        if (ciphertext == null || ciphertext.length != CHUNK_BYTES || index < 0) throw invalid();
        synchronized (lock(id)) {
            Row row = required(id);
            if (!row.owner.equals(owner) || index >= row.chunks() || index > row.next || row.expiry <= now()) throw invalid();
            if (row.complete || index < row.next) {
                Path finalFile = path(id,true);
                byte[] previous = read(row.complete || fileSize(finalFile) >= 0 ? finalFile : path(id,false),index);
                if (!MessageDigest.isEqual(previous,ciphertext)) throw invalid();
                return row.status();
            }
            Path partialFile = path(id,false);
            try (FileChannel file = FileChannel.open(partialFile,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                long offset = index*CHUNK_BYTES;
                if (file.size() < offset) throw new IOException("Incomplete durable object data");
                file.truncate(offset);
                file.position(offset);
                ByteBuffer source = ByteBuffer.wrap(ciphertext);
                while (source.hasRemaining()) file.write(source);
                file.force(true); // Never acknowledge a chunk that exists only in a write cache.
            }
            if (index == 0) {
                // The first durable cursor must also keep the partial file reachable after restart.
                forceDirectory(partialFile.getParent());
                forceDirectory(directory);
            }
            long expiry = now() + PARTIAL_RETENTION_SECONDS;
            database(db -> {
                advanceUpload.setLong(1, index + 1);
                advanceUpload.setLong(2, expiry);
                advanceUpload.setString(3, id);
                advanceUpload.setLong(4, index);
                if (advanceUpload.executeUpdate() != 1) throw new SQLException("Object progress changed unexpectedly");
                return null;
            });
            return new Status(index+1,false,expiry);
        }
    }

    public Status finish(String owner, String id) throws SQLException, IOException {
        identifier(id);
        synchronized (lock(id)) {
            Row row = required(id);
            if (!row.owner.equals(owner) || row.next != row.chunks() || row.expiry <= now()) throw invalid();
            Path finalFile = path(id,true);
            long finalSize = fileSize(finalFile);
            if (row.complete && finalSize == row.size) return row.status();
            Path source = finalSize >= 0 ? finalFile : path(id,false);
            if (row.complete || (finalSize >= 0 ? finalSize : fileSize(source)) != row.size) {
                restartUpload(id, row);
                throw invalid();
            }
            MessageDigest digest = sha256();
            long bytes = 0;
            try (FileChannel stream = FileChannel.open(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(64*1024);
                for (int count; (count=stream.read(buffer))>=0;) {
                    bytes += count;
                    if (bytes > row.size) break;
                    buffer.flip(); digest.update(buffer); buffer.clear();
                }
            }
            if (bytes != row.size || !MessageDigest.isEqual(digest.digest(),HexFormat.of().parseHex(row.hash))) {
                restartUpload(id, row);
                throw invalid();
            }
            if (!source.equals(finalFile)) Files.move(source,finalFile,StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(finalFile.getParent());
            forceDirectory(directory);
            long expiry=now()+limits.retentionDays()*86400L;
            updateProgress(id, row.next, true, expiry);
            return new Status(row.next,true,expiry);
        }
    }

    public byte[] get(String id, long index, byte[] capability) throws SQLException, IOException {
        identifier(id);
        if (capability == null || capability.length != 32) throw invalid();
        synchronized (lock(id)) {
            Row row = database(db -> row(id));
            if (row == null || row.expiry <= now() || IdentityRevocations.revoked(row.owner)
                    || !MessageDigest.isEqual(sha256().digest(capability),HexFormat.of().parseHex(row.capability))) {
                throw new DownloadUnavailable();
            }
            if (!row.complete || index < 0 || index >= row.chunks()) throw invalid();
            try { return read(path(id,true),index); }
            catch (NoSuchFileException missing) { throw new DownloadUnavailable(); }
        }
    }

    public void delete(String owner, String id) throws SQLException, IOException {
        identifier(id);
        synchronized (lock(id)) {
            Row row = database(db -> row(id));
            if (row == null) return;
            if (!row.owner.equals(owner)) throw invalid();
            deleteLocked(id);
        }
    }

    public boolean cleanupExpired() throws SQLException, IOException {
        long cutoff = now();
        var expired = database(db -> {
            var entries = new java.util.ArrayList<String>();
            try (var query = db.prepareStatement("""
                    SELECT id FROM objects WHERE expires_at<=? ORDER BY expires_at LIMIT ?
                    """)) {
                query.setLong(1, cutoff);
                query.setInt(2, EXPIRY_BATCH);
                try (var rows=query.executeQuery()) { while(rows.next()) entries.add(rows.getString(1)); }
            }
            return entries;
        });
        for (String id:expired) synchronized (lock(id)) {
            // An owner may have advanced or recreated an upload after the bounded scan.
            boolean stillExpired=database(db -> {
                try(var query=db.prepareStatement("SELECT 1 FROM objects WHERE id=? AND expires_at<=?")) {
                    query.setString(1, id);
                    query.setLong(2, cutoff);
                    try(var row=query.executeQuery()) { return row.next(); }
                }
            });
            if(stillExpired) deleteLocked(id);
        }
        return expired.size() == EXPIRY_BATCH;
    }

    public boolean eraseIdentity(String owner) throws SQLException,IOException {
        var ids=database(db -> {
            var result=new java.util.ArrayList<String>();
            try(var query=db.prepareStatement("SELECT id FROM objects WHERE owner=? LIMIT 16")) {
                query.setString(1,owner); try(var rows=query.executeQuery()) { while(rows.next()) result.add(rows.getString(1)); }
            }
            return result;
        });
        Exception firstFailure = null;
        for (var id : ids) {
            try {
                synchronized (lock(id)) { deleteLocked(id); }
            } catch (SQLException | IOException error) {
                if (firstFailure == null) firstFailure = error;
            }
        }
        if (firstFailure instanceof SQLException error) throw error;
        if (firstFailure instanceof IOException error) throw error;
        return database(db -> { try(var query=db.prepareStatement("SELECT 1 FROM objects WHERE owner=? LIMIT 1")) {
            query.setString(1,owner); try(var row=query.executeQuery()) { return !row.next(); }
        } });
    }

    private void deleteLocked(String id) throws IOException, SQLException {
        Files.deleteIfExists(path(id,false)); Files.deleteIfExists(path(id,true));
        database(db -> { try (var remove=db.prepareStatement("DELETE FROM objects WHERE id=?")) { remove.setString(1,id); remove.executeUpdate(); } return null; });
    }
    private Row required(String id) throws SQLException {
        Row row=database(db -> row(id));
        if (row==null) throw invalid();
        return row;
    }
    // Called only while holding the connection monitor.
    private Row row(String id) throws SQLException {
        objectLookup.setString(1, id);
        try (var row = objectLookup.executeQuery()) {
            return row.next() ? new Row(row.getString(1), row.getString(2), row.getString(3), row.getLong(4),
                    row.getLong(5), row.getInt(6) != 0, row.getLong(7)) : null;
        }
    }
    private static long usage(Connection db,String sql,String owner) throws SQLException {
        try (var query=db.prepareStatement(sql)) {
            if(owner!=null) query.setString(1,owner);
            try(var result=query.executeQuery()) { return result.next()?result.getLong(1):0; }
        }
    }
    private <T> T database(Query<T> operation) throws SQLException { synchronized(connection) { return operation.run(connection); } }
    private Path path(String id,boolean complete) { return directory.resolve(id.substring(0,2)).resolve(id.substring(2)+(complete?".obj":".part")); }
    private static long fileSize(Path file) throws IOException {
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Invalid object data file");
            return attributes.size();
        } catch (NoSuchFileException missing) {
            return -1;
        }
    }
    private static void forceDirectory(Path directory) throws IOException {
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
    private Object lock(String id) { return locks[(id.hashCode()&0x7fffffff)%locks.length]; }
    private static byte[] read(Path file,long index) throws IOException {
        ByteBuffer result=ByteBuffer.allocate(CHUNK_BYTES);
        try(FileChannel stream=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            stream.position(index*CHUNK_BYTES);
            while(result.hasRemaining()) if(stream.read(result)<0) throw new IOException("Truncated encrypted object");
        }
        return result.array();
    }
    private static void identifier(String value) {
        if (value == null || !HEX_IDENTIFIER.matcher(value).matches()) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid encrypted object request"); }
    private static long now() { return Instant.now().getEpochSecond(); }
    private static MessageDigest sha256() { try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);} }
    private static void rollback(Connection connection,Exception original) { try{connection.rollback();}catch(SQLException error){original.addSuppressed(error);} }
    @Override public void close() throws SQLException { synchronized(connection) { connection.close(); } }
}
