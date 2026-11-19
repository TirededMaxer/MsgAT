package org.msgat.console.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.EncoderException;
import org.msgat.console.model.GhostPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class GhostPacketCodec extends ByteToMessageCodec<GhostPacket> {
    private static final Logger log = LoggerFactory.getLogger(GhostPacketCodec.class);
    private static final int PAYLOAD_LENGTH_OFFSET = Integer.BYTES + 1 + 64 + 16;
    private static final int HEADER_SIZE = PAYLOAD_LENGTH_OFFSET + Integer.BYTES;
    private static final int MIN_FRAME_SIZE = HEADER_SIZE + 16;
    static final int MIN_INBOUND_LIMIT = 128 * 1024;
    static final int MAX_FRAME_SIZE = 256 * 1024;
    private final int maxInboundFrameBytes;
    private boolean rejected;

    public GhostPacketCodec() {
        this(MAX_FRAME_SIZE);
    }

    GhostPacketCodec(int maxInboundFrameBytes) {
        if (maxInboundFrameBytes < MIN_INBOUND_LIMIT || maxInboundFrameBytes > MAX_FRAME_SIZE)
            throw new IllegalArgumentException("Invalid inbound frame limit");
        this.maxInboundFrameBytes = maxInboundFrameBytes;
    }

    // Encodes a GhostPacket into a ByteBuf, applying random padding for fixed-size transmission.
    @Override
    protected void encode(ChannelHandlerContext ctx, GhostPacket msg, ByteBuf out) {
        if (msg.getNonceOrIv() != null && msg.getNonceOrIv().length != 16 ||
                msg.getAuthTag() != null && msg.getAuthTag().length != 16) {
            ctx.close();
            throw new EncoderException("Nonce/IV and authentication tag must be exactly 16 bytes");
        }
        int payloadLen = msg.getPayloadLen();
        if (payloadLen < 0 || payloadLen > MAX_FRAME_SIZE - MIN_FRAME_SIZE) {
            ctx.close();
            throw new EncoderException("Encoded packet exceeds protocol limits");
        }
        int actualSize = MIN_FRAME_SIZE + payloadLen;
        int paddedSize = Math.max(4096, actualSize);

        out.writeInt(paddedSize);

        out.writeByte(msg.getType());

        byte[] mailboxBytes = msg.getTargetMailboxId() != null ?
                msg.getTargetMailboxId().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (mailboxBytes.length > 64) {
            ctx.close();
            throw new EncoderException("Mailbox ID exceeds the 64-byte protocol field");
        }
        out.writeBytes(mailboxBytes);
        out.writeZero(64 - mailboxBytes.length);

        if (msg.getNonceOrIv() != null) out.writeBytes(msg.getNonceOrIv()); else out.writeZero(16);
        out.writeInt(payloadLen);

        if (msg.getEncryptedData() != null) {
            out.writeBytes(msg.getEncryptedData());
        }

        if (msg.getAuthTag() != null) {
            out.writeBytes(msg.getAuthTag());
        } else {
            out.writeZero(16);
        }

        int paddingSize = paddedSize - actualSize;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (paddingSize >= Long.BYTES) { out.writeLong(random.nextLong()); paddingSize -= Long.BYTES; }
        while (paddingSize-- > 0) out.writeByte(random.nextInt(256));
    }

    // Decodes incoming bytes from a ByteBuf into a GhostPacket object and skips trailing random padding.
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (rejected) {
            in.skipBytes(in.readableBytes());
            return;
        }
        int readable = in.readableBytes();
        if (readable < Integer.BYTES) return;
        int start = in.readerIndex();
        int totalLen = in.getInt(start);
        if (totalLen < MIN_FRAME_SIZE || totalLen > maxInboundFrameBytes) {
            reject(ctx, in);
            return;
        }
        if (readable < HEADER_SIZE) return;
        int payloadLen = in.getInt(start + PAYLOAD_LENGTH_OFFSET);
        if (payloadLen < 0 || payloadLen > totalLen - MIN_FRAME_SIZE) {
            reject(ctx, in);
            return;
        }
        if (readable < totalLen) return;

        in.skipBytes(Integer.BYTES);
        byte type = in.readByte();

        byte[] mailboxBytes = new byte[64];
        in.readBytes(mailboxBytes);
        String targetMailboxId = new String(mailboxBytes, StandardCharsets.UTF_8).replace("\0", "");

        byte[] nonceOrIv = new byte[16];
        in.readBytes(nonceOrIv);

        in.skipBytes(Integer.BYTES);

        byte[] encryptedData = new byte[payloadLen];
        in.readBytes(encryptedData);

        byte[] authTag = new byte[16];
        in.readBytes(authTag);

        in.skipBytes(totalLen - MIN_FRAME_SIZE - payloadLen);

        GhostPacket packet = new GhostPacket(type, targetMailboxId, nonceOrIv, encryptedData, authTag);
        out.add(packet);
    }

    private void reject(ChannelHandlerContext ctx, ByteBuf in) {
        rejected = true;
        in.skipBytes(in.readableBytes());
        log.debug("Rejected invalid inbound frame");
        ctx.close();
    }
}
