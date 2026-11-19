package org.msgat.console.core;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.msgat.console.db.SignalKeyDirectory;
import org.msgat.console.model.GhostPacket;
import org.msgat.console.routing.BlindRoutingTable;

import java.util.Arrays;

public class AuthChallengeHandler extends SimpleChannelInboundHandler<GhostPacket> {
    private final BlindRoutingTable routingTable;
    private final java.util.function.Consumer<io.netty.channel.Channel> configureTransfer;
    private final Runnable wakeRevocationCleanup;
    private boolean transfer;

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private volatile boolean isAuthenticated = false;
    private byte[] currentChallengeNonce = null;
    private byte[] currentTranscript;
    private long challengeIssuedAtNanos;
    private String pendingMailboxId = null;
    private final RelayAdmission.Lease admission;
    private final boolean pushAvailable;

    // Constructor to initialize the handler with a routing table instance.
    AuthChallengeHandler(BlindRoutingTable routingTable, RelayAdmission.Lease admission, boolean pushAvailable,
                         Runnable wakeRevocationCleanup, java.util.function.Consumer<io.netty.channel.Channel> configureTransfer) {
        this.routingTable = routingTable;
        this.admission = admission;
        this.pushAvailable = pushAvailable;
        this.configureTransfer = configureTransfer;
        this.wakeRevocationCleanup = wakeRevocationCleanup;
    }

    // Intercepts packets for authentication; passes through if already authenticated, otherwise handles challenge-response.
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GhostPacket packet) {
        boolean transferred = isAuthenticated && transfer;
        try {
            if (packet.getType()==GhostPacket.TYPE_V2_REVOKE) {
                RevocationHandler.handle(ctx, packet, routingTable, wakeRevocationCleanup);
                return;
            }
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
        } finally { if (!transferred) ApplicationQueueGuard.completed(ctx.channel()); }
    }

    // Handles incoming authentication request by generating and sending a challenge nonce to the client.
    private void handleAuthRequest(ChannelHandlerContext ctx, GhostPacket packet) {
        if (currentChallengeNonce != null || packet.getTargetMailboxId() == null
                || !packet.getTargetMailboxId().matches("[a-f0-9]{64}")
                || packet.getNonceOrIv() == null || packet.getNonceOrIv().length != 16
                || (!Arrays.equals(packet.getEncryptedData(), RelayAuthentication.REQUEST)
                    && !Arrays.equals(packet.getEncryptedData(), RelayAuthentication.TRANSFER_REQUEST))) {
            ctx.close();
            return;
        }
        this.pendingMailboxId = packet.getTargetMailboxId();
        transfer = Arrays.equals(packet.getEncryptedData(), RelayAuthentication.TRANSFER_REQUEST);
        this.currentChallengeNonce = new byte[16];
        this.challengeIssuedAtNanos = System.nanoTime();
        RANDOM.nextBytes(currentChallengeNonce);

        byte[] body = RelayAuthentication.challenge(ctx.channel(), packet.getNonceOrIv(), transfer);
        currentTranscript = RelayAuthentication.transcript(pendingMailboxId, currentChallengeNonce, body);

        GhostPacket challenge = new GhostPacket(
                GhostPacket.TYPE_AUTH_CHAL, this.pendingMailboxId, this.currentChallengeNonce, body, null
        );
        PacketWriter.write(ctx.channel(), challenge, true);
    }

    // Register the authenticated control socket; V2 sync owns durable replay.
    private void handleAuthResponse(ChannelHandlerContext ctx, GhostPacket packet) {
        byte[] payload = packet.getEncryptedData();
        if (currentChallengeNonce == null || currentTranscript == null || pendingMailboxId == null
                || !pendingMailboxId.equals(packet.getTargetMailboxId())
                || !messageDigestEqual(currentChallengeNonce, packet.getNonceOrIv())
                || payload == null || payload.length != 96
                || System.nanoTime() - challengeIssuedAtNanos > java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
            ctx.close();
            return;
        }

        byte[] publicKey = Arrays.copyOfRange(payload, 0, 32);
        byte[] signature = Arrays.copyOfRange(payload, 32, 96);

        String uuid = IdentityAddresses.identityUuid(publicKey);
        if (pendingMailboxId.equals(IdentityAddresses.calculateMailboxId(uuid))
                && verifySignature(publicKey, currentTranscript, signature)) {
            try(var active=org.msgat.console.db.IdentityRevocations.active(uuid)) {
            if(!routingTable.track(pendingMailboxId,ctx.channel())) { ctx.close(); return; }
            if (!admission.authenticate()) { ctx.close(); return; }
            this.isAuthenticated = true;
            ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("AUTHENTICATED_UUID")).set(uuid);
            ctx.channel().attr(io.netty.util.AttributeKey.<byte[]>valueOf("ROOT_PUBLIC_KEY")).set(publicKey.clone());
            ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf("MAILBOX_ID")).set(this.pendingMailboxId);
            ctx.channel().attr(io.netty.util.AttributeKey.<Boolean>valueOf("TRANSFER_CHANNEL")).set(transfer);
            if (transfer) configureTransfer.accept(ctx.channel());
            else synchronized (routingTable) {
                routingTable.register(this.pendingMailboxId, ctx.channel());

                // Cleanup is independent of a potentially saturated application executor.
                String mailbox = this.pendingMailboxId;
                ctx.channel().closeFuture().addListener(done -> routingTable.remove(mailbox, ctx.channel()));
            }
            Arrays.fill(currentChallengeNonce, (byte) 0);
            currentChallengeNonce = null;
            Arrays.fill(currentTranscript, (byte) 0);
            currentTranscript = null;
            PacketWriter.write(ctx.channel(), new GhostPacket(GhostPacket.TYPE_OFFLINE_END,
                    pendingMailboxId, transfer ? new byte[16] : SignalKeyDirectory.epoch(),
                    pushAvailable && !transfer ? AndroidPushRelay.CAPABILITY : new byte[0], new byte[16]), true);
            } catch(java.sql.SQLException | IllegalStateException error) { ctx.close(); }
        } else {
            ctx.close();
            return;
        }

    }

    private static boolean messageDigestEqual(byte[] expected, byte[] actual) {
        return actual != null && java.security.MessageDigest.isEqual(expected, actual);
    }

    // Only this fixed-purpose transcript is signed; a bare nonce is never accepted.
    private boolean verifySignature(byte[] publicKey, byte[] transcript, byte[] signature) {
        try {
            Ed25519PublicKeyParameters pubKeyParams = new Ed25519PublicKeyParameters(publicKey, 0);
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, pubKeyParams);
            verifier.update(transcript, 0, transcript.length);
            return verifier.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // Handles channel deactivation by removing the client from the routing table if previously authenticated.
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (currentChallengeNonce != null) Arrays.fill(currentChallengeNonce, (byte) 0);
        if (currentTranscript != null) Arrays.fill(currentTranscript, (byte) 0);
        currentChallengeNonce = null;
        currentTranscript = null;
        if (isAuthenticated && !transfer && pendingMailboxId != null) {
            routingTable.remove(pendingMailboxId, ctx.channel());
        }
        ctx.fireChannelInactive();
    }
}
