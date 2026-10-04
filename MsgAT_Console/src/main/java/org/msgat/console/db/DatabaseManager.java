package org.msgat.console.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String DB_DIR = System.getProperty("msgat.data.dir", "data");
    private static final String DB_URL = "jdbc:sqlite:" + DB_DIR + "/msgat.db";
    private static final String OFFLINE_DB_URL = "jdbc:sqlite:" + DB_DIR + "/msgat_offline.db";

    // Initializes the local database directory, schema and tables for users, groups, and offline messages.
    public static void init() {
        File dataDir = new File(DB_DIR);
        if (!dataDir.exists()) {
            if (!dataDir.mkdirs()) {
                throw new IllegalStateException("Unable to create database directory: " + dataDir.getAbsolutePath());
            }
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "uuid TEXT PRIMARY KEY, " +
                    "mailbox_id TEXT, " +
                    "nickname TEXT, " +
                    "avatar_path TEXT, " +
                    "avatar_data TEXT, " +
                    "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_mailbox ON users(mailbox_id)");

            if (!hasColumn(conn, "users", "avatar_data")) {
                stmt.execute("ALTER TABLE users ADD COLUMN avatar_data TEXT");
            }

        } catch (SQLException e) {
            throw new IllegalStateException("DB init failed", e);
        }

        try (Connection conn = getOfflineConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS offline_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "target_mailbox TEXT, " +
                    "packet_data BLOB, " +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");

            if (!hasColumn(conn, "offline_messages", "packet_hash")) {
                stmt.execute("ALTER TABLE offline_messages ADD COLUMN packet_hash TEXT");
            }
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_offline_packet ON offline_messages(target_mailbox, packet_hash)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_offline_mailbox ON offline_messages(target_mailbox)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_offline_created ON offline_messages(created_at)");
            initializeMailboxUsage(conn);

        } catch (SQLException e) {
            throw new IllegalStateException("Offline DB init failed", e);
        }
    }

    // Transactional counters avoid rescanning every existing attachment chunk on each insert.
    private static void initializeMailboxUsage(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            boolean exists;
            try (var result = statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='offline_mailbox_usage'")) {
                exists = result.next();
            }
            statement.execute("CREATE TABLE IF NOT EXISTS offline_mailbox_usage (target_mailbox TEXT PRIMARY KEY, stored_bytes INTEGER NOT NULL)");
            if (!exists) statement.executeUpdate("INSERT INTO offline_mailbox_usage SELECT target_mailbox, SUM(length(packet_data)) FROM offline_messages GROUP BY target_mailbox");
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS offline_usage_insert AFTER INSERT ON offline_messages BEGIN
                      INSERT INTO offline_mailbox_usage VALUES (NEW.target_mailbox, length(NEW.packet_data))
                      ON CONFLICT(target_mailbox) DO UPDATE SET stored_bytes=stored_bytes+length(NEW.packet_data);
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS offline_usage_delete AFTER DELETE ON offline_messages BEGIN
                      UPDATE offline_mailbox_usage SET stored_bytes=stored_bytes-length(OLD.packet_data) WHERE target_mailbox=OLD.target_mailbox;
                      DELETE FROM offline_mailbox_usage WHERE target_mailbox=OLD.target_mailbox AND stored_bytes<=0;
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS offline_usage_update AFTER UPDATE OF packet_data,target_mailbox ON offline_messages BEGIN
                      UPDATE offline_mailbox_usage SET stored_bytes=stored_bytes-length(OLD.packet_data) WHERE target_mailbox=OLD.target_mailbox;
                      DELETE FROM offline_mailbox_usage WHERE target_mailbox=OLD.target_mailbox AND stored_bytes<=0;
                      INSERT INTO offline_mailbox_usage VALUES (NEW.target_mailbox, length(NEW.packet_data))
                      ON CONFLICT(target_mailbox) DO UPDATE SET stored_bytes=stored_bytes+length(NEW.packet_data);
                    END
                    """);
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally { connection.setAutoCommit(true); }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (java.sql.ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            return result.next();
        }
    }

    // Opens and returns a new SQL database connection to the SQLite instance.
    public static Connection getConnection() throws SQLException {
        return openConnection(DB_URL);
    }

    public static Connection getOfflineConnection() throws SQLException {
        return openConnection(OFFLINE_DB_URL);
    }

    private static Connection openConnection(String url) throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            return connection;
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
    }

    public static String uuidForMailbox(String mailboxId) {
        try (Connection connection = getConnection();
             var statement = connection.prepareStatement("SELECT uuid FROM users WHERE mailbox_id = ? LIMIT 1")) {
            statement.setString(1, mailboxId);
            try (var result = statement.executeQuery()) { return result.next() ? result.getString(1) : null; }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to resolve mailbox", error);
        }
    }

    public static void wipeOfflineDatabaseFile() {
        File f = new File(DB_DIR + "/msgat_offline.db");
        if (f.exists()) {
            f.delete();
        }
    }

    // Upserts user profile information into the users table.
    public static void saveUser(String uuid, String mailboxId, String nickname, String avatarPath, String avatarData) {
        try (Connection conn = getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO users (uuid, mailbox_id, nickname, avatar_path, avatar_data) VALUES (?, ?, ?, ?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET mailbox_id=excluded.mailbox_id, nickname=excluded.nickname, avatar_path=excluded.avatar_path, avatar_data=excluded.avatar_data, updated_at=CURRENT_TIMESTAMP")) {
            stmt.setString(1, uuid);
            stmt.setString(2, mailboxId);
            stmt.setString(3, org.msgat.console.core.CryptoUtils.encryptString(nickname));
            stmt.setString(4, org.msgat.console.core.CryptoUtils.encryptString(avatarPath));
            stmt.setString(5, org.msgat.console.core.CryptoUtils.encryptString(avatarData));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to save profile", e);
        }
    }

    // Checks if a given mailbox ID exists in the users table.
    public static boolean doesMailboxExist(String mailboxId) {
        try (Connection conn = getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM users WHERE mailbox_id = ? LIMIT 1")) {
            stmt.setString(1, mailboxId);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query mailbox", e);
        }
    }

    public static class UserProfile {
        public String uuid, nickname, avatarPath, avatarData;

        // Constructor for creating a new UserProfile instance.
        public UserProfile(String u, String n, String a, String d) { this.uuid = u; this.nickname = n; this.avatarPath = a; this.avatarData = d; }
    }

    // Retrieves a user's profile information by their UUID from the database.
    public static UserProfile getUser(String uuid) {
        try (Connection conn = getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT nickname, avatar_path, avatar_data FROM users WHERE uuid = ?")) {
            stmt.setString(1, uuid);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String nickname = org.msgat.console.core.CryptoUtils.decryptString(rs.getString("nickname"));
                    String avatarPath = org.msgat.console.core.CryptoUtils.decryptString(rs.getString("avatar_path"));
                    String avatarData = org.msgat.console.core.CryptoUtils.decryptString(rs.getString("avatar_data"));
                    return new UserProfile(uuid, nickname, avatarPath, avatarData);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load profile {}", uuid, e);
        }
        return null;
    }

    // Deletes all records associated with a specific mailbox ID.
    public static void deleteUserAndData(String mailboxId) {
        // Both SQLite files participate in one transaction (rollback journals, same filesystem).
        try (Connection connection = getConnection();
             var attach = connection.prepareStatement("ATTACH DATABASE ? AS offline")) {
            attach.setString(1, new File(DB_DIR, "msgat_offline.db").getAbsolutePath());
            attach.execute();
            connection.setAutoCommit(false);
            try (var users = connection.prepareStatement("DELETE FROM users WHERE mailbox_id = ?");
                 var packets = connection.prepareStatement("DELETE FROM offline.offline_messages WHERE target_mailbox = ?")) {
                users.setString(1, mailboxId);
                packets.setString(1, mailboxId);
                users.executeUpdate();
                packets.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
            log.info("Deleted all data for mailbox: {}", mailboxId);
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to delete account data", error);
        }
    }

    // Deletes messages from the database that are older than three days.
    public static void cleanupOldOfflineMessages() {
        try (Connection conn = getOfflineConnection();
             Statement stmt = conn.createStatement()) {
            int deleted;
            do {
                deleted = stmt.executeUpdate("DELETE FROM offline_messages WHERE id IN (SELECT id FROM offline_messages WHERE created_at < datetime('now', '-3 days') LIMIT 1000)");
            } while (deleted == 1000 && !Thread.currentThread().isInterrupted());
        } catch (SQLException e) {
            log.error("Failed to clean up old offline messages", e);
        }
    }
}
