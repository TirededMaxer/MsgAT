package org.msgat.console.core;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import org.msgat.console.model.GhostPacket;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Account for queued writes before they cross to the I/O thread. */
public final class PacketWriter {
    private static final AttributeKey<AtomicLong> PENDING = AttributeKey.valueOf("MSGAT_PENDING_BYTES");
    private static final long MAX_PENDING_BYTES = 4L * 1024 * 1024;

    private PacketWriter() { }

    public static ChannelFuture write(Channel channel, GhostPacket packet, boolean flush) {
        AtomicLong pending = channel.attr(PENDING).get();
        if (pending == null) {
            AtomicLong created = new AtomicLong();
            AtomicLong existing = channel.attr(PENDING).setIfAbsent(created);
            pending = existing == null ? created : existing;
        }
        int bytes = Math.max(4096, 105 + packet.getPayloadLen());
        AtomicLong counter = pending;
        if (counter.addAndGet(bytes) > MAX_PENDING_BYTES) {
            counter.addAndGet(-bytes);
            // Durable packets remain in SQLite for the client's next connection.
            channel.close();
            return channel.newFailedFuture(new IOException("Slow connection exceeded pending write limit"));
        }
        var promise = channel.newPromise();
        promise.addListener(done -> {
            counter.addAndGet(-bytes);
            if (!done.isSuccess()) channel.close();
        });
        try {
            return flush ? channel.writeAndFlush(packet, promise) : channel.write(packet, promise);
        } catch (RuntimeException error) {
            promise.tryFailure(error);
            return promise;
        }
    }
}
