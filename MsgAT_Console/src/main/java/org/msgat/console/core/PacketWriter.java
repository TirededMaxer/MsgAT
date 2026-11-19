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
    private static final AttributeKey<Runnable> REPLAY_RESUME = AttributeKey.valueOf("MSGAT_REPLAY_RESUME");
    private static final long MAX_PENDING_BYTES = 4L * 1024 * 1024;
    // Leave room for eight maximum-size replay frames and concurrent control replies.
    private static final long REPLAY_LOW_WATER = 1024L * 1024;

    private PacketWriter() { }

    static boolean canSendDelivery(Channel channel) {
        if (!channel.isWritable()) return false;
        AtomicLong pending = channel.attr(PENDING).get();
        return pending == null || pending.get() <= REPLAY_LOW_WATER;
    }

    static boolean deferReplayIfBusy(Channel channel, Runnable resume) {
        AtomicLong pending = channel.attr(PENDING).get();
        if (pending == null || pending.get() <= REPLAY_LOW_WATER) return false;
        channel.attr(REPLAY_RESUME).set(resume);
        if (pending.get() <= REPLAY_LOW_WATER) resumeReplay(channel);
        return true;
    }

    static void cancelReplayWait(Channel channel) {
        if (channel.hasAttr(REPLAY_RESUME)) channel.attr(REPLAY_RESUME).set(null);
    }

    private static void resumeReplay(Channel channel) {
        Runnable resume = channel.attr(REPLAY_RESUME).getAndSet(null);
        if (resume != null && channel.isActive()) resume.run();
    }

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
            long remaining = counter.addAndGet(-bytes);
            if (!done.isSuccess()) channel.close();
            else if (remaining <= REPLAY_LOW_WATER && channel.hasAttr(REPLAY_RESUME)) resumeReplay(channel);
        });
        try {
            return flush ? channel.writeAndFlush(packet, promise) : channel.write(packet, promise);
        } catch (RuntimeException error) {
            promise.tryFailure(error);
            return promise;
        }
    }
}
