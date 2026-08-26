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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.MsgDao
import org.msgat.model.GhostPacket
import org.msgat.model.SyncState
import java.util.concurrent.TimeUnit

class NettyClient(
    private val prefs: SecurePrefs,
    private val msgDao: MsgDao
) {
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
        println("Scheduling reconnect attempt #$retryAttempt in ${delayMs}ms")
        _connectionState.value = SyncState.DISCONNECTED
        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
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

        val ip = prefs.serverIp ?: return
        val port = prefs.serverPort
        if (port <= 0) return

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
                    override fun initChannel(ch: SocketChannel) {
                        val p = ch.pipeline()

                        p.addLast(sslCtx.newHandler(ch.alloc(), ip, port))
                        
                        // 180s writer idle for heartbeat
                        p.addLast(io.netty.handler.timeout.IdleStateHandler(0, 180, 0, TimeUnit.SECONDS))

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
                    println("Connected successfully to $ip:$port")
                    channel = (future as io.netty.channel.ChannelFuture).channel()
                    channel?.closeFuture()?.addListener {
                        if (!isManualDisconnect) {
                            scheduleReconnect()
                        }
                    }
                } else {
                    println("Connection failed to $ip:$port: ${future.cause()?.message}")
                    workerGroup?.shutdownGracefully()
                    if (!isManualDisconnect) {
                        scheduleReconnect()
                    }
                }
            }

        } catch (e: Exception) {
            println("Exception in connect(): ${e.message}")
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
