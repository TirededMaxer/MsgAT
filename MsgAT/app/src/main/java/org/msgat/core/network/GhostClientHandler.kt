// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.util.concurrent.ScheduledFuture
import org.msgat.core.crypto.CryptoManager
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.model.GhostPacket
import org.msgat.ui.chat.SyncState
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/** TLS/authentication framing only. All V2 business content goes through libsignal. */
class GhostClientHandler(
    private val prefs: SecurePrefs,
    private val onStateChange: (SyncState) -> Unit,
    private val onAuthenticated: (Boolean, String) -> Unit,
    private val onSecurePacket: (GhostPacket) -> Unit,
    private val onPushResult: (GhostPacket) -> Unit,
    private val onDisconnected: () -> Unit
) : SimpleChannelInboundHandler<GhostPacket>() {
    private val mailbox = prefs.myMailboxId
    private var authentication: RelayAuthentication? = null
    private var authenticationDeadline: ScheduledFuture<*>? = null
    private var heartbeatNonce: ByteArray? = null
    private var heartbeatDeadline: ScheduledFuture<*>? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        onStateChange(SyncState.SYNCING)
        authenticationDeadline = ctx.executor().schedule({
            if (authentication?.authenticated != true) ctx.close()
        }, 30, TimeUnit.SECONDS)
        super.channelActive(ctx)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, packet: GhostPacket) {
        val auth = authentication ?: run { ctx.close(); return }
        if (!auth.authenticated && packet.type != GhostPacket.TYPE_AUTH_CHAL && packet.type != GhostPacket.TYPE_OFFLINE_END) {
            ctx.close(); return
        }
        when (packet.type) {
            GhostPacket.TYPE_AUTH_CHAL -> {
                val transcript = auth.transcript(packet)
                val (publicKey,signature) = prefs.withRootSeed { seed ->
                    CryptoManager.signChallenge(org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0), transcript)
                }
                ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_AUTH_RESP,mailbox,packet.nonceOrIv,publicKey+signature,ByteArray(16)))
            }
            GhostPacket.TYPE_OFFLINE_END -> {
                auth.complete(packet)
                val epoch = requireNotNull(packet.nonceOrIv)
                require(epoch.size == 16 && epoch.any { it != 0.toByte() }) { "Missing relay directory epoch" }
                authenticationDeadline?.cancel(false)
                onAuthenticated(packet.encryptedData?.contentEquals(byteArrayOf(85, 80, 49)) == true,
                    android.util.Base64.encodeToString(epoch, android.util.Base64.NO_WRAP)) // UP1
            }
            GhostPacket.TYPE_V2_PUSH_RESULT -> onPushResult(packet)
            GhostPacket.TYPE_V2_KEY_PUBLISHED, GhostPacket.TYPE_V2_KEY_RESPONSE,
            GhostPacket.TYPE_V2_REJECTED, GhostPacket.TYPE_V2_DEFERRED,
            GhostPacket.TYPE_V2_STORED, GhostPacket.TYPE_V2_DELIVERY, GhostPacket.TYPE_V2_SYNC_END -> onSecurePacket(packet)
            GhostPacket.TYPE_HEARTBEAT -> {
                val nonce = heartbeatNonce
                if (nonce != null && packet.payloadLen == 0 && nonce.contentEquals(packet.nonceOrIv)) {
                    heartbeatDeadline?.cancel(false)
                    heartbeatDeadline = null
                    heartbeatNonce = null
                }
            }
            // V1 business packets are never interpreted or decrypted by this client.
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, event: Any) {
        if (event is io.netty.handler.ssl.SslHandshakeCompletionEvent) {
            if (!event.isSuccess || authentication != null) { ctx.close(); return }
            val tls = ctx.pipeline().get(io.netty.handler.ssl.SslHandler::class.java)
            val certificate = tls?.engine()?.session?.peerCertificates?.firstOrNull()
                ?: run { ctx.close(); return }
            val endpoint = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            val auth = RelayAuthentication(mailbox, endpoint)
            authentication = auth
            ctx.writeAndFlush(auth.request())
        }
        if (event is IdleStateEvent && authentication?.authenticated == true) sendHeartbeat(ctx)
        super.userEventTriggered(ctx,event)
    }

    private fun sendHeartbeat(ctx: ChannelHandlerContext) {
        if (heartbeatNonce != null) return
        val nonce = ByteArray(16).also { ThreadLocalRandom.current().nextBytes(it) }
        heartbeatNonce = nonce
        heartbeatDeadline = ctx.executor().schedule({ ctx.close() }, 45, TimeUnit.SECONDS)
        ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_HEARTBEAT, mailbox, nonce, ByteArray(0), ByteArray(16)))
            .addListener(io.netty.channel.ChannelFutureListener.CLOSE_ON_FAILURE)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        authenticationDeadline?.cancel(false)
        heartbeatDeadline?.cancel(false)
        heartbeatDeadline = null
        heartbeatNonce = null
        authentication = null
        onDisconnected(); onStateChange(SyncState.DISCONNECTED)
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Network/parser exceptions can contain payload bytes; do not log their values.
        android.util.Log.w("MsgAT_Netty","Connection closed: ${cause.javaClass.simpleName}")
        ctx.close()
    }
}
