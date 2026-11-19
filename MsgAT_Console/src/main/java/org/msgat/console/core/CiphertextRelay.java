// SPDX-License-Identifier: MIT
package org.msgat.console.core;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.PromiseCombiner;
import org.msgat.console.db.CiphertextMailboxStore;
import org.msgat.console.db.IdentityRevocations;
import org.msgat.console.model.GhostPacket;
import org.msgat.console.routing.BlindRoutingTable;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class CiphertextRelay {
    private static final AttributeKey<ChannelHandlerContext> DELIVERY_CONTEXT = AttributeKey.valueOf("V2_DELIVERY_CONTEXT");
    private static final AttributeKey<Replay> REPLAY = AttributeKey.valueOf("V2_REPLAY");
    private static final Object[] LOCKS = new Object[64];
    static { for (int index=0; index<LOCKS.length; index++) LOCKS[index]=new Object(); }
    private CiphertextRelay() {}

    static void handle(ChannelHandlerContext context, GhostPacket packet, BlindRoutingTable routes,
                       PendingAcknowledgements acknowledgements) {
        String self = context.channel().attr(AttributeKey.<String>valueOf("AUTHENTICATED_UUID")).get();
        if (self == null) { context.close(); return; }
        try {
            switch (packet.getType()) {
                case GhostPacket.TYPE_V2_SUBMIT -> {
                    String target = identity(packet.getTargetMailboxId());
                    if(org.msgat.console.db.IdentityRevocations.revoked(target)) {
                        String rejected=eventID(packet.getNonceOrIv());
                        byte[] proof=org.msgat.console.db.IdentityRevocations.proof(target);
                        if(proof==null) { context.close(); return; }
                        PacketWriter.write(context.channel(),new GhostPacket(GhostPacket.TYPE_V2_REJECTED,target,eventBytes(rejected),proof,null),true);
                        return;
                    }
                    if (self.equals(target)) throw new IllegalArgumentException("Saved messages are local");
                    String event = eventID(packet.getNonceOrIv());
                    synchronized (lock(target)) {
                        CiphertextMailboxStore.StoredEnvelope stored;
                        try {
                            stored = CiphertextMailboxStore.put(self, target, event, packet.getEncryptedData());
                        } catch (CiphertextMailboxStore.QuotaExceeded full) {
                            PacketWriter.write(context.channel(), new GhostPacket(GhostPacket.TYPE_V2_DEFERRED, target,
                                    eventBytes(event), null, null), true);
                            return;
                        }
                        // Acknowledgement means durable server storage, not recipient delivery.
                        PacketWriter.write(context.channel(), new GhostPacket(GhostPacket.TYPE_V2_STORED, target,
                                eventBytes(event), null, null), true);
                        if (stored.expiresAt() > Instant.now().getEpochSecond()) {
                            Channel recipient = routes.getChannel(IdentityAddresses.calculateMailboxId(target));
                            if (recipient != null && recipient.isActive())
                                notifyRecipient(recipient, routes, target, stored.cursor(), self, event, packet.getEncryptedData());
                        }
                    }
                }
                case GhostPacket.TYPE_V2_RECEIVED -> {
                    if (packet.getPayloadLen()!=0) throw new IllegalArgumentException("Invalid receive acknowledgement");
                    acknowledgements.add(context, self, new CiphertextMailboxStore.Acknowledgement(
                            identity(packet.getTargetMailboxId()), eventID(packet.getNonceOrIv())));
                }
                case GhostPacket.TYPE_V2_SYNC -> {
                    if (!self.equals(packet.getTargetMailboxId()) || packet.getPayloadLen()!=0) throw new IllegalArgumentException("Invalid sync request");
                    acknowledgements.flush();
                    Replay replay;
                    synchronized (lock(self)) {
                        replay = context.channel().attr(REPLAY).get();
                        if (replay == null) {
                            replay = new Replay(context, routes, self, 0);
                            context.channel().attr(DELIVERY_CONTEXT).set(context);
                            context.channel().attr(REPLAY).set(replay);
                        }
                        replay.requestFullSync(packet.getNonceOrIv());
                    }
                    replay.advance();
                }
                default -> context.close();
            }
        } catch (IllegalArgumentException | SQLException error) { context.close(); }
    }

    /** Owned by one authenticated control connection and its ordered application executor. */
    static final class PendingAcknowledgements {
        private static final int MAX_ACKNOWLEDGEMENTS = 32;
        private final LinkedHashSet<CiphertextMailboxStore.Acknowledgement> pending = new LinkedHashSet<>();
        private ScheduledFuture<?> timer;
        private String recipient;

        void add(ChannelHandlerContext context, String recipient, CiphertextMailboxStore.Acknowledgement acknowledgement) throws SQLException {
            this.recipient = recipient;
            if (pending.size() >= MAX_ACKNOWLEDGEMENTS) flush();
            if (!pending.add(acknowledgement)) return;
            if (pending.size() >= MAX_ACKNOWLEDGEMENTS) {
                flush();
            } else if (timer == null) {
                try {
                    timer = context.executor().schedule(() -> {
                        timer = null;
                        try (var active = IdentityRevocations.active(recipient)) { flush(); }
                        catch (SQLException | IllegalStateException error) { context.close(); }
                    }, 20, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException overloaded) { flush(); }
            }
        }

        void flush() throws SQLException {
            if (timer != null) { timer.cancel(false); timer = null; }
            if (pending.isEmpty()) return;
            CiphertextMailboxStore.acknowledgeBatch(recipient, pending);
            pending.clear();
        }

        void finish() {
            if (pending.isEmpty()) return;
            try (var active = IdentityRevocations.active(recipient)) { flush(); }
            catch (SQLException | IllegalStateException error) {
                // Failed deletes stay durable and can be replayed after reconnect.
            } finally {
                if (timer != null) { timer.cancel(false); timer = null; }
                pending.clear();
            }
        }
    }

    // The caller holds the recipient stripe, so a live write cannot overtake the end of a drain.
    private static void notifyRecipient(Channel channel, BlindRoutingTable routes, String identity, long storedCursor,
                                        String sender, String event, byte[] ciphertext) {
        if (!channel.hasAttr(DELIVERY_CONTEXT)) return;
        var context = channel.attr(DELIVERY_CONTEXT).get();
        if (context == null || channel.attr(REPLAY).get() != null) return;
        if (PacketWriter.canSendDelivery(channel)) {
            PacketWriter.write(channel, delivery(sender, event, ciphertext), true);
            return;
        }
        Replay replay = new Replay(context, routes, identity, storedCursor - 1);
        channel.attr(REPLAY).set(replay);
        replay.schedule(replay::advance);
    }

    static void resumeReplay(Channel channel) {
        if (!channel.hasAttr(REPLAY)) return;
        Replay replay = channel.attr(REPLAY).get();
        if (replay != null) replay.advance();
    }

    static void stopReplay(Channel channel) {
        if (channel.hasAttr(DELIVERY_CONTEXT)) channel.attr(DELIVERY_CONTEXT).set(null);
        if (channel.hasAttr(REPLAY)) channel.attr(REPLAY).set(null);
        PacketWriter.cancelReplayWait(channel);
    }

    /** All cursor changes run on the control connection's ordered application executor. */
    private static final class Replay {
        private static final int MAX_PENDING_SYNCS = 8;
        private record SyncBoundary(byte[] nonce, long through) {}

        private final ChannelHandlerContext context;
        private final BlindRoutingTable routes;
        private final String identity;
        private final String mailbox;
        private final ArrayDeque<SyncBoundary> pendingSyncs = new ArrayDeque<>();
        private long cursor;
        private boolean writing;
        private boolean waitingForBudget;
        private boolean restartFromBeginning;

        Replay(ChannelHandlerContext context, BlindRoutingTable routes, String identity, long after) {
            this.context = context;
            this.routes = routes;
            this.identity = identity;
            mailbox = IdentityAddresses.calculateMailboxId(identity);
            cursor = after;
        }

        // The recipient stripe covers admission and capture of the sync boundary.
        void requestFullSync(byte[] nonce) throws SQLException {
            if (pendingSyncs.stream().anyMatch(sync -> Arrays.equals(sync.nonce(), nonce))) return;
            if (pendingSyncs.size() >= MAX_PENDING_SYNCS) {
                context.close();
                return;
            }
            if (pendingSyncs.isEmpty()) restartFromBeginning = true;
            pendingSyncs.addLast(new SyncBoundary(nonce, CiphertextMailboxStore.lastCursor(identity)));
        }

        private boolean active() {
            Channel channel = context.channel();
            return channel.isActive() && channel.attr(REPLAY).get() == this && routes.getChannel(mailbox) == channel;
        }

        void advance() {
            Channel channel = context.channel();
            if (writing || waitingForBudget || !active() || !channel.isWritable()) return;
            waitingForBudget = true;
            if (PacketWriter.deferReplayIfBusy(channel, () -> schedule(() -> {
                waitingForBudget = false;
                advance();
            }))) return;
            waitingForBudget = false;

            try (var active = IdentityRevocations.active(identity)) {
                List<CiphertextMailboxStore.Envelope> batch;
                synchronized (lock(identity)) {
                    if (!active()) return;
                    if (restartFromBeginning) {
                        cursor = 0;
                        restartFromBeginning = false;
                    }
                    var sync = pendingSyncs.peekFirst();
                    batch = CiphertextMailboxStore.batch(identity, cursor, sync == null ? Long.MAX_VALUE : sync.through());
                    if (batch.isEmpty()) {
                        if (sync == null) {
                            channel.attr(REPLAY).compareAndSet(this, null);
                        } else {
                            pendingSyncs.removeFirst();
                            cursor = Math.max(cursor, sync.through());
                            PacketWriter.write(channel, new GhostPacket(GhostPacket.TYPE_V2_SYNC_END,
                                    identity, sync.nonce(), null, null), true);
                            schedule(this::advance);
                        }
                        return;
                    }
                }
                long now = Instant.now().getEpochSecond();
                var filtered = new ArrayList<CiphertextMailboxStore.Envelope>(batch.size());
                var revoked = new ArrayList<CiphertextMailboxStore.Acknowledgement>();
                var revokedBySender = new HashMap<String, Boolean>();
                for (var envelope : batch) {
                    if (envelope.expiresAt() <= now) continue;
                    String sender = envelope.sender();
                    Boolean senderRevoked = revokedBySender.get(sender);
                    if (senderRevoked == null) {
                        senderRevoked = IdentityRevocations.revoked(sender);
                        revokedBySender.put(sender, senderRevoked);
                    }
                    if (senderRevoked) {
                        revoked.add(new CiphertextMailboxStore.Acknowledgement(envelope.sender(), envelope.eventID()));
                    } else {
                        filtered.add(envelope);
                    }
                }
                CiphertextMailboxStore.acknowledgeBatch(identity, revoked);

                if (!active()) return;
                writing = true;
                long next = batch.get(batch.size() - 1).cursor();
                if (filtered.isEmpty()) { schedule(() -> wrote(next)); return; }
                var writes = new PromiseCombiner(context.executor());
                for (var envelope : filtered) {
                    writes.add(PacketWriter.write(channel,
                            delivery(envelope.sender(), envelope.eventID(), envelope.ciphertext()), false));
                }
                var completed = context.executor().<Void>newPromise();
                writes.finish(completed);
                channel.flush();
                completed.addListener(done -> {
                    if (done.isSuccess()) schedule(() -> wrote(next));
                    else context.close();
                });
            } catch (SQLException | IllegalStateException error) { context.close(); }
        }

        private void wrote(long next) {
            cursor = next;
            writing = false;
            advance();
        }

        private void schedule(Runnable continuation) {
            if (!context.channel().isActive()) return;
            try { context.executor().execute(continuation); }
            catch (RejectedExecutionException overloaded) { context.close(); }
        }
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
