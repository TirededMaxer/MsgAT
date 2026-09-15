package org.msgat.core.network

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.MsgDao
import org.msgat.model.GhostPacket
import org.msgat.ui.chat.SyncState
import java.util.concurrent.atomic.AtomicBoolean

class NettyClient(
    private val prefs: SecurePrefs,
    private val msgDao: MsgDao
) {
    private val _connectionState = MutableStateFlow(SyncState.DISCONNECTED)
    val connectionState: StateFlow<SyncState> = _connectionState.asStateFlow()

    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var workerGroup: NioEventLoopGroup? = null
    @Volatile private var channel: Channel? = null

    @Volatile private var isManualDisconnect = false
    private var retryAttempt = 0
    private val connecting = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null

    @Volatile private var deletionReply: Pair<ByteArray, kotlinx.coroutines.CompletableDeferred<Boolean>>? = null

    suspend fun deleteAccount(): Boolean {
        if (connectionState.value != SyncState.SYNCED) return false
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        deletionReply = nonce to result
        try {
            if (!sendPacket(GhostPacket(GhostPacket.TYPE_DELETE_ACCOUNT, org.msgat.core.crypto.CryptoManager.calculateMailboxId(prefs.myUuid), nonce, ByteArray(0), ByteArray(16)))) return false
            return kotlinx.coroutines.withTimeoutOrNull(10000) { result.await() } ?: false
        } finally { deletionReply = null }
    }

    fun sendProfile() {
        scope.launch {
            if (connectionState.value != SyncState.SYNCED) return@launch
            try {
                sendPacket(GhostPacket(GhostPacket.TYPE_UPDATE_PROFILE,
                    org.msgat.core.crypto.CryptoManager.calculateMailboxId(prefs.myUuid), ByteArray(16), prefs.profilePayload(), ByteArray(16)))
            } catch (error: Exception) { android.util.Log.e("MsgAT", "Profile update failed", error) }
        }
    }

    fun resetBackoff() {
        retryAttempt = 0
    }

    private fun calculateBackoffDelayMs(): Long {
        val baseDelay = (1000L * (1 shl kotlin.math.min(retryAttempt, 5))).coerceAtMost(30000L)
        val jitter = kotlin.random.Random.nextLong(0, 1000)
        return baseDelay + jitter
    }

    private fun scheduleReconnect() {
        if (isManualDisconnect || !isConfigured()) return
        val delayMs = calculateBackoffDelayMs()
        retryAttempt++
        android.util.Log.i("MsgAT_Netty", "Scheduling reconnect attempt #$retryAttempt in ${delayMs}ms")
        _connectionState.value = SyncState.DISCONNECTED
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!isManualDisconnect && (channel == null || channel?.isActive != true)) {
                connect()
            }
        }
    }

    // Checks whether the client possesses the necessary configuration to initiate a connection.
    fun isConfigured(): Boolean = prefs.isConfigured()

    // Bootstraps and establishes an asynchronous Netty TCP connection to the relay server.
    fun connect() {
        if (!isConfigured() || channel?.isActive == true || !connecting.compareAndSet(false, true)) return
        isManualDisconnect = false

        val ip = prefs.serverIp!!
        val port = prefs.serverPort
        val currentGeneration = generation.incrementAndGet()

        val newWorkerGroup = NioEventLoopGroup()
        workerGroup = newWorkerGroup
        val bootstrap = Bootstrap()

        try {

            val pin = prefs.serverCertificateSha256.orEmpty().replace(":", "").replace(" ", "").lowercase()
            val tls = SslContextBuilder.forClient()
            if (pin.isNotEmpty()) {
                require(pin.matches(Regex("[a-f0-9]{64}"))) { "Invalid TLS SHA-256 pin" }
                tls.trustManager(object : javax.net.ssl.X509TrustManager {
                    override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                        throw java.security.cert.CertificateException("Client certificate not supported")
                    }
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                        val certificate = chain.firstOrNull() ?: throw java.security.cert.CertificateException("Missing server certificate")
                        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                            .joinToString("") { "%02x".format(it) }
                        if (actual != pin) throw java.security.cert.CertificateException("Server certificate pin mismatch")
                    }
                })
            } else if (prefs.allowDevelopmentCertificate) {
                tls.trustManager(InsecureTrustManagerFactory.INSTANCE)
            }
            val sslCtx = tls.build()
            val verifyHostname = pin.isEmpty() && !prefs.allowDevelopmentCertificate

            bootstrap.group(newWorkerGroup)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    // Configures the Netty channel pipeline with encoders, decoders, and handlers.
                    override fun initChannel(ch: SocketChannel) {
                        val p = ch.pipeline()

                        val sslHandler = sslCtx.newHandler(ch.alloc(), ip, port)
                        if (verifyHostname) {
                            val parameters = sslHandler.engine().sslParameters
                            parameters.endpointIdentificationAlgorithm = "HTTPS"
                            sslHandler.engine().sslParameters = parameters
                        }
                        p.addLast(sslHandler)

                        // Added IdleStateHandler for 3-minute writer idle
                        p.addLast(io.netty.handler.timeout.IdleStateHandler(0, 180, 0, java.util.concurrent.TimeUnit.SECONDS))

                        p.addLast(GhostPacketCodec())

                        p.addLast(GhostClientHandler(prefs, msgDao,
                            onDeleteConfirmed = { packet ->
                                deletionReply?.let { (nonce, result) ->
                                    if (packet.nonceOrIv?.contentEquals(nonce) == true) result.complete(true)
                                }
                            },
                            onStateChange = { newState ->
                                if (generation.get() == currentGeneration) {
                                if (newState == SyncState.SYNCED) {
                                    resetBackoff()
                                }
                                _connectionState.value = newState
                                }
                            }
                        ))
                    }
                })

            bootstrap.connect(ip, port).addListener { future ->
                if (generation.get() != currentGeneration) {
                    (future as io.netty.channel.ChannelFuture).channel().close()
                    newWorkerGroup.shutdownGracefully()
                    return@addListener
                }
                connecting.set(false)
                if (future.isSuccess) {
                    android.util.Log.i("MsgAT_Netty", "Connected successfully to $ip:$port")
                    channel = (future as io.netty.channel.ChannelFuture).channel()
                    val connectedChannel = channel
                    connectedChannel?.closeFuture()?.addListener {
                        if (channel === connectedChannel) channel = null
                        newWorkerGroup.shutdownGracefully()
                        if (!isManualDisconnect && generation.get() == currentGeneration) {
                            scheduleReconnect()
                        }
                    }
                } else {
                    android.util.Log.e("MsgAT_Netty", "Connection failed to $ip:$port", future.cause())
                    newWorkerGroup.shutdownGracefully()
                    if (!isManualDisconnect) {
                        scheduleReconnect()
                    }
                }
            }

        } catch (e: Exception) {
            connecting.set(false)
            android.util.Log.e("MsgAT_Netty", "Exception in connect()", e)
            _connectionState.value = SyncState.DISCONNECTED
            newWorkerGroup.shutdownGracefully()
        }
    }

    // Gracefully closes the active Netty channel and shuts down the event loop group.
    fun disconnect() {
        isManualDisconnect = true
        generation.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        connecting.set(false)
        val current = channel
        channel = null
        current?.close()
        val group = workerGroup
        workerGroup = null
        group?.shutdownGracefully()
        _connectionState.value = SyncState.DISCONNECTED
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    // Asynchronously flushes a GhostPacket through the pipeline to the server.
    fun sendPacket(packet: GhostPacket): Boolean {
        return if (channel?.isActive == true) {
            channel?.writeAndFlush(packet)?.addListener { future ->
                if (!future.isSuccess) (future as io.netty.channel.ChannelFuture).channel().close()
            }
            true
        } else {
            false
        }
    }
}
