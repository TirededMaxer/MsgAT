package org.msgat.console.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.EncoderException;
import org.msgat.console.model.GhostPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class GhostPacketCodec extends ByteToMessageCodec<GhostPacket> {
    private static final Logger log = LoggerFactory.getLogger(GhostPacketCodec.class);
    private static final int MIN_FRAME_SIZE = 105;
    private static final int MAX_FRAME_SIZE = 256 * 1024;

    // Determines the appropriate padded boundary size for packet obfuscation.
    private int getBoundarySize(int actualSize) {
        int[] boundaries = {4096};
        for (int b : boundaries) {
            if (actualSize <= b) return b;
        }
        return actualSize;
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
        int actualSize = 4 + 1 + 64 + 16 + 4 + payloadLen + 16;
        int paddedSize = getBoundarySize(actualSize);
        if (paddedSize < MIN_FRAME_SIZE || paddedSize > MAX_FRAME_SIZE) {
            ctx.close();
            throw new EncoderException("Encoded packet exceeds protocol limits: " + paddedSize);
        }

        out.writeInt(paddedSize);

        out.writeByte(msg.getType());

        byte[] mailboxBytes = msg.getTargetMailboxId() != null ?
                msg.getTargetMailboxId().getBytes(StandardCharsets.UTF_8) : new byte[64];
        if (mailboxBytes.length > 64) {
            ctx.close();
            throw new EncoderException("Mailbox ID exceeds the 64-byte protocol field");
        }
        out.writeBytes(Arrays.copyOf(mailboxBytes, 64));

        out.writeBytes(msg.getNonceOrIv() != null ? msg.getNonceOrIv() : new byte[16]);
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
        if (paddingSize > 0) {
            byte[] padding = new byte[paddingSize];
            ThreadLocalRandom.current().nextBytes(padding);
            out.writeBytes(padding);
        }
    }

    // Decodes incoming bytes from a ByteBuf into a GhostPacket object and skips trailing random padding.
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        if (in.readableBytes() < 4) {
            return;
        }
        int totalLen = in.readInt();
        if (totalLen < MIN_FRAME_SIZE || totalLen > MAX_FRAME_SIZE) {
            log.warn("Rejected invalid frame length: {}", totalLen);
            ctx.close();
            return;
        }
        if (in.readableBytes() < totalLen - 4) {
            in.resetReaderIndex();
            return;
        }

        byte type = in.readByte();

        byte[] mailboxBytes = new byte[64];
        in.readBytes(mailboxBytes);
        String targetMailboxId = new String(mailboxBytes, StandardCharsets.UTF_8).replace("\0", "");

        byte[] nonceOrIv = new byte[16];
        in.readBytes(nonceOrIv);

        int payloadLen = in.readInt();

        if (payloadLen > totalLen - 105 || payloadLen < 0) {
            ctx.close();
            return;
        }

        byte[] encryptedData = new byte[payloadLen];
        in.readBytes(encryptedData);

        byte[] authTag = new byte[16];
        in.readBytes(authTag);

        int actualSize = 4 + 1 + 64 + 16 + 4 + payloadLen + 16;
        int paddingSize = totalLen - actualSize;
        in.skipBytes(paddingSize);

        GhostPacket packet = new GhostPacket(type, targetMailboxId, nonceOrIv, encryptedData, authTag);
        out.add(packet);
    }
}
