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

    public AuthChallengeHandler(BlindRoutingTable routingTable) {
        this.routingTable = routingTable;
    }

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

    private void handleAuthRequest(ChannelHandlerContext ctx, GhostPacket packet) {
        this.pendingMailboxId = packet.getTargetMailboxId();
        this.currentChallengeNonce = new byte[16];
        new java.security.SecureRandom().nextBytes(currentChallengeNonce);

        GhostPacket challenge = new GhostPacket(
                GhostPacket.TYPE_AUTH_CHAL, this.pendingMailboxId, this.currentChallengeNonce, null, null
        );
        ctx.writeAndFlush(challenge);
    }

    private void handleAuthResponse(ChannelHandlerContext ctx, GhostPacket packet) {
        byte[] payload = packet.getEncryptedData();
        // 验证数据长度：32(公钥) + 64(签名) = 96
        if (payload == null || payload.length != 96) {
            ctx.close();
            return;
        }

        byte[] publicKey = Arrays.copyOfRange(payload, 0, 32);
        byte[] signature = Arrays.copyOfRange(payload, 32, 96);

        // 核心验签逻辑
        if (verifySignature(publicKey, currentChallengeNonce, signature)) {
            this.isAuthenticated = true;
            routingTable.register(this.pendingMailboxId, ctx.channel());
            log.info("[AUTH] 信箱验证通过: {}", this.pendingMailboxId.substring(0, 8));

            // 【新增补全】：上线后立刻检查并推送离线消息！
            var offlineMsgs = routingTable.retrieveOfflineMessages(this.pendingMailboxId);
            if (offlineMsgs != null && !offlineMsgs.isEmpty()) {
                log.info("[RELAY] 发现 {} 条离线消息，正在推送...", offlineMsgs.size());
                for (GhostPacket msg : offlineMsgs) {
                    ctx.writeAndFlush(msg);
                }
                // 注意：推完之后绝对不能在这里清空内存！
                // 必须等客户端收到后发回 ACK 包，RelayHandler 才会执行物理抹除。
            }
        } else {
            log.warn("[AUTH] 签名验证失败，强制断开");
            ctx.close();
        }

        GhostPacket endPacket = new GhostPacket(
                GhostPacket.TYPE_OFFLINE_END,
                this.pendingMailboxId, // 目标依然是当前上线的信箱
                new byte[16],          // 空的 IV
                new byte[0],           // 零长度的有效载荷
                new byte[16]           // 空的 AuthTag
        );
        ctx.writeAndFlush(endPacket);
        log.debug("[RELAY] 已向下发同步结束标记");
    }

    private boolean verifySignature(byte[] publicKey, byte[] nonce, byte[] signature) {
        try {
            // 使用 Bouncy Castle 的 Ed25519 验证器
            Ed25519PublicKeyParameters pubKeyParams = new Ed25519PublicKeyParameters(publicKey, 0);
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, pubKeyParams);
            verifier.update(nonce, 0, nonce.length);
            return verifier.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 【新增补全】：网络断开时，立刻把这个连接从活跃路由表中拔除
        if (isAuthenticated && pendingMailboxId != null) {
            routingTable.remove(pendingMailboxId);
            log.debug("[NETWORK] 目标断开连接，信箱 {} 已从活跃区摘除", pendingMailboxId.substring(0, 8));
        }
        ctx.fireChannelInactive();
    }
}