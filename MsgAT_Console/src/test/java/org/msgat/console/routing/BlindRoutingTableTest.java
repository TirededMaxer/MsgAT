package org.msgat.console.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.msgat.console.core.CryptoUtils;
import org.msgat.console.db.DatabaseManager;
import org.msgat.console.model.GhostPacket;


import static org.junit.jupiter.api.Assertions.*;

class BlindRoutingTableTest {
    private final BlindRoutingTable routing = new BlindRoutingTable();

    @BeforeEach
    void initialize() throws Exception {
        DatabaseManager.init();
        try (var connection = DatabaseManager.getOfflineConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM offline_messages");
        }
    }

    private GhostPacket packet(String mailbox, byte nonce, byte tag) {
        byte[] iv = new byte[16];
        byte[] authTag = new byte[16];
        iv[0] = nonce;
        authTag[0] = tag;
        return new GhostPacket(GhostPacket.TYPE_MESSAGE, mailbox, iv, new byte[] {1, 2, 3}, authTag);
    }

    @Test
    void exactRetriesKeepOneRecordAndAckRemainsMailboxScoped() throws Exception {
        GhostPacket first = packet("recipient-a", (byte) 1, (byte) 2);
        for (int attempt = 0; attempt < 20; attempt++) routing.storeOfflineMessage("recipient-a", first);
        routing.storeOfflineMessage("recipient-b", packet("recipient-b", (byte) 1, (byte) 2));
        assertEquals(1, routing.retrieveOfflineBatch("recipient-a", 0).packets().size());
        assertEquals(1, routing.retrieveOfflineBatch("recipient-b", 0).packets().size());
        routing.acknowledge("recipient-a", new byte[0]);
        routing.acknowledge("recipient-a", new byte[32]);
        assertEquals(1, routing.retrieveOfflineBatch("recipient-a", 0).packets().size());
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(first.getEncryptedData());
        routing.acknowledge("recipient-a", digest);
        assertTrue(routing.retrieveOfflineBatch("recipient-a", 0).packets().isEmpty());
        assertEquals(1, routing.retrieveOfflineBatch("recipient-b", 0).packets().size());
    }

    @Test
    void equalPayloadWithDifferentNonceOrTagIsNotMerged() {
        routing.storeOfflineMessage("recipient-a", packet("recipient-a", (byte) 1, (byte) 2));
        routing.storeOfflineMessage("recipient-a", packet("recipient-a", (byte) 3, (byte) 2));
        routing.storeOfflineMessage("recipient-a", packet("recipient-a", (byte) 1, (byte) 4));
        assertEquals(3, routing.retrieveOfflineBatch("recipient-a", 0).packets().size());
    }

    @Test
    void legacyNullHashRecordAlsoSuppressesExactRetry() throws Exception {
        GhostPacket first = packet("recipient-a", (byte) 1, (byte) 2);
        try (var connection = DatabaseManager.getOfflineConnection();
             var insert = connection.prepareStatement("INSERT INTO offline_messages(target_mailbox, packet_data) VALUES (?, ?)")) {
            insert.setString(1, "recipient-a");
            insert.setBytes(2, CryptoUtils.encryptField(first.serialize()));
            insert.executeUpdate();
        }
        routing.storeOfflineMessage("recipient-a", first);
        var stored = routing.retrieveOfflineBatch("recipient-a", 0).packets();
        assertEquals(1, stored.size());
        assertArrayEquals(first.serialize(), stored.getFirst().serialize());
    }
}
