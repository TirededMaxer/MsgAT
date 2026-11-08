// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.msgat.console.db.CiphertextMailboxStore;
import org.msgat.console.model.GhostPacket;
import org.msgat.console.routing.BlindRoutingTable;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.UUID;

final class CiphertextRelay {
    private static final AttributeKey<Boolean> READY = AttributeKey.valueOf("V2_READY");
    private static final AttributeKey<Boolean> REPLAYING = AttributeKey.valueOf("V2_REPLAYING");
    private static final Object[] LOCKS = new Object[64];
    static { for (int index=0; index<LOCKS.length; index++) LOCKS[index]=new Object(); }
    private CiphertextRelay() {}

    static void handle(ChannelHandlerContext context, GhostPacket packet, BlindRoutingTable routes) {
        String self = context.channel().attr(AttributeKey.<String>valueOf("AUTHENTICATED_UUID")).get();
        if (self == null) { context.close(); return; }
        try {
            switch (packet.getType()) {
                case GhostPacket.TYPE_V2_SUBMIT -> {
                    String target = identity(packet.getTargetMailboxId());
                    if (self.equals(target)) throw new IllegalArgumentException("Saved messages are local");
                    String event = eventID(packet.getNonceOrIv());
                    synchronized (lock(target)) {
                        CiphertextMailboxStore.put(self, target, event, packet.getEncryptedData());
                        // Acknowledgement means durable server storage, not recipient delivery.
                        PacketWriter.write(context.channel(), new GhostPacket(GhostPacket.TYPE_V2_STORED, target,
                                eventBytes(event), null, null), true);
                        Channel recipient = routes.getChannel(CryptoUtils.calculateMailboxId(target));
                        if (recipient != null && recipient.isActive() && Boolean.TRUE.equals(recipient.attr(READY).get())
                                && !Boolean.TRUE.equals(recipient.attr(REPLAYING).get())) {
                            PacketWriter.write(recipient, delivery(self, event, packet.getEncryptedData()), true);
                        }
                    }
                }
                case GhostPacket.TYPE_V2_RECEIVED -> {
                    if (packet.getPayloadLen()!=0) throw new IllegalArgumentException("Invalid receive acknowledgement");
                    CiphertextMailboxStore.acknowledge(self, identity(packet.getTargetMailboxId()), eventID(packet.getNonceOrIv()));
                }
                case GhostPacket.TYPE_V2_SYNC -> {
                    if (!self.equals(packet.getTargetMailboxId()) || packet.getPayloadLen()!=0) throw new IllegalArgumentException("Invalid sync request");
                    synchronized (lock(self)) {
                        if (Boolean.TRUE.equals(context.channel().attr(REPLAYING).get())) return;
                        context.channel().attr(READY).set(true);
                        context.channel().attr(REPLAYING).set(true);
                    }
                    drain(context, routes, self, 0);
                }
                default -> context.close();
            }
        } catch (IllegalArgumentException | SQLException error) { context.close(); }
    }

    private static void drain(ChannelHandlerContext context, BlindRoutingTable routes, String self, long after) {
        if (!context.channel().isActive() || routes.getChannel(CryptoUtils.calculateMailboxId(self))!=context.channel()) return;
        try {
            java.util.List<CiphertextMailboxStore.Envelope> batch;
            synchronized (lock(self)) {
                batch=CiphertextMailboxStore.batch(self, after);
                if (batch.isEmpty()) {
                    context.channel().attr(REPLAYING).set(false);
                    PacketWriter.write(context.channel(), new GhostPacket(GhostPacket.TYPE_V2_SYNC_END,self,null,null,null),true);
                    return;
                }
            }
            ChannelFuture last=null;
            long cursor=after;
            for (var envelope : batch) {
                last=PacketWriter.write(context.channel(),delivery(envelope.sender(),envelope.eventID(),envelope.ciphertext()),false);
                cursor=envelope.cursor();
            }
            context.channel().flush();
            long next=cursor;
            last.addListener(done -> {
                if (!done.isSuccess()) { context.close(); return; }
                try { context.executor().execute(() -> drain(context,routes,self,next)); }
                catch (java.util.concurrent.RejectedExecutionException overloaded) { context.close(); }
            });
        } catch (SQLException error) { context.close(); }
    }

    private static GhostPacket delivery(String sender, String event, byte[] ciphertext) {
        return new GhostPacket(GhostPacket.TYPE_V2_DELIVERY,sender,eventBytes(event),ciphertext,null);
    }
    private static String identity(String uuid) {
        if (uuid==null || !uuid.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid identity");
        return uuid;
    }
    private static String eventID(byte[] bytes) {
        if (bytes==null || bytes.length!=16) throw new IllegalArgumentException("Invalid event identifier");
        ByteBuffer buffer=ByteBuffer.wrap(bytes);
        UUID uuid=new UUID(buffer.getLong(),buffer.getLong());
        if (uuid.version()!=4 || uuid.variant()!=2) throw new IllegalArgumentException("Expected random event identifier");
        return uuid.toString();
    }
    private static byte[] eventBytes(String event) {
        UUID uuid=UUID.fromString(event);
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
    private static Object lock(String uuid) { return LOCKS[uuid.hashCode() & (LOCKS.length-1)]; }
}
