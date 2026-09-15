package org.msgat.core.network

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import io.netty.handler.codec.EncoderException
import org.msgat.model.GhostPacket
import java.util.concurrent.ThreadLocalRandom

class GhostPacketCodec : ByteToMessageCodec<GhostPacket>() {
    private companion object {
        const val MIN_FRAME_SIZE = 105
        const val MAX_FRAME_SIZE = 256 * 1024
    }
    // Calculates the dynamic byte boundary size used for packet framing.
    private fun getBoundarySize(actualSize: Int): Int {
        val boundaries = intArrayOf(4096)
        for (b in boundaries) {
            if (actualSize <= b) return b
        }
        return actualSize
    }

    // Serializes a GhostPacket into a structured byte buffer for network transmission.
    override fun encode(ctx: ChannelHandlerContext, msg: GhostPacket, out: ByteBuf) {
        if (msg.nonceOrIv != null && msg.nonceOrIv.size != 16 || msg.authTag != null && msg.authTag.size != 16) {
            ctx.close()
            throw EncoderException("Nonce/IV and authentication tag must be exactly 16 bytes")
        }
        val payloadLen = msg.payloadLen
        val actualSize = 4 + 1 + 64 + 16 + 4 + payloadLen + 16
        val paddedSize = getBoundarySize(actualSize)
        if (paddedSize !in MIN_FRAME_SIZE..MAX_FRAME_SIZE) {
            ctx.close()
            throw EncoderException("Encoded packet exceeds protocol limits: $paddedSize")
        }

        out.writeInt(paddedSize)

        out.writeByte(msg.type.toInt())

        val mailboxBytes = msg.targetMailboxId?.toByteArray(Charsets.UTF_8) ?: ByteArray(64)
        if (mailboxBytes.size > 64) {
            ctx.close()
            throw EncoderException("Mailbox ID exceeds the 64-byte protocol field")
        }
        out.writeBytes(mailboxBytes.copyOf(64))

        out.writeBytes(msg.nonceOrIv ?: ByteArray(16))
        out.writeInt(payloadLen)

        if (msg.encryptedData != null) {
            out.writeBytes(msg.encryptedData)
        }

        if (msg.authTag != null) {
            out.writeBytes(msg.authTag)
        } else {
            out.writeZero(16)
        }

        val paddingSize = paddedSize - actualSize

        if (paddingSize > 0) {
            val padding = ByteArray(paddingSize)
            ThreadLocalRandom.current().nextBytes(padding)
            out.writeBytes(padding)
        }
    }

    // Parses an incoming byte stream to reconstruct a valid GhostPacket object.
    override fun decode(ctx: ChannelHandlerContext, `in`: ByteBuf, out: MutableList<Any>) {
        `in`.markReaderIndex()
        if (`in`.readableBytes() < 4) {
            return
        }
        val totalLen = `in`.readInt()
        if (totalLen !in MIN_FRAME_SIZE..MAX_FRAME_SIZE) {
            ctx.close()
            return
        }
        if (`in`.readableBytes() < totalLen - 4) {
            `in`.resetReaderIndex()
            return
        }

        val type = `in`.readByte()

        val mailboxBytes = ByteArray(64)
        `in`.readBytes(mailboxBytes)
        val targetMailboxId = String(mailboxBytes, Charsets.UTF_8).trimEnd('\u0000')

        val nonceOrIv = ByteArray(16)
        `in`.readBytes(nonceOrIv)

        val payloadLen = `in`.readInt()
        if (payloadLen > totalLen - 105 || payloadLen < 0) {
            ctx.close()
            return
        }

        val encryptedData = ByteArray(payloadLen)
        `in`.readBytes(encryptedData)

        val authTag = ByteArray(16)
        `in`.readBytes(authTag)

        val actualSize = 4 + 1 + 64 + 16 + 4 + payloadLen + 16
        val paddingSize = totalLen - actualSize
        if (paddingSize > 0) {
            `in`.skipBytes(paddingSize)
        }

        out.add(GhostPacket(type, targetMailboxId, nonceOrIv, encryptedData, authTag))
    }
}
