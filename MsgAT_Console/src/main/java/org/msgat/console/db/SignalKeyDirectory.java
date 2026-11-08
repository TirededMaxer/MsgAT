// SPDX-License-Identifier: MIT
package org.msgat.console.db;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.msgat.console.core.BoundedJson;
import org.msgat.console.core.CryptoUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;

/** Public-key-only directory. Root signatures cover the exact received body bytes. */
public final class SignalKeyDirectory {
    private static final byte[] BUNDLE_DOMAIN = "MsgAT/v2/signal-bundle\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CERTIFICATE_DOMAIN = "MsgAT/v2/protocol-identity\0".getBytes(StandardCharsets.UTF_8);
    private SignalKeyDirectory() {}

    static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS signal_directory (uuid TEXT PRIMARY KEY, bundle_hash TEXT NOT NULL, bundle_json TEXT NOT NULL, expires_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS signal_directory_keys (uuid TEXT NOT NULL, key_id INTEGER NOT NULL, public_hash TEXT NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid,key_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS signal_directory_claims (requester TEXT NOT NULL, request_id TEXT NOT NULL, target TEXT NOT NULL, response_json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(requester,request_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS signal_claims_expiry ON signal_directory_claims(created_at)");
        }
    }

    public static void publish(String uuid, byte[] authenticatedRoot, JsonObject envelope) throws SQLException {
        BoundedJson.fields(envelope, "root_public_key", "body", "signature");
        byte[] root = bytes(envelope, "root_public_key", 32, 32);
        byte[] bodyBytes = bytes(envelope, "body", 1, 96 * 1024);
        byte[] signature = bytes(envelope, "signature", 64, 64);
        if (!MessageDigest.isEqual(root, authenticatedRoot) || !CryptoUtils.identityUuid(root).equals(uuid)) throw invalid();
        verify(root, BUNDLE_DOMAIN, bodyBytes, signature);
        JsonObject body = BoundedJson.parse(bodyBytes);
        BoundedJson.fields(body, "version", "expires_at", "registration_id", "certificate", "signed_prekey_id", "signed_prekey", "signed_prekey_signature", "pq_prekey_id", "pq_prekey", "pq_prekey_signature", "prekeys");
        BoundedJson.integer(body, "version", 2, 2);
        long now = Instant.now().getEpochSecond();
        long expiry = BoundedJson.integer(body, "expires_at", now + 1, now + 8 * 86400);
        BoundedJson.integer(body, "registration_id", 1, 16380);
        BoundedJson.integer(body, "signed_prekey_id", 1, 0xffffffffL);
        BoundedJson.integer(body, "pq_prekey_id", 1, 0xffffffffL);
        signalPublic(bytes(body, "signed_prekey", 33, 33));
        bytes(body, "signed_prekey_signature", 64, 64);
        bytes(body, "pq_prekey", 1, 4096);
        bytes(body, "pq_prekey_signature", 64, 64);
        if (!body.get("certificate").isJsonObject() || !body.get("prekeys").isJsonArray()) throw invalid();
        JsonObject certificate = body.getAsJsonObject("certificate");
        BoundedJson.fields(certificate, "root_public_key", "identity_key", "signature");
        if (!MessageDigest.isEqual(root, bytes(certificate, "root_public_key", 32, 32))) throw invalid();
        byte[] identity = bytes(certificate, "identity_key", 33, 33);
        signalPublic(identity);
        verify(root, CERTIFICATE_DOMAIN, identity, bytes(certificate, "signature", 64, 64));
        JsonArray prekeys = body.getAsJsonArray("prekeys");
        if (prekeys.size() > 100) throw invalid();
        Map<Long, String> keys = new LinkedHashMap<>();
        for (var element : prekeys) {
            if (!element.isJsonObject()) throw invalid();
            JsonObject key = element.getAsJsonObject();
            BoundedJson.fields(key, "id", "public_key");
            long id = BoundedJson.integer(key, "id", 1, 0xffffffffL);
            byte[] publicKey = bytes(key, "public_key", 33, 33);
            signalPublic(publicKey);
            if (keys.put(id, hash(publicKey)) != null) throw invalid();
        }
        try (Connection connection = DatabaseManager.getConnection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                for (var key : keys.entrySet()) {
                    try (var query = connection.prepareStatement("SELECT public_hash FROM signal_directory_keys WHERE uuid=? AND key_id=?")) {
                        query.setString(1, uuid); query.setLong(2, key.getKey());
                        try (var result = query.executeQuery()) {
                            if (result.next() && !result.getString(1).equals(key.getValue())) throw new IllegalArgumentException("Prekey identifiers cannot be reused for different keys");
                        }
                    }
                    try (var insert = connection.prepareStatement("INSERT INTO signal_directory_keys(uuid,key_id,public_hash) VALUES(?,?,?) ON CONFLICT(uuid,key_id) DO NOTHING")) {
                        insert.setString(1, uuid); insert.setLong(2, key.getKey()); insert.setString(3, key.getValue()); insert.executeUpdate();
                    }
                }
                try (var insert = connection.prepareStatement("INSERT INTO signal_directory(uuid,bundle_hash,bundle_json,expires_at) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET bundle_hash=excluded.bundle_hash,bundle_json=excluded.bundle_json,expires_at=excluded.expires_at")) {
                    insert.setString(1, uuid); insert.setString(2, hash(bodyBytes)); insert.setString(3, envelope.toString()); insert.setLong(4, expiry); insert.executeUpdate();
                }
                transaction.execute("COMMIT");
            } catch (SQLException | RuntimeException error) { rollback(transaction, error); throw error; }
        }
    }

    /** A repeated authenticated request ID returns its original reservation. */
    public static JsonObject claim(String requester, String requestID, String target) throws SQLException {
        if (!requestID.matches("[a-f0-9-]{36}") || !UUID.fromString(requestID).toString().equals(requestID) || !target.matches("[a-f0-9]{64}")) throw invalid();
        long now = Instant.now().getEpochSecond();
        try (Connection connection = DatabaseManager.getConnection(); Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                try (var expired = connection.prepareStatement("DELETE FROM signal_directory_claims WHERE created_at<?")) {
                    expired.setLong(1, now - 7 * 86400); expired.executeUpdate();
                }
                try (var query = connection.prepareStatement("SELECT target,response_json FROM signal_directory_claims WHERE requester=? AND request_id=?")) {
                    query.setString(1, requester); query.setString(2, requestID);
                    try (var result = query.executeQuery()) {
                        if (result.next()) {
                            if (!result.getString(1).equals(target)) throw new IllegalArgumentException("Request identifier is already bound to another contact");
                            JsonObject response = BoundedJson.parse(result.getString(2).getBytes(StandardCharsets.UTF_8));
                            transaction.execute("COMMIT"); return response;
                        }
                    }
                }
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM signal_directory_claims WHERE requester=?")) {
                    query.setString(1, requester);
                    try (var result = query.executeQuery()) { if (result.next() && result.getInt(1) >= 256) throw new IllegalArgumentException("Directory reservation quota exceeded"); }
                }
                JsonObject response = new JsonObject();
                response.addProperty("request_id", requestID); response.addProperty("uuid", target); response.addProperty("found", false);
                try (var query = connection.prepareStatement("SELECT bundle_json FROM signal_directory WHERE uuid=? AND expires_at>?")) {
                    query.setString(1, target); query.setLong(2, now);
                    try (var result = query.executeQuery()) {
                        if (result.next()) {
                            JsonObject bundle = BoundedJson.parse(result.getString(1).getBytes(StandardCharsets.UTF_8));
                            JsonObject body = BoundedJson.parse(bytes(bundle, "body", 1, 96 * 1024));
                            response.add("bundle", bundle); response.addProperty("found", true); response.add("selected_prekey", com.google.gson.JsonNull.INSTANCE);
                            for (var item : body.getAsJsonArray("prekeys")) {
                                long id = item.getAsJsonObject().get("id").getAsLong();
                                try (var reserve = connection.prepareStatement("UPDATE signal_directory_keys SET claimed=1 WHERE uuid=? AND key_id=? AND claimed=0")) {
                                    reserve.setString(1, target); reserve.setLong(2, id);
                                    if (reserve.executeUpdate() == 1) { response.addProperty("selected_prekey", id); break; }
                                }
                            }
                        }
                    }
                }
                try (var insert = connection.prepareStatement("INSERT INTO signal_directory_claims(requester,request_id,target,response_json,created_at) VALUES(?,?,?,?,?)")) {
                    insert.setString(1, requester); insert.setString(2, requestID); insert.setString(3, target); insert.setString(4, response.toString()); insert.setLong(5, now); insert.executeUpdate();
                }
                transaction.execute("COMMIT"); return response;
            } catch (SQLException | RuntimeException error) { rollback(transaction, error); throw error; }
        }
    }

    private static void rollback(Statement transaction, Exception original) {
        try { transaction.execute("ROLLBACK"); } catch (SQLException error) { original.addSuppressed(error); }
    }
    private static void signalPublic(byte[] bytes) { if (bytes[0] != 5) throw invalid(); }
    private static byte[] bytes(JsonObject object, String name, int minimum, int maximum) {
        String encoded = BoundedJson.string(object, name, ((maximum + 2) / 3) * 4);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < minimum || bytes.length > maximum) throw invalid();
        return bytes;
    }
    private static void verify(byte[] root, byte[] domain, byte[] body, byte[] signature) {
        Ed25519Signer verifier = new Ed25519Signer(); verifier.init(false, new Ed25519PublicKeyParameters(root, 0));
        verifier.update(domain, 0, domain.length); verifier.update(body, 0, body.length);
        if (!verifier.verifySignature(signature)) throw invalid();
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid signed key directory request"); }
}
