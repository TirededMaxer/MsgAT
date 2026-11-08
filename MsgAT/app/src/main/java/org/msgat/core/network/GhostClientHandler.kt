// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.msgat.core.crypto.CryptoManager
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.model.GhostPacket
import org.msgat.ui.chat.SyncState

/** TLS/authentication framing only. All V2 business content goes through libsignal. */
class GhostClientHandler(
    private val prefs: SecurePrefs,
    private val onStateChange: (SyncState) -> Unit,
    private val onLegacySyncEnd: () -> Unit,
    private val onSecurePacket: (GhostPacket) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onDeleteConfirmed: (GhostPacket) -> Unit
) : SimpleChannelInboundHandler<GhostPacket>() {
    private val mailbox = prefs.myMailboxId

    override fun channelActive(ctx: ChannelHandlerContext) {
        onStateChange(SyncState.SYNCING)
        ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_AUTH_REQ,mailbox,ByteArray(16),ByteArray(0),ByteArray(16)))
    }

    override fun channelRead0(ctx: ChannelHandlerContext, packet: GhostPacket) {
        when (packet.type) {
            GhostPacket.TYPE_AUTH_CHAL -> {
                val nonce=packet.nonceOrIv
                if (nonce==null || nonce.size!=16) { ctx.close(); return }
                val (publicKey,signature)=CryptoManager.signChallenge(prefs.getOrCreatePrivateKey(),nonce)
                ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_AUTH_RESP,mailbox,nonce,publicKey+signature,ByteArray(16)))
            }
            GhostPacket.TYPE_OFFLINE_END -> onLegacySyncEnd()
            GhostPacket.TYPE_V2_KEY_PUBLISHED, GhostPacket.TYPE_V2_KEY_RESPONSE,
            GhostPacket.TYPE_V2_STORED, GhostPacket.TYPE_V2_DELIVERY, GhostPacket.TYPE_V2_SYNC_END -> onSecurePacket(packet)
            GhostPacket.TYPE_DELETE_ACCOUNT -> onDeleteConfirmed(packet)
            GhostPacket.TYPE_HEARTBEAT -> Unit
            // V1 business packets are never interpreted or decrypted by this client.
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, event: Any) {
        if (event is io.netty.handler.timeout.IdleStateEvent && event.state()==io.netty.handler.timeout.IdleState.WRITER_IDLE)
            ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_HEARTBEAT,mailbox,ByteArray(16),ByteArray(0),ByteArray(16)))
        super.userEventTriggered(ctx,event)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        onDisconnected(); onStateChange(SyncState.DISCONNECTED)
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Network/parser exceptions can contain payload bytes; do not log their values.
        android.util.Log.w("MsgAT_Netty","Connection closed: ${cause.javaClass.simpleName}")
        ctx.close()
    }
}
