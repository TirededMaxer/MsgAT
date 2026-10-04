package org.msgat.console.core;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.RejectedExecutionException;

/** A saturated database queue must close the source, rather than block the network thread. */
public final class ApplicationQueueGuard extends ChannelInboundHandlerAdapter {
    private int pending;
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (++pending >= 8) ctx.channel().config().setAutoRead(false);
        try {
            ctx.fireChannelRead(message);
        } catch (RejectedExecutionException overloaded) {
            io.netty.util.ReferenceCountUtil.release(message);
            ctx.close();
        }
    }

    public static void completed(io.netty.channel.Channel channel) {
        channel.eventLoop().execute(() -> {
            ApplicationQueueGuard guard = channel.pipeline().get(ApplicationQueueGuard.class);
            if (guard != null && --guard.pending <= 4 && channel.isActive())
                channel.config().setAutoRead(true);
        });
    }
}
