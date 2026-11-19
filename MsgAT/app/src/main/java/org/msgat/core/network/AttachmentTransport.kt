// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import android.util.Base64
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel as NettyChannel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import org.msgat.core.crypto.CryptoManager
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.model.GhostPacket
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bulk connections never register as the account's control connection. */
internal class AttachmentUnavailable : IOException("The relay could not provide this attachment")
private class InvalidAttachmentCiphertext : IOException("Encrypted attachment integrity check failed")

internal class AttachmentTransport(private val prefs: SecurePrefs) : Closeable {
    private val lock = Any()
    private var group: NioEventLoopGroup? = null
    private var closed = false
    private val connections = ConcurrentHashMap.newKeySet<NettyChannel>()

    suspend fun upload(manifest: JSONObject, file: File, endpoint: RelayEndpoint, progress: (Long) -> Unit,checkpoint: suspend ()->Unit = {}): Long = withContext(Dispatchers.IO) {
        validate(manifest);checkpoint()
        open(endpoint).use { connection ->
            val id = manifest.getString("object_id")
            val request = JSONObject().put("cipher_size", manifest.getLong("cipher_size"))
                .put("cipher_sha256", manifest.getString("cipher_sha256")).put("read_token_hash", manifest.getString("read_token_hash"))
            checkpoint()
            var status = status(connection.exchange(packet(GhostPacket.TYPE_OBJECT_BEGIN, id, json(request)), GhostPacket.TYPE_OBJECT_STATUS))
            val count = manifest.getLong("chunk_count")
            checkProgress(status, count)
            if (status.complete) return@withContext status.expiry
            val bytes = ByteArray(CHUNK_BYTES)
            try {
                RandomAccessFile(file, "r").use { input ->
                    check(input.length() == manifest.getLong("cipher_size")) { "Incorrect encrypted attachment size" }
                    input.seek(status.next * CHUNK_BYTES)
                    for (index in status.next until count) {
                        input.readFully(bytes)
                        checkpoint()
                        status = status(connection.exchange(packet(GhostPacket.TYPE_OBJECT_PUT, id, bytes, index), GhostPacket.TYPE_OBJECT_STATUS))
                        check(!status.complete && status.next == index + 1) { "Invalid attachment progress acknowledgement" }
                        progress(minOf((index + 1) * 65536, manifest.getLong("size")))
                    }
                }
                checkpoint()
                status = status(connection.exchange(packet(GhostPacket.TYPE_OBJECT_FINISH, id, ByteArray(0)), GhostPacket.TYPE_OBJECT_STATUS))
                checkProgress(status, count)
                check(status.complete) { "Attachment publication is incomplete" }
                status.expiry
            } finally { bytes.fill(0) }
        }
    }

    suspend fun download(manifest: JSONObject, target: File, endpoint: RelayEndpoint, progress: (Long) -> Unit,checkpoint: suspend ()->Unit = {}) = withContext(Dispatchers.IO) {
        validate(manifest);checkpoint()
        val expected = manifest.getLong("cipher_size")
        val hash = manifest.getString("cipher_sha256")
        fun syncPublishedFile() {
            SecurityCore.call(JSONObject().put("operation", "sync_object_publication")
                .put("directory", target.absoluteFile.parentFile!!.parentFile!!.path)
                .put("object_id", manifest.getString("object_id")))
        }
        if (target.isFile) {
            try { verifyFile(target, expected, hash); syncPublishedFile(); return@withContext }
            catch (_: InvalidAttachmentCiphertext) {
                if (!target.delete()) throw IOException("Cannot replace an invalid encrypted attachment")
            }
        }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create the encrypted object directory" }
        val partial = File(target.path + ".part")
        val token = Base64.decode(manifest.getString("read_token"), Base64.NO_WRAP)
        try {
            RandomAccessFile(partial, "rw").use { output ->
                if (output.length() > expected) output.setLength(0)
                val start = output.length() / CHUNK_BYTES
                val count = manifest.getLong("chunk_count")
                output.setLength(start * CHUNK_BYTES); output.seek(output.length())
                if (start < count) open(endpoint).use { connection ->
                    for (index in start until count) {
                        checkpoint()
                        val response = connection.exchange(packet(GhostPacket.TYPE_OBJECT_GET, manifest.getString("object_id"), token, index), GhostPacket.TYPE_OBJECT_DATA)
                        val bytes = response.encryptedData ?: throw IOException("Missing attachment chunk")
                        check(bytes.size == CHUNK_BYTES) { "Invalid encrypted attachment chunk size" }
                        val chunk = JSONObject().put("key", manifest.getString("key")).put("object_id", manifest.getString("object_id"))
                            .put("size", manifest.getLong("size")).put("index", index).put("data", encode(bytes))
                        SecurityCore.call(JSONObject().put("operation", "check_object_chunk").put("chunk", chunk))
                        output.write(bytes); output.fd.sync()
                        progress(minOf((index + 1) * 65536, manifest.getLong("size")))
                    }
                }
            }
            try { verifyFile(partial, expected, hash) } catch (error: InvalidAttachmentCiphertext) { partial.delete(); throw error }
            checkpoint()
            check(!target.exists() && partial.renameTo(target)) { "Cannot finalize the encrypted attachment" }
            syncPublishedFile()
        } finally { token.fill(0) }
    }

    private suspend fun open(endpoint: RelayEndpoint): Connection = withTimeoutOrNull(30000) {
        val handler = BulkHandler(prefs.myMailboxId)
        val workers = synchronized(lock) {
            check(!closed) { "Attachment transport is closed" }
            group ?: NioEventLoopGroup(1).also { group = it }
        }
        val future = Bootstrap().group(workers).channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, io.netty.channel.WriteBufferWaterMark(128 * 1024, 256 * 1024))
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(channel: SocketChannel) {
                    channel.pipeline().addLast(endpoint.handler(channel.alloc()), GhostPacketCodec(), handler)
                }
            }).connect(endpoint.host, endpoint.port)
        val channel = future.awaitChannel()
        connections.add(channel)
        channel.closeFuture().addListener { connections.remove(channel) }
        val connection = Connection(channel, handler.replies)
        try {
            val authentication = handler.authentication.await()
            val challenge = handler.replies.receive()
            check(challenge.type == GhostPacket.TYPE_AUTH_CHAL) { "Missing attachment authentication challenge" }
            val transcript = authentication.transcript(challenge)
            val proof = prefs.withRootSeed { CryptoManager.signChallenge(Ed25519PrivateKeyParameters(it, 0), transcript) }
            channel.writeAndFlush(GhostPacket(GhostPacket.TYPE_AUTH_RESP, prefs.myMailboxId, challenge.nonceOrIv, proof.first + proof.second, ByteArray(16))).awaitWrite()
            val completed = handler.replies.receive()
            authentication.complete(completed)
            connection
        } catch (error: Throwable) { connection.close(); throw error }
    } ?: throw SocketTimeoutException("The attachment connection timed out")

    private class BulkHandler(private val mailbox: String) : SimpleChannelInboundHandler<GhostPacket>() {
        val replies = Channel<GhostPacket>(2)
        val authentication = CompletableDeferred<RelayAuthentication>()
        override fun userEventTriggered(ctx: ChannelHandlerContext, event: Any) {
            if (event is SslHandshakeCompletionEvent) {
                if (!event.isSuccess) { fail(ctx); return }
                val certificate = ctx.pipeline().get(SslHandler::class.java).engine().session.peerCertificates.firstOrNull()
                    ?: run { fail(ctx); return }
                val auth = RelayAuthentication(mailbox, MessageDigest.getInstance("SHA-256").digest(certificate.encoded), transfer = true)
                if (!authentication.complete(auth)) { fail(ctx); return }
                ctx.writeAndFlush(auth.request())
            }
            super.userEventTriggered(ctx, event)
        }
        override fun channelRead0(ctx: ChannelHandlerContext, packet: GhostPacket) { if (replies.trySend(packet).isFailure) fail(ctx) }
        override fun channelInactive(ctx: ChannelHandlerContext) { fail(ctx); super.channelInactive(ctx) }
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { fail(ctx) }
        private fun fail(ctx: ChannelHandlerContext) {
            authentication.completeExceptionally(IOException("Attachment connection closed"))
            replies.close(IOException("Attachment connection closed")); ctx.close()
        }
    }

    private class Connection(private val channel: NettyChannel, private val replies: Channel<GhostPacket>) : Closeable {
        suspend fun exchange(request: GhostPacket, expected: Byte): GhostPacket = withTimeoutOrNull(30000) {
            channel.writeAndFlush(request).awaitWrite()
            val response = replies.receive()
            check(response.targetMailboxId == request.targetMailboxId
                && response.nonceOrIv?.contentEquals(request.nonceOrIv) == true) { "The attachment request could not be completed" }
            if (expected == GhostPacket.TYPE_OBJECT_DATA && response.type == GhostPacket.TYPE_OBJECT_ERROR &&
                response.encryptedData?.contentEquals("{\"error\":\"object_unavailable\"}".encodeToByteArray()) == true)
                throw AttachmentUnavailable()
            check(response.type == expected) { "The attachment request could not be completed" }
            response
        } ?: throw SocketTimeoutException("The attachment request timed out")
        override fun close() { channel.close(); replies.cancel() }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        for (channel in connections) channel.close()
        group?.shutdownGracefully(0, 1, TimeUnit.SECONDS); group = null
    }

    companion object {
        /** Purpose-signed public cancellation; usable after the identity key has been destroyed. */
        internal suspend fun revoke(endpoint: RelayEndpoint,proof: ByteArray) = withContext(Dispatchers.IO) {
            require(proof.size==113)
            val group=NioEventLoopGroup(1)
            val reply=CompletableDeferred<GhostPacket>()
            val nonce=ByteArray(16).also { random.nextBytes(it) }
            var channel: NettyChannel?=null
            try {
                kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    val future=Bootstrap().group(group).channel(NioSocketChannel::class.java)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,10_000).option(ChannelOption.TCP_NODELAY,true)
                        .handler(object : ChannelInitializer<SocketChannel>() {
                            override fun initChannel(ch: SocketChannel) {
                                ch.pipeline().addLast(endpoint.handler(ch.alloc()),GhostPacketCodec(),object : SimpleChannelInboundHandler<GhostPacket>() {
                                    override fun userEventTriggered(ctx: ChannelHandlerContext,event: Any) {
                                        if(event is SslHandshakeCompletionEvent) {
                                            if(event.isSuccess) ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_V2_REVOKE,null,nonce,proof,ByteArray(16)))
                                            else reply.completeExceptionally(IOException("Revocation TLS unavailable"))
                                        }
                                    }
                                    override fun channelRead0(ctx: ChannelHandlerContext,packet: GhostPacket) { reply.complete(packet); ctx.close() }
                                    override fun channelInactive(ctx: ChannelHandlerContext) { reply.completeExceptionally(IOException("Revocation connection closed")) }
                                    override fun exceptionCaught(ctx: ChannelHandlerContext,cause: Throwable) { reply.completeExceptionally(IOException("Revocation unavailable")); ctx.close() }
                                })
                            }
                        }).connect(endpoint.host,endpoint.port)
                    channel=future.awaitChannel()
                    val response=reply.await()
                    check(response.type==GhostPacket.TYPE_V2_REVOKED && response.targetMailboxId.isNullOrEmpty() && response.nonceOrIv?.contentEquals(nonce)==true &&
                        response.encryptedData?.let { MessageDigest.isEqual(it,MessageDigest.getInstance("SHA-256").digest(proof)) }==true) { "Invalid revocation acknowledgement" }
                } ?: throw IOException("Revocation acknowledgement timed out")
            } finally { channel?.close(); group.shutdownGracefully(0,1,TimeUnit.SECONDS) }
        }

        private const val CHUNK_BYTES = 65552
        private val random = SecureRandom()
        private data class Status(val next: Long, val complete: Boolean, val expiry: Long)
        private fun status(packet: GhostPacket): Status {
            val bytes = packet.encryptedData ?: throw IOException("Missing attachment acknowledgement")
            check(bytes.size <= 1024) { "Oversized attachment acknowledgement" }
            val value = JSONObject(String(bytes, Charsets.UTF_8))
            return Status(value.getLong("next_chunk"), value.getBoolean("complete"), value.getLong("expires_at"))
        }
        private fun checkProgress(value: Status, count: Long) { check(value.next in 0..count && (!value.complete || value.next == count)) { "Invalid attachment progress" } }
        private fun validate(value: JSONObject) { SecurityCore.call(JSONObject().put("operation", "validate_object_manifest").put("manifest", value)) }
        private fun packet(type: Byte, id: String, bytes: ByteArray, index: Long? = null): GhostPacket {
            val nonce = ByteArray(16).also { random.nextBytes(it) }
            if (index != null) ByteBuffer.wrap(nonce).putLong(index)
            return GhostPacket(type, id, nonce, bytes, ByteArray(16))
        }
        private fun verifyFile(file: File, expected: Long, hash: String) {
            if (file.length() != expected) throw InvalidAttachmentCiphertext()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            try { file.inputStream().use { input -> while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } } }
            finally { buffer.fill(0) }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != hash) throw InvalidAttachmentCiphertext()
        }
        private fun json(value: JSONObject) = value.toString().toByteArray(Charsets.UTF_8)
        private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
        private suspend fun ChannelFuture.awaitChannel(): NettyChannel = suspendCancellableCoroutine { continuation ->
            addListener { if (it.isSuccess) continuation.resume(channel()) else continuation.resumeWithException(IOException("Attachment connection failed")) }
            continuation.invokeOnCancellation { channel().close() }
        }
        private suspend fun ChannelFuture.awaitWrite(): Unit = suspendCancellableCoroutine { continuation ->
            addListener { if (it.isSuccess) continuation.resume(Unit) else continuation.resumeWithException(IOException("Attachment write failed")) }
            continuation.invokeOnCancellation { channel().close() }
        }
    }
}
