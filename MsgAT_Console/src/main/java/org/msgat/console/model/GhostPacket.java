package org.msgat.console.model;

import java.util.Arrays;

public class GhostPacket {

    public static final byte TYPE_AUTH_REQ = 0x00;
    public static final byte TYPE_AUTH_CHAL = 0x01;
    public static final byte TYPE_AUTH_RESP = 0x02;
    public static final byte TYPE_MESSAGE = 0x03;
    public static final byte TYPE_HEARTBEAT = 0x04;
    public static final byte TYPE_ACK = 0x05;
    public static final byte TYPE_OFFLINE_END = 0x06;
    public static final byte TYPE_SEARCH_USER = 0x07;
    public static final byte TYPE_UPDATE_PROFILE = 0x08;
    public static final byte TYPE_GET_PROFILE = 0x0C;
    public static final byte TYPE_PROFILE_RESP = 0x0D;
    public static final byte TYPE_FRIEND_ACCEPTED = 0x0E;
    public static final byte TYPE_FRIEND_DELETED = 0x0F;
    public static final byte TYPE_REQUEST_REVOKED = 0x10;
    public static final byte TYPE_ACCOUNT_RESET = 0x11;
    public static final byte TYPE_DELETE_ACCOUNT = 0x12;

    // Voice Call Signaling
    public static final byte TYPE_CALL_OFFER = 0x13;
    public static final byte TYPE_CALL_ANSWER = 0x14;
    public static final byte TYPE_CALL_ICE = 0x15;
    public static final byte TYPE_CALL_REJECT = 0x16;
    public static final byte TYPE_CALL_END = 0x17;
    public static final byte TYPE_CALL_BUSY = 0x18;
    private final byte type;
    private final String targetMailboxId;
    private final byte[] nonceOrIv;
    private final int payloadLen;
    private final byte[] encryptedData;
    private final byte[] authTag;

    // Constructs a new GhostPacket with specified type, target, IV, data, and authentication tag.
    public GhostPacket(byte type, String targetMailboxId, byte[] nonceOrIv, byte[] encryptedData, byte[] authTag) {
        this.type = type;
        this.targetMailboxId = targetMailboxId;
        this.nonceOrIv = nonceOrIv;
        this.encryptedData = encryptedData;
        this.payloadLen = (encryptedData != null) ? encryptedData.length : 0;
        this.authTag = authTag;
    }

    // Returns the packet type identifier.
    public byte getType() { return type; }
    
    // Returns the target mailbox identifier.
    public String getTargetMailboxId() { return targetMailboxId; }
    
    // Returns the initialization vector or challenge nonce array.
    public byte[] getNonceOrIv() { return nonceOrIv; }
    
    // Returns the length of the encrypted payload data.
    public int getPayloadLen() { return payloadLen; }
    
    // Returns the encrypted payload data array.
    public byte[] getEncryptedData() { return encryptedData; }
    
    // Returns the GCM authentication tag array.
    public byte[] getAuthTag() { return authTag; }

    // Securely clears sensitive memory arrays by filling them with zeros.
    public void wipeData() {
        if (encryptedData != null) Arrays.fill(encryptedData, (byte) 0);
        if (nonceOrIv != null) Arrays.fill(nonceOrIv, (byte) 0);
        if (authTag != null) Arrays.fill(authTag, (byte) 0);
    }

    // Serializes the current packet object into a raw byte array for transmission or storage.
    public byte[] serialize() {
        byte[] targetBytes = targetMailboxId != null ? targetMailboxId.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        int size = 1 + 4 + targetBytes.length + 16 + 4 + payloadLen + 16;
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(size);
        buffer.put(type);
        buffer.putInt(targetBytes.length);
        buffer.put(targetBytes);
        buffer.put(nonceOrIv != null ? nonceOrIv : new byte[16]);
        buffer.putInt(payloadLen);
        if (encryptedData != null) buffer.put(encryptedData);
        if (authTag != null) buffer.put(authTag); else buffer.put(new byte[16]);
        return buffer.array();
    }

    // Deserializes a raw byte array back into a GhostPacket object.
    public static GhostPacket deserialize(byte[] data) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
        byte type = buffer.get();
        int targetLen = buffer.getInt();
        byte[] targetBytes = new byte[targetLen];
        buffer.get(targetBytes);
        String targetMailboxId = new String(targetBytes, java.nio.charset.StandardCharsets.UTF_8);
        
        byte[] nonceOrIv = new byte[16];
        buffer.get(nonceOrIv);
        
        int payloadLen = buffer.getInt();
        byte[] encryptedData = new byte[payloadLen];
        buffer.get(encryptedData);
        
        byte[] authTag = new byte[16];
        buffer.get(authTag);
        
        return new GhostPacket(type, targetMailboxId, nonceOrIv, encryptedData, authTag);
    }
}