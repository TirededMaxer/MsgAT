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
    }

    // Handles incoming authentication request by generating and sending a challenge nonce to the client.
    private void handleAuthRequest(ChannelHandlerContext ctx, GhostPacket packet) {
        this.pendingMailboxId = packet.getTargetMailboxId();
        this.currentChallengeNonce = new byte[16];
        new java.security.SecureRandom().nextBytes(currentChallengeNonce);

        GhostPacket challenge = new GhostPacket(
                GhostPacket.TYPE_AUTH_CHAL, this.pendingMailboxId, this.currentChallengeNonce, null, null
        );
        ctx.writeAndFlush(challenge);
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

                var offlineMsgs = routingTable.retrieveOfflineMessages(this.pendingMailboxId);
                if (offlineMsgs != null && !offlineMsgs.isEmpty()) {
                    for (GhostPacket msg : offlineMsgs) {
                        ctx.writeAndFlush(msg);
                    }
                }
                ctx.writeAndFlush(new GhostPacket(GhostPacket.TYPE_OFFLINE_END, this.pendingMailboxId,
                        new byte[16], new byte[0], new byte[16]));
            }
        } else {
            ctx.close();
            return;
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
