package org.msgat.console.core;

import org.bouncycastle.jcajce.provider.digest.Blake2b;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class CryptoUtils {
    private static final Logger log = LoggerFactory.getLogger(CryptoUtils.class);
    private static final String AES_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // in bits
    private static final int GCM_IV_LENGTH = 12; // in bytes

    private static SecretKeySpec databaseKey = null;

    private static synchronized SecretKeySpec getDatabaseKey() {
        if (databaseKey != null) {
            return databaseKey;
        }

        // Embedded key reconstruction; this is obfuscation, not a separate security boundary.
        byte[] fragmentA = new byte[] {
            (byte)0xA1, 0x3F, (byte)0x8C, 0x11, 0x5D, 0x7E, 0x4B, 0x22,
            (byte)0xC9, 0x01, 0x55, (byte)0xAA, 0x1F, 0x33, (byte)0xEE, 0x77,
            0x2A, 0x6B, (byte)0x9D, 0x4C, 0x11, 0x3E, (byte)0xFF, 0x00,
            (byte)0xDD, 0x14, 0x25, 0x36, 0x47, 0x58, 0x69, 0x7A
        };

        byte[] fragmentB = new byte[] {
            0x55, (byte)0xCA, 0x12, (byte)0xEE, 0x22, 0x11, 0x33, 0x44,
            0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x70, (byte)0x81,
            (byte)0x92, (byte)0xA3, (byte)0xB4, (byte)0xC5, (byte)0xD6, (byte)0xE7, (byte)0xF8, 0x09,
            0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F, 0x70, (byte)0x81
        };

        byte[] fragmentC = new byte[] {
            (byte)0xF4, (byte)0xF5, (byte)0x9E, (byte)0xFF, 0x7F, 0x6F, 0x78, 0x66,
            (byte)0xD3, 0x2A, 0x69, (byte)0xE7, 0x41, 0x5C, (byte)0x9E, (byte)0xF6,
            (byte)0xB8, (byte)0xC8, 0x29, (byte)0x89, (byte)0xC7, (byte)0xD9, 0x07, 0x09,
            (byte)0xC7, 0x3F, 0x19, 0x7B, 0x19, 0x37, 0x19, (byte)0xFB
        };

        byte[] finalKey = new byte[32];
        for (int i = 0; i < 32; i++) {
            finalKey[i] = (byte) (fragmentA[i] ^ fragmentB[i] ^ fragmentC[i]);
        }

        databaseKey = new SecretKeySpec(finalKey, "AES");

        java.util.Arrays.fill(finalKey, (byte) 0);
        // Zero out fragments after use
        java.util.Arrays.fill(fragmentA, (byte) 0);
        java.util.Arrays.fill(fragmentB, (byte) 0);
        java.util.Arrays.fill(fragmentC, (byte) 0);

        return databaseKey;
    }

    public static void validateConfiguration() {
        getDatabaseKey();
    }

    public static String identityUuid(byte[] publicKey) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(publicKey));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Calculates a 32-byte hexadecimal mailbox ID from a given UUID using Blake2b-512.
    public static String calculateMailboxId(String uuid) {
        Blake2b.Blake2b512 digest = new Blake2b.Blake2b512();
        byte[] hash = digest.digest(uuid.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            sb.append(String.format("%02x", hash[i]));
        }
        return sb.toString();
    }

    // Encrypts a raw byte array using AES-GCM and prepends the random 12-byte IV to the returned array.
    public static byte[] encryptField(byte[] plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, getDatabaseKey(), spec);

            byte[] ciphertext = cipher.doFinal(plaintext);

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return buffer.array();
        } catch (Exception e) {
            log.error("Failed to encrypt field", e);
            return null;
        }
    }

    // Decrypts a byte array that has a 12-byte IV prepended to the AES-GCM ciphertext.
    public static byte[] decryptField(byte[] cipherData) {
        if (cipherData == null || cipherData.length <= GCM_IV_LENGTH) return null;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(cipherData);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);

            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(AES_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, getDatabaseKey(), spec);

            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            log.error("Failed to decrypt field", e);
            return null;
        }
    }

    // Helper to encrypt a String and return the result as Base64 for database storage.
    public static String encryptString(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        byte[] encrypted = encryptField(plaintext.getBytes(StandardCharsets.UTF_8));
        return encrypted != null ? Base64.getEncoder().encodeToString(encrypted) : null;
    }

    // Helper to decrypt a Base64-encoded encrypted string back to plaintext.
    public static String decryptString(String base64Cipher) {
        if (base64Cipher == null || base64Cipher.isEmpty()) return base64Cipher;
        try {
            byte[] cipherData = Base64.getDecoder().decode(base64Cipher);
            byte[] decrypted = decryptField(cipherData);
            return decrypted != null ? new String(decrypted, StandardCharsets.UTF_8) : null;
        } catch (IllegalArgumentException e) {
            // Probably not base64 (maybe old unencrypted data), return as is for safety or fail.
            log.warn("String is not base64 during decryption");
            return null;
        }
    }
}
