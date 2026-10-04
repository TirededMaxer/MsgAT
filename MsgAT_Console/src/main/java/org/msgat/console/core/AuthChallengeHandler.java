package org.msgat.console.core;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.msgat.console.model.GhostPacket;
import org.msgat.console.routing.BlindRoutingTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class AuthChallengeHandler extends SimpleChannelInboundHandler<GhostPacket> {
    private static final Logger log = LoggerFactory.getLogger(AuthChallengeHandler.class);
    private final BlindRoutingTable routingTable;

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private long replayCursor;
    private boolean isAuthenticated = false;
    private byte[] currentChallengeNonce = null;
    private String pendingMailboxId = null;

    // Constructor to initialize the handler with a routing table instance.
    public AuthChallengeHandler(BlindRoutingTable routingTable) {
        this.routingTable = routingTable;
    }

    // Intercepts packets for authentication; passes through if already authenticated, otherwise handles challenge-response.
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GhostPacket packet) {
        try {
            if (isAuthenticated) {
                ctx.fireChannelRead(packet);
                return;
            }

            if (packet.getType() == GhostPacket.TYPE_AUTH_REQ) {
                handleAuthRequest(ctx, packet);
            } else if (packet.getType() == GhostPacket.TYPE_AUTH_RESP) {
                handleAuthResponse(ctx, packet);
            } else {
                ctx.close();
            }
        } finally { ApplicationQueueGuard.completed(ctx.channel()); }
    }

    // Handles incoming authentication request by generating and sending a challenge nonce to the client.
    private void handleAuthRequest(ChannelHandlerContext ctx, GhostPacket packet) {
        this.pendingMailboxId = packet.getTargetMailboxId();
        this.currentChallengeNonce = new byte[16];
        RANDOM.nextBytes(currentChallengeNonce);

        GhostPacket challenge = new GhostPacket(
                GhostPacket.TYPE_AUTH_CHAL, this.pendingMailboxId, this.currentChallengeNonce, null, null
        );
        PacketWriter.write(ctx.channel(), challenge, true);
    }

    // Verifies the client's signed authentication response; if valid, registers the client and pushes offline messages.
    private void handleAuthResponse(ChannelHandlerContext ctx, GhostPacket packet) {
        byte[] payload = packet.getEncryptedData();
        if (currentChallengeNonce == null || pendingMailboxId == null || payload == null || payload.length != 96) {
            ctx.close();
            return;
        }

        byte[] publicKey = Arrays.copyOfRange(payload, 0, 32);
        byte[] signature = Arrays.copyOfRange(payload, 32, 96);

        String uuid = CryptoUtils.identityUuid(publicKey);
        if (pendingMailboxId.equals(CryptoUtils.calculateMailboxId(uuid))
                && verifySignature(publicKey, currentChallengeNonce, signature)) {
            this.isAuthenticated = true;
            synchronized (routingTable) {
                routingTable.register(this.pendingMailboxId, ctx.channel());
                ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("MAILBOX_ID")).set(this.pendingMailboxId);

                routingTable.beginReplay(this.pendingMailboxId);
                // Cleanup is independent of a potentially saturated application executor.
                String mailbox = this.pendingMailboxId;
                ctx.channel().closeFuture().addListener(done -> routingTable.remove(mailbox, ctx.channel()));
            }
            currentChallengeNonce = null;
            drainOffline(ctx);
        } else {
            ctx.close();
            return;
        }

    }

    // Read at most 8 packets, flush together, and wait for socket completion before reading more.
    private void drainOffline(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive() || routingTable.getChannel(pendingMailboxId) != ctx.channel()) return;
        try {
            org.msgat.console.routing.BlindRoutingTable.OfflineBatch batch;
            synchronized (routingTable) {
                if (!ctx.channel().isActive() || routingTable.getChannel(pendingMailboxId) != ctx.channel()) return;
                batch = routingTable.retrieveOfflineBatch(pendingMailboxId, replayCursor);
                if (batch.packets().isEmpty()) {
                    routingTable.finishReplay(pendingMailboxId);
                    PacketWriter.write(ctx.channel(), new GhostPacket(GhostPacket.TYPE_OFFLINE_END,
                            pendingMailboxId, new byte[16], new byte[0], new byte[16]), true);
                    return;
                }
            }
            replayCursor = batch.lastId();
            io.netty.channel.ChannelFuture lastWrite = null;
            for (GhostPacket message : batch.packets()) lastWrite = PacketWriter.write(ctx.channel(), message, false);
            ctx.channel().flush();
            lastWrite.addListener(done -> {
                if (!done.isSuccess()) { ctx.close(); return; }
                try { ctx.executor().execute(() -> drainOffline(ctx)); }
                catch (java.util.concurrent.RejectedExecutionException overloaded) { ctx.close(); }
            });
        } catch (Exception error) {
            log.warn("Offline replay failed: {}", error.getMessage());
            ctx.close();
        }
    }

    // Validates an Ed25519 signature against a public key and the original challenge nonce.
    private boolean verifySignature(byte[] publicKey, byte[] nonce, byte[] signature) {
        try {
            Ed25519PublicKeyParameters pubKeyParams = new Ed25519PublicKeyParameters(publicKey, 0);
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, pubKeyParams);
            verifier.update(nonce, 0, nonce.length);
            return verifier.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // Handles channel deactivation by removing the client from the routing table if previously authenticated.
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (isAuthenticated && pendingMailboxId != null) {
            routingTable.remove(pendingMailboxId, ctx.channel());
        }
        ctx.fireChannelInactive();
    }
}
