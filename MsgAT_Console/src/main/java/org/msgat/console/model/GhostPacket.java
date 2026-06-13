package org.msgat.console.model;

import java.util.Arrays;

/**
 * 幽灵数据包定义 (固定大小 500KB)
 */
public class GhostPacket {
    public static final int PACKET_SIZE = 512000;

    // 指令类型
    public static final byte TYPE_AUTH_REQ = 0x00;
    public static final byte TYPE_AUTH_CHAL = 0x01;
    public static final byte TYPE_AUTH_RESP = 0x02;
    public static final byte TYPE_MESSAGE = 0x03;
    public static final byte TYPE_HEARTBEAT = 0x04;
    public static final byte TYPE_ACK = 0x05; // 阅后即焚确认包
    public static final byte TYPE_OFFLINE_END = 0x06; // 离线消息推送结束标记包

    private final byte type;
    private final String targetMailboxId; // 目标信箱
    private final byte[] nonceOrIv;       // AES-GCM 的 IV，或挑战 Nonce (16 bytes)
    private final int payloadLen;         // 实际密文长度
    private final byte[] encryptedData;   // AES 加密后的密文
    private final byte[] authTag;         // GCM 认证标签 (16 bytes)

    public GhostPacket(byte type, String targetMailboxId, byte[] nonceOrIv, byte[] encryptedData, byte[] authTag) {
        this.type = type;
        this.targetMailboxId = targetMailboxId;
        this.nonceOrIv = nonceOrIv;
        this.encryptedData = encryptedData;
        this.payloadLen = (encryptedData != null) ? encryptedData.length : 0;
        this.authTag = authTag;
    }

    public byte getType() { return type; }
    public String getTargetMailboxId() { return targetMailboxId; }
    public byte[] getNonceOrIv() { return nonceOrIv; }
    public int getPayloadLen() { return payloadLen; }
    public byte[] getEncryptedData() { return encryptedData; }
    public byte[] getAuthTag() { return authTag; }

    /**
     * 极客级防御：用完即毁
     * 手动将存储密文的数组全部覆写为 0，防范内存转储 (Memory Dump) 攻击
     */
    public void wipeData() {
        if (encryptedData != null) Arrays.fill(encryptedData, (byte) 0);
        if (nonceOrIv != null) Arrays.fill(nonceOrIv, (byte) 0);
        if (authTag != null) Arrays.fill(authTag, (byte) 0);
    }
}