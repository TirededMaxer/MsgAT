package org.msgat.core.network

import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertThrows
import io.netty.handler.codec.EncoderException
import org.msgat.model.GhostPacket

class GhostPacketCodecTest {
    @Test
    fun roundTripPreservesPacket() {
        val packet = GhostPacket(0x03, "a".repeat(64), ByteArray(16) { 1 }, "hello".toByteArray(), ByteArray(16) { 2 })
        val encoder = EmbeddedChannel(GhostPacketCodec())
        val decoder = EmbeddedChannel(GhostPacketCodec())

        encoder.writeOutbound(packet)
        val encoded = encoder.readOutbound<ByteBuf>()
        decoder.writeInbound(encoded)
        val decoded = decoder.readInbound<GhostPacket>()

        assertEquals(packet.type, decoded.type)
        assertEquals(packet.targetMailboxId, decoded.targetMailboxId)
        assertArrayEquals(packet.encryptedData, decoded.encryptedData)
        assertArrayEquals(packet.authTag, decoded.authTag)
        encoder.finishAndReleaseAll()
        decoder.finishAndReleaseAll()
    }

    @Test
    fun rejectsInvalidFrameLength() {
        val channel = EmbeddedChannel(GhostPacketCodec())
        channel.writeInbound(channel.alloc().buffer(4).writeInt(-1))
        assertFalse(channel.isOpen)
        channel.finishAndReleaseAll()
    }

    @Test
    fun rejectsOversizedOutboundFrame() {
        val channel = EmbeddedChannel(GhostPacketCodec())
        val packet = GhostPacket(0x03, "a".repeat(64), ByteArray(16), ByteArray(262_040), ByteArray(16))
        assertThrows(EncoderException::class.java) { channel.writeOutbound(packet) }
        channel.finishAndReleaseAll()
    }
}
