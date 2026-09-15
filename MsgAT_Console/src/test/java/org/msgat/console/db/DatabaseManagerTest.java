package org.msgat.console.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseManagerTest {
    @BeforeEach
    void initializeCleanTables() throws Exception {
        DatabaseManager.init();
        try (Connection users = DatabaseManager.getConnection();
             Connection offline = DatabaseManager.getOfflineConnection()) {
            users.createStatement().executeUpdate("DELETE FROM users");
            offline.createStatement().executeUpdate("DELETE FROM offline_messages");
        }
    }

    @Test
    void deleteUserAndDataRemovesBothDatabaseRecords() throws Exception {
        String mailbox = "mailbox-under-test";
        try (Connection users = DatabaseManager.getConnection();
             Connection offline = DatabaseManager.getOfflineConnection()) {
            try (var statement = users.prepareStatement(
                    "INSERT INTO users(uuid, mailbox_id, nickname, avatar_path, avatar_data) VALUES (?, ?, '', '', '')")) {
                statement.setString(1, "uuid-under-test");
                statement.setString(2, mailbox);
                statement.executeUpdate();
            }
            try (var statement = offline.prepareStatement(
                    "INSERT INTO offline_messages(target_mailbox, packet_data) VALUES (?, ?)")) {
                statement.setString(1, mailbox);
                statement.setBytes(2, new byte[] {1, 2, 3});
                statement.executeUpdate();
            }
        }

        DatabaseManager.deleteUserAndData(mailbox);

        try (Connection users = DatabaseManager.getConnection();
             Connection offline = DatabaseManager.getOfflineConnection()) {
            var userResult = users.createStatement().executeQuery("SELECT COUNT(*) FROM users");
            var offlineResult = offline.createStatement().executeQuery("SELECT COUNT(*) FROM offline_messages");
            assertEquals(0, userResult.getInt(1));
            assertEquals(0, offlineResult.getInt(1));
        }
    }
}
