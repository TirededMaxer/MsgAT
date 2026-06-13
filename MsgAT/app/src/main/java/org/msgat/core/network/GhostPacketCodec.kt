package org.msgat.core.network

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import org.msgat.model.GhostPacket
import java.security.SecureRandom

class GhostPacketCodec : ByteToMessageCodec<GhostPacket>() {
    private val secureRandom = SecureRandom()

    override fun encode(ctx: ChannelHandlerContext, msg: GhostPacket, out: ByteBuf) {
        val startIdx = out.writerIndex()

        out.writeByte(msg.type.toInt())

        val mailboxBytes = msg.targetMailboxId?.toByteArray(Charsets.UTF_8) ?: ByteArray(64)
        out.writeBytes(mailboxBytes.copyOf(64)) // 确保严格 64 字节

        out.writeBytes(msg.nonceOrIv ?: ByteArray(16))
        out.writeInt(msg.payloadLen)

        if (msg.encryptedData != null) {
            out.writeBytes(msg.encryptedData)
        }

        if (msg.authTag != null) {
            out.writeBytes(msg.authTag)
        }

        val writtenSoFar = out.writerIndex() - startIdx
        val paddingSize = GhostPacket.PACKET_SIZE - writtenSoFar

        if (paddingSize > 0) {
            val padding = ByteArray(paddingSize)
            secureRandom.nextBytes(padding)
            out.writeBytes(padding)
        }
    }

    override fun decode(ctx: ChannelHandlerContext, `in`: ByteBuf, out: MutableList<Any>) {
        if (`in`.readableBytes() < GhostPacket.PACKET_SIZE) {
            return // 等待 Netty 攒够 500KB
        }

        val type = `in`.readByte()

        val mailboxBytes = ByteArray(64)
        `in`.readBytes(mailboxBytes)
        val targetMailboxId = String(mailboxBytes, Charsets.UTF_8).trimEnd('\u0000')

        val nonceOrIv = ByteArray(16)
        `in`.readBytes(nonceOrIv)

        val payloadLen = `in`.readInt()
        if (payloadLen > GhostPacket.PACKET_SIZE - 100 || payloadLen < 0) {
            ctx.close() // 抛弃畸形包
            return
        }

        val encryptedData = ByteArray(payloadLen)
        `in`.readBytes(encryptedData)

        val authTag = ByteArray(16)
        `in`.readBytes(authTag)

        // 丢弃剩余的噪音
        val remaining = GhostPacket.PACKET_SIZE - (1 + 64 + 16 + 4 + payloadLen + 16)
        if (remaining > 0) {
            `in`.skipBytes(remaining)
        }

        out.add(GhostPacket(type, targetMailboxId, nonceOrIv, encryptedData, authTag))
    }
}