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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.MsgDao
import org.msgat.model.GhostPacket
import org.msgat.ui.chat.SyncState

class NettyClient(
    private val prefs: SecurePrefs,
    private val msgDao: MsgDao
) {
    private var currentChannel: Channel? = null

    private val _connectionState = MutableStateFlow(SyncState.DISCONNECTED)
    val connectionState: StateFlow<SyncState> = _connectionState.asStateFlow()

    private var workerGroup: NioEventLoopGroup? = null
    private var channel: Channel? = null

    private var isManualDisconnect = false
    private var retryAttempt = 0

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
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
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
        if (!isConfigured() || channel?.isActive == true) return
        isManualDisconnect = false

        val ip = prefs.serverIp!!
        val port = prefs.serverPort
        val myUuid = prefs.myUuid

        workerGroup = NioEventLoopGroup()
        val bootstrap = Bootstrap()

        try {

            val sslCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build()

            bootstrap.group(workerGroup)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    // Configures the Netty channel pipeline with encoders, decoders, and handlers.
                    override fun initChannel(ch: SocketChannel) {
                        val p = ch.pipeline()

                        p.addLast(sslCtx.newHandler(ch.alloc(), ip, port))
                        
                        // Added IdleStateHandler for 3-minute writer idle
                        p.addLast(io.netty.handler.timeout.IdleStateHandler(0, 180, 0, java.util.concurrent.TimeUnit.SECONDS))

                        p.addLast(GhostPacketCodec())

                        p.addLast(GhostClientHandler(prefs, msgDao,
                            onStateChange = { newState ->
                                if (newState == SyncState.SYNCED) {
                                    resetBackoff()
                                }
                                _connectionState.value = newState
                            }
                        ))
                    }
                })

            bootstrap.connect(ip, port).addListener { future ->
                if (future.isSuccess) {
                    android.util.Log.i("MsgAT_Netty", "Connected successfully to $ip:$port")
                    channel = (future as io.netty.channel.ChannelFuture).channel()
                    channel?.closeFuture()?.addListener {
                        if (!isManualDisconnect) {
                            scheduleReconnect()
                        }
                    }
                } else {
                    android.util.Log.e("MsgAT_Netty", "Connection failed to $ip:$port", future.cause())
                    workerGroup?.shutdownGracefully()
                    if (!isManualDisconnect) {
                        scheduleReconnect()
                    }
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("MsgAT_Netty", "Exception in connect()", e)
            _connectionState.value = SyncState.DISCONNECTED
            workerGroup?.shutdownGracefully()
        }
    }

    // Gracefully closes the active Netty channel and shuts down the event loop group.
    fun disconnect() {
        isManualDisconnect = true
        channel?.close()
        workerGroup?.shutdownGracefully()
        _connectionState.value = SyncState.DISCONNECTED
    }

    // Asynchronously flushes a GhostPacket through the pipeline to the server.
    fun sendPacket(packet: GhostPacket): Boolean {
        return if (channel?.isActive == true) {
            channel?.writeAndFlush(packet)
            true
        } else {
            false
        }
    }
}