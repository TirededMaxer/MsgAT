// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import org.msgat.model.GhostPacket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/** Confined to one Netty channel; never reused after reconnect. */
internal class RelayAuthentication(private val mailbox: String, private val endpoint: ByteArray, private val transfer: Boolean = false) {
    private val clientNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private var challenged = false
    var authenticated = false
        private set

    fun request() = GhostPacket(GhostPacket.TYPE_AUTH_REQ, mailbox, clientNonce,
        (if (transfer) "MTB2" else "MTA2").toByteArray(Charsets.US_ASCII), ByteArray(16))

    fun transcript(packet: GhostPacket): ByteArray {
        val body = packet.encryptedData ?: error("Missing authentication body")
        val nonce = packet.nonceOrIv ?: error("Missing authentication nonce")
        require(!challenged && !authenticated && packet.targetMailboxId == mailbox && nonce.size == 16
            && body.size == 50 && body[0] == 2.toByte() && body[1] == (if (transfer) 1 else 0).toByte() && endpoint.size == 32
            && MessageDigest.isEqual(body.copyOfRange(2, 18), clientNonce)
            && MessageDigest.isEqual(body.copyOfRange(18, 50), endpoint)) { "Invalid authentication challenge" }
        challenged = true
        val domain = "MsgAT/v2/relay-auth\u0000".toByteArray(Charsets.US_ASCII)
        val mailboxBytes = ByteArray(32) { mailbox.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return ByteBuffer.allocate(domain.size + 98).put(domain).put(2.toByte()).put((if (transfer) 1 else 0).toByte()).put(mailboxBytes)
            .put(clientNonce).put(nonce).put(endpoint).array()
    }

    fun complete(packet: GhostPacket) {
        require(challenged && !authenticated && packet.type == GhostPacket.TYPE_OFFLINE_END &&
            packet.targetMailboxId == mailbox) { "Unexpected authentication completion" }
        authenticated = true
    }
}
