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
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.MsgDao
import org.msgat.model.GhostPacket
import org.msgat.ui.chat.SyncState

class NettyClient(
    private val prefs: SecurePrefs,
    private val msgDao: MsgDao
) {
    // 暴露给 ViewModel 和 UI 层监听的状态流
    private val _connectionState = MutableStateFlow(SyncState.DISCONNECTED)
    val connectionState: StateFlow<SyncState> = _connectionState.asStateFlow()

    private var workerGroup: NioEventLoopGroup? = null
    private var channel: Channel? = null

    fun isConfigured(): Boolean = prefs.isConfigured()

    fun connect() {
        if (!isConfigured() || channel?.isActive == true) return

        val ip = prefs.serverIp!!
        val port = prefs.serverPort
        val myUuid = prefs.myUuid

        workerGroup = NioEventLoopGroup()
        val bootstrap = Bootstrap()

        try {
            // 客户端必须盲信任所有证书
            val sslCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build()

            bootstrap.group(workerGroup)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val p = ch.pipeline()

                        // 1. TLS 解密层
                        p.addLast(sslCtx.newHandler(ch.alloc(), ip, port))
                        // 2. 500KB 破甲层
                        p.addLast(GhostPacketCodec())
                        // 3. 业务处理层
                        p.addLast(GhostClientHandler(myUuid, prefs.getOrCreatePrivateKey(), msgDao) { newState ->
                            // 收到状态更新，推送到 Flow 让 UI 刷新
                            _connectionState.value = newState
                        })
                    }
                })

            bootstrap.connect(ip, port).addListener { future ->
                if (future.isSuccess) {
                    android.util.Log.i("MsgAT_Netty", "Connected successfully to $ip:$port")
                    channel = (future as io.netty.channel.ChannelFuture).channel()
                } else {
                    android.util.Log.e("MsgAT_Netty", "Connection failed to $ip:$port", future.cause())
                    _connectionState.value = SyncState.DISCONNECTED
                    workerGroup?.shutdownGracefully()
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("MsgAT_Netty", "Exception in connect()", e)
            _connectionState.value = SyncState.DISCONNECTED
            workerGroup?.shutdownGracefully()
        }
    }

    fun disconnect() {
        channel?.close()
        workerGroup?.shutdownGracefully()
        _connectionState.value = SyncState.DISCONNECTED
    }

    fun sendPacket(packet: GhostPacket): Boolean {
        return if (channel?.isActive == true) {
            channel?.writeAndFlush(packet)
            true
        } else {
            false
        }
    }
}