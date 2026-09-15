package org.msgat.console.core;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;
import org.msgat.console.model.GhostPacket;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class GhostPacketCodecTest {
    @Test
    void rejectsOversizedOutboundFrame() {
        var channel = new EmbeddedChannel(new GhostPacketCodec());
        var packet = new GhostPacket((byte) 0x03, "a".repeat(64), new byte[16], new byte[262_040], new byte[16]);
        assertThrows(EncoderException.class, () -> channel.writeOutbound(packet));
        channel.finishAndReleaseAll();
    }
}
