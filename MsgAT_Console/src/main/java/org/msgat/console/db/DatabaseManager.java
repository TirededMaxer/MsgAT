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
    private static final String DB_DIR = "data";
    private static final String DB_URL = "jdbc:sqlite:" + DB_DIR + "/msgat.db";
    private static final String OFFLINE_DB_URL = "jdbc:sqlite:" + DB_DIR + "/msgat_offline.db";

    // Initializes the local database directory, schema and tables for users, groups, and offline messages.
    public static void init() {
        File dataDir = new File(DB_DIR);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
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

            try {
                stmt.execute("ALTER TABLE users ADD COLUMN avatar_data TEXT");
            } catch (SQLException e) {
            }

        } catch (SQLException e) {
            log.error("DB init failed", e);
        }

        try (Connection conn = getOfflineConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS offline_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "target_mailbox TEXT, " +
                    "packet_data BLOB, " +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_offline_mailbox ON offline_messages(target_mailbox)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_offline_created ON offline_messages(created_at)");

        } catch (SQLException e) {
            log.error("Offline DB init failed", e);
        }
    }

    // Opens and returns a new SQL database connection to the SQLite instance.
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    public static Connection getOfflineConnection() throws SQLException {
        return DriverManager.getConnection(OFFLINE_DB_URL);
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
        }
        return false;
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
        }
        return null;
    }

    // Deletes all records associated with a specific mailbox ID.
    public static void deleteUserAndData(String mailboxId) {
        try (Connection conn = getConnection();
             java.sql.PreparedStatement stmtUsers = conn.prepareStatement("DELETE FROM users WHERE mailbox_id = ?");
             java.sql.PreparedStatement stmtOffline = conn.prepareStatement("DELETE FROM offline_messages WHERE target_mailbox = ?")) {
            stmtUsers.setString(1, mailboxId);
            stmtUsers.executeUpdate();
            
            stmtOffline.setString(1, mailboxId);
            stmtOffline.executeUpdate();
            log.info("Deleted all data for mailbox: {}", mailboxId);
        } catch (SQLException e) {
            log.error("Failed to delete user data", e);
        }
    }

    // Deletes messages from the database that are older than three days.
    public static void cleanupOldOfflineMessages() {
        try (Connection conn = getOfflineConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM offline_messages WHERE created_at < datetime('now', '-3 days')");
        } catch (SQLException e) {
        }
    }
}
