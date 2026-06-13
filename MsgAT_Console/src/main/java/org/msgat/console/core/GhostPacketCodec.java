package org.msgat.console.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import org.msgat.console.model.GhostPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

/**
 * 500KB 定长数据包装甲：负责粘包/拆包与随机噪音填充
 */
public class GhostPacketCodec extends ByteToMessageCodec<GhostPacket> {
    private static final Logger log = LoggerFactory.getLogger(GhostPacketCodec.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    protected void encode(ChannelHandlerContext ctx, GhostPacket msg, ByteBuf out) {
        // 1. 写入真实数据头
        out.writeByte(msg.getType());

        byte[] mailboxBytes = msg.getTargetMailboxId() != null ?
                msg.getTargetMailboxId().getBytes(StandardCharsets.UTF_8) : new byte[64];
        out.writeBytes(mailboxBytes); // 64 bytes (Hex string)

        out.writeBytes(msg.getNonceOrIv() != null ? msg.getNonceOrIv() : new byte[16]);
        out.writeInt(msg.getPayloadLen());

        if (msg.getEncryptedData() != null) {
            out.writeBytes(msg.getEncryptedData());
        }

        if (msg.getAuthTag() != null) {
            out.writeBytes(msg.getAuthTag());
        } else {
            out.writeZero(16);
        }

        // 2. 计算剩余空间，填入随机垃圾数据 (Padding)
        int currentWriterIndex = out.writerIndex();
        int paddingSize = GhostPacket.PACKET_SIZE - currentWriterIndex;

        if (paddingSize > 0) {
            byte[] padding = new byte[paddingSize];
            // 使用安全的随机数生成器填充，防止被压缩算法或流量分析工具识破
            SECURE_RANDOM.nextBytes(padding);
            out.writeBytes(padding);
        } else if (paddingSize < 0) {
            log.error("[CODEC] 致命错误：数据包超出 500KB 上限！");
            ctx.close(); // 强行断开连接
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 1. 防御机制：不够 500KB 坚决不读 (Netty 会自动帮我们在缓冲区里攒着)
        if (in.readableBytes() < GhostPacket.PACKET_SIZE) {
            return;
        }

        // 2. 开始解析真实的头部数据
        byte type = in.readByte();

        byte[] mailboxBytes = new byte[64];
        in.readBytes(mailboxBytes);
        String targetMailboxId = new String(mailboxBytes, StandardCharsets.UTF_8);

        byte[] nonceOrIv = new byte[16];
        in.readBytes(nonceOrIv);

        int payloadLen = in.readInt();

        // 3. 安全校验：如果声明的长度荒谬（比如大于 500KB），直接拉黑这个连接
        if (payloadLen > GhostPacket.PACKET_SIZE - 100 || payloadLen < 0) {
            log.warn("[CODEC] 侦测到恶意畸形包，正在断开连接...");
            ctx.close();
            return;
        }

        byte[] encryptedData = new byte[payloadLen];
        in.readBytes(encryptedData);

        byte[] authTag = new byte[16];
        in.readBytes(authTag);

        // 4. 读取并丢弃剩下的所有随机垃圾数据
        int paddingSize = GhostPacket.PACKET_SIZE - in.readerIndex();
        in.skipBytes(paddingSize);

        // 5. 组装成纯净的对象，传递给下一个 Handler
        GhostPacket packet = new GhostPacket(type, targetMailboxId, nonceOrIv, encryptedData, authTag);
        out.add(packet);
    }
}