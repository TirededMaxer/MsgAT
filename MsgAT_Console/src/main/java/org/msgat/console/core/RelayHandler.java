// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.msgat.console.model.GhostPacket;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** V2 control traffic only. Profiles/content/calls are never parsed by the relay. */
public final class RelayHandler extends SimpleChannelInboundHandler<GhostPacket> {
    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);
    private final BlindRoutingTable routingTable;
    private final AndroidPushRelay androidPush;
    private final CiphertextRelay.PendingAcknowledgements acknowledgements = new CiphertextRelay.PendingAcknowledgements();
    public RelayHandler(BlindRoutingTable routingTable, AndroidPushRelay androidPush) {
        this.routingTable = routingTable;
        this.androidPush = androidPush;
    }
    @Override
    protected void channelRead0(ChannelHandlerContext context, GhostPacket packet) {
        String mailbox = context.channel().attr(AttributeKey.<String>valueOf("MAILBOX_ID")).get();
        if (mailbox == null || routingTable.getChannel(mailbox) != context.channel()) { context.close(); return; }
        String identity=context.channel().attr(AttributeKey.<String>valueOf("AUTHENTICATED_UUID")).get();
        try(var active=org.msgat.console.db.IdentityRevocations.active(identity)) {
        switch (packet.getType()) {
            case GhostPacket.TYPE_V2_SUBMIT, GhostPacket.TYPE_V2_RECEIVED, GhostPacket.TYPE_V2_SYNC ->
                CiphertextRelay.handle(context, packet, routingTable, acknowledgements);
            case GhostPacket.TYPE_V2_KEY_PUBLISH, GhostPacket.TYPE_V2_KEY_CLAIM ->
                KeyDirectoryHandler.handle(context, packet);
            case GhostPacket.TYPE_V2_PUSH_UPDATE -> {
                if (androidPush == null) { context.close(); return; }
                androidPush.handle(context, identity, packet);
            }
            case GhostPacket.TYPE_HEARTBEAT -> {
                if (packet.getPayloadLen() != 0) { context.close(); return; }
                PacketWriter.write(context.channel(), new GhostPacket(GhostPacket.TYPE_HEARTBEAT,
                        null, packet.getNonceOrIv(), new byte[0], null), true);
            }
            default -> context.close(); // Never acknowledge a legacy account/data operation as successful.
        }
        } catch(java.sql.SQLException | IllegalStateException error) { context.close(); }
    }
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext context) {
        if (context.channel().isWritable()) CiphertextRelay.resumeReplay(context.channel());
        context.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        CiphertextRelay.stopReplay(context.channel());
        acknowledgements.finish();
        context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        log.debug("Control connection closed ({})", cause.getClass().getSimpleName());
        context.close();
    }
}
