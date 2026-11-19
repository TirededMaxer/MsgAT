package org.msgat.core.network

import android.os.SystemClock
import kotlinx.coroutines.cancelAndJoin
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.AppDatabase
import org.msgat.model.GhostPacket
import org.msgat.ui.chat.SyncState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class NettyClient(
    context: android.content.Context,
    private val prefs: SecurePrefs,
    database: AppDatabase,
    onMessageCommitted: suspend (String,String,Boolean,Boolean) -> Unit
) {
    private val _connectionState = MutableStateFlow(SyncState.DISCONNECTED)
    val connectionState: StateFlow<SyncState> = _connectionState.asStateFlow()
    private val _networkStatus = MutableStateFlow(RelayNetworkStatus.UNKNOWN)
    internal val networkStatus = _networkStatus.asStateFlow()

    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var workerGroup: NioEventLoopGroup? = null
    @Volatile private var channel: Channel? = null
    @Volatile internal var connectedEndpoint: RelayEndpoint? = null
        private set
    internal val connectionEpoch: Long get() = generation.get()

    private var isManualDisconnect = true
    private var transportClosed = false
    private var retryAttempt = 0
    private var pendingChannel: Channel? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var initialReplayWatch: Job? = null
    private val transportLock = Any()
    private val closeLock = Mutex()
    private var mailboxProgressAt = 0L
    private var lastMailboxRecoveryAt: Long? = null
    internal data class PushRelay(val epoch: Long, val endpoint: RelayEndpoint, val supported: Boolean)
    private val _pushRelay = MutableStateFlow<PushRelay?>(null)
    internal val pushRelay = _pushRelay.asStateFlow()
    private data class PushRequest(val nonce: ByteArray, val reply: CompletableDeferred<ByteArray?>)
    private val pushRequests = Mutex()
    private var pushRequest: PushRequest? = null
    internal val webPreviews=WebPreviewFetcher()
    internal val objects=org.msgat.core.media.EncryptedObjectStore(context,database,prefs)
    val drafts = org.msgat.data.db.ConversationDraftStore(database, scope)
    internal val contacts=org.msgat.data.db.ContactDirectoryStore(database,prefs)
    val conversationIndex = org.msgat.data.db.ConversationIndexStore(database) { token ->
        runCatching { org.msgat.NotificationHelper.dismissMessageNotifications(context, listOf(token)) }
    }
    internal val stickers=org.msgat.data.db.StickerLibraryStore(database,objects)
    internal val actions=org.msgat.data.db.MessageActionsStore(database,prefs,objects)

    val secureMessaging = SecureMessaging(database,prefs,
        send = { epoch, packet -> synchronized(transportLock) { generation.get() == epoch && sendPacket(packet) } },
        stateChanged = ::reportState,
        mailboxProgress = ::recordMailboxProgress,
        closeTransport = ::closeTransportForEpoch,
        onMessageCommitted = onMessageCommitted,
        onContactsChanged = contacts::invalidate,
        objects=objects,
        actions=actions,
        connection={generation.get() to connectedEndpoint},
        connected={_connectionState.value==SyncState.SYNCED}
    )
    internal val liveLocations = org.msgat.core.location.LiveLocationCoordinator(context.applicationContext, secureMessaging)

    fun sendProfile() {
        secureMessaging.broadcastProfile()
    }

    private fun reportState(epoch: Long, state: SyncState) = synchronized(transportLock) {
        if (generation.get() != epoch) return@synchronized
        if (state == SyncState.SYNCED) retryAttempt = 0
        if (state != SyncState.SYNCING) {
            initialReplayWatch?.cancel()
            initialReplayWatch = null
        }
        _connectionState.value = state
    }

    private fun watchInitialReplayLocked(epoch: Long) {
        initialReplayWatch?.cancel()
        initialReplayWatch = scope.launch {
            val idleLimit = 40_000L
            var waitMillis = idleLimit
            while (true) {
                delay(waitMillis)
                waitMillis = synchronized(transportLock) {
                    if (generation.get() != epoch || transportClosed || _connectionState.value != SyncState.SYNCING) return@launch
                    val remaining = idleLimit - (SystemClock.elapsedRealtime() - mailboxProgressAt)
                    if (remaining <= 0) {
                        (channel ?: pendingChannel)?.close()
                        return@launch
                    }
                    remaining
                }
            }
        }
    }

    private fun closeTransportForEpoch(epoch: Long) {
        synchronized(transportLock) {
            if (generation.get() == epoch) channel?.close()
        }
    }

    private fun recordMailboxProgress(epoch: Long) = synchronized(transportLock) {
        if (generation.get() == epoch) mailboxProgressAt = SystemClock.elapsedRealtime()
    }

    internal fun recoverStalledMailbox(epoch: Long, idleMillis: Long): Boolean = synchronized(transportLock) {
        val now = SystemClock.elapsedRealtime()
        if (generation.get() != epoch || transportClosed || isManualDisconnect ||
            !_networkStatus.value.canConnect || channel?.isActive != true ||
            now - mailboxProgressAt < idleMillis ||
            lastMailboxRecoveryAt?.let { now - it < 60_000L } == true) return@synchronized false
        lastMailboxRecoveryAt = now
        retireTransportLocked()
        connectLocked()
        true
    }

    private fun calculateBackoffDelayMs(): Long {
        val baseDelay = (1000L * (1 shl kotlin.math.min(retryAttempt, 5))).coerceAtMost(30000L)
        val jitter = kotlin.random.Random.nextLong(0, 1000)
        return baseDelay + jitter
    }

    private fun scheduleReconnectLocked() {
        if (transportClosed || isManualDisconnect || !_networkStatus.value.canConnect || !isConfigured()) return
        if (reconnectJob?.isActive == true) return
        val delayMs = calculateBackoffDelayMs()
        val expectedGeneration=generation.get()
        retryAttempt++
        android.util.Log.i("MsgAT_Netty", "Scheduling reconnect attempt #$retryAttempt in ${delayMs}ms")
        _connectionState.value = SyncState.DISCONNECTED
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            val owner=kotlinx.coroutines.currentCoroutineContext()[Job]
            synchronized(transportLock) {
                if (reconnectJob !== owner) return@synchronized
                reconnectJob = null
                if (generation.get() == expectedGeneration) connectLocked()
            }
        }
    }

    // Checks whether the client possesses the necessary configuration to initiate a connection.
    fun isConfigured(): Boolean = prefs.isConfigured()

    // Bootstraps and establishes an asynchronous Netty TCP connection to the relay server.
    fun connect() = synchronized(transportLock) {
        if (transportClosed) return@synchronized
        isManualDisconnect = false
        connectLocked()
    }

    internal fun defaultNetworkChanged(status: RelayNetworkStatus) = synchronized(transportLock) {
        val previous = _networkStatus.value
        if (previous == status) return@synchronized
        _networkStatus.value = status
        if (transportClosed || isManualDisconnect) return@synchronized
        if (!status.canConnect && previous.canConnect) retireTransportLocked()
        if (status == RelayNetworkStatus.AVAILABLE) {
            retryAttempt = 0
            connectLocked()
        }
    }

    internal fun retryDefaultNetwork() = synchronized(transportLock) {
        if (_networkStatus.value != RelayNetworkStatus.AVAILABLE || reconnectJob == null) return@synchronized
        retryAttempt = 0
        connectLocked()
    }

    internal fun defaultNetworkRouteChanged(replacedRoute: Boolean, removedAddresses: Set<InetAddress>) =
        synchronized(transportLock) {
            if (transportClosed || isManualDisconnect || _networkStatus.value != RelayNetworkStatus.AVAILABLE)
                return@synchronized
            val source = ((channel ?: pendingChannel)?.localAddress() as? InetSocketAddress)?.address
            if (replacedRoute || (source != null && source in removedAddresses)) {
                retireTransportLocked()
                retryAttempt = 0
                connectLocked()
            } else if (reconnectJob != null) {
                retryAttempt = 0
                connectLocked()
            }
        }

    private fun connectLocked() {
        if (transportClosed || isManualDisconnect || !_networkStatus.value.canConnect || !isConfigured() ||
            channel?.isActive == true || pendingChannel != null) return
        if (channel != null) retireTransportLocked()
        reconnectJob?.cancel()
        reconnectJob = null

        val currentGeneration = generation.incrementAndGet()
        mailboxProgressAt = SystemClock.elapsedRealtime()
        try {
            val endpoint = RelayEndpoint.from(prefs)
            val group = workerGroup ?: NioEventLoopGroup(1).also { workerGroup = it }
            val bootstrap = Bootstrap()
            val ip = endpoint.host
            val port = endpoint.port

            bootstrap.group(group)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,io.netty.channel.WriteBufferWaterMark(1024*1024,4*1024*1024))
                .handler(object : ChannelInitializer<SocketChannel>() {
                    // Configures the Netty channel pipeline with encoders, decoders, and handlers.
                    override fun initChannel(ch: SocketChannel) {
                        val p = ch.pipeline()

                        p.addLast(endpoint.handler(ch.alloc()))

                        p.addLast(io.netty.handler.timeout.IdleStateHandler(240, 180, 0, java.util.concurrent.TimeUnit.SECONDS))

                        p.addLast(GhostPacketCodec())

                        p.addLast(GhostClientHandler(prefs,
                            onAuthenticated = { supported, directoryEpoch ->
                                synchronized(transportLock) {
                                    if (generation.get() == currentGeneration) {
                                        mailboxProgressAt = SystemClock.elapsedRealtime()
                                        watchInitialReplayLocked(currentGeneration)
                                        _pushRelay.value = PushRelay(currentGeneration, endpoint, supported)
                                        secureMessaging.beginSync(currentGeneration, directoryEpoch)
                                    }
                                }
                            },
                            onSecurePacket = { packet -> if (generation.get()==currentGeneration) secureMessaging.handle(currentGeneration,packet) },
                            onPushResult = { packet -> acceptPushResult(currentGeneration, packet) },
                            onDisconnected = { secureMessaging.disconnected(currentGeneration) },
                            onStateChange = { newState -> reportState(currentGeneration, newState) }
                        ))
                    }
                })

            val attempt = bootstrap.connect(ip, port)
            val socket = attempt.channel()
            pendingChannel = socket
            attempt.addListener { future ->
                synchronized(transportLock) {
                    if (generation.get() != currentGeneration || pendingChannel !== socket) {
                        socket.close()
                        return@synchronized
                    }
                    pendingChannel = null
                    if (future.isSuccess && socket.isActive) {
                        channel = socket
                        connectedEndpoint = endpoint
                        socket.closeFuture().addListener {
                            synchronized(transportLock) {
                                if (generation.get() == currentGeneration && channel === socket) {
                                    secureMessaging.disconnected(currentGeneration)
                                    channel = null
                                    connectedEndpoint = null
                                    clearPushRelay()
                                    _connectionState.value = SyncState.DISCONNECTED
                                    scheduleReconnectLocked()
                                }
                            }
                        }
                    } else {
                        socket.close()
                        scheduleReconnectLocked()
                    }
                }
            }

        } catch (e: Exception) {
            pendingChannel?.close()
            pendingChannel = null
            android.util.Log.w("MsgAT_Netty", "Connection setup failed: ${e.javaClass.simpleName}")
            _connectionState.value = SyncState.DISCONNECTED
            scheduleReconnectLocked()
        }
    }

    // Close only the connection. Network changes and retries share the same event loop.
    fun disconnect() = synchronized(transportLock) {
        isManualDisconnect = true
        retireTransportLocked()
    }

    private fun retireTransportLocked() {
        val previousGeneration=generation.getAndIncrement()
        secureMessaging.disconnected(previousGeneration)
        reconnectJob?.cancel()
        reconnectJob = null
        initialReplayWatch?.cancel()
        initialReplayWatch = null
        val pending = pendingChannel
        pendingChannel = null
        val current = channel
        channel = null
        connectedEndpoint = null
        clearPushRelay()
        pending?.close()
        current?.close()
        _connectionState.value = SyncState.DISCONNECTED
    }

    private suspend fun stopRelay() {
        val group = synchronized(transportLock) {
            isManualDisconnect = true
            retireTransportLocked()
            workerGroup.also { workerGroup = null }
        } ?: return
        suspendCancellableCoroutine<Unit> { continuation ->
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).addListener { continuation.resume(Unit) }
        }
    }

    suspend fun close() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        closeLock.withLock {
            val first = synchronized(transportLock) {
                if (transportClosed) false else {
                    transportClosed = true
                    true
                }
            }
            if (!first) return@withLock
            suspend fun stop(component: String, operation: suspend () -> Unit) {
                try { operation() }
                catch (error: Exception) {
                    android.util.Log.w("MsgAT_Netty", "Closing $component failed (${error.javaClass.simpleName})")
                }
            }
            stop("live location") { liveLocations.close() }
            stop("relay") { stopRelay() }
            stop("web previews") { webPreviews.close() }
            stop("messaging") { secureMessaging.close() }
            stop("thumbnails") { objects.closeThumbnailWork() }
            stop("recordings") { objects.captures.close() }
            stop("conversation index") { conversationIndex.close() }
            stop("contacts") { contacts.close() }
            stop("stickers") { stickers.close() }
            stop("message actions") { actions.close() }
            stop("drafts") { drafts.close() }
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun clearPushRelay() {
        _pushRelay.value = null
        pushRequest?.reply?.complete(null)
    }

    private fun acceptPushResult(epoch: Long, packet: GhostPacket) = synchronized(transportLock) {
        val request = pushRequest ?: return@synchronized
        if (_pushRelay.value?.epoch != epoch || !request.nonce.contentEquals(packet.nonceOrIv) ||
            packet.payloadLen !in 1..256) return@synchronized
        request.reply.complete(packet.encryptedData)
    }

    internal suspend fun updateAndroidPush(relay: PushRelay, identity: String, bytes: ByteArray): ByteArray? = pushRequests.withLock {
        if (!relay.supported || !relay.endpoint.verifiesServer) return@withLock null
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val request = PushRequest(nonce, CompletableDeferred())
        try {
            synchronized(transportLock) {
                if (_pushRelay.value != relay) return@withLock null
                pushRequest = request
                if (!sendPacket(GhostPacket(GhostPacket.TYPE_V2_PUSH_UPDATE, identity, nonce, bytes, null))) return@withLock null
            }
            withTimeoutOrNull(7_000) { request.reply.await() }
        } finally {
            synchronized(transportLock) { if (pushRequest === request) pushRequest = null }
        }
    }

    // Asynchronously flushes a GhostPacket through the pipeline to the server.
    fun sendPacket(packet: GhostPacket): Boolean {
        val target=channel ?: return false
        if (!target.isActive || !target.isWritable || packet.payloadLen>GhostPacket.MAX_PACKET_SIZE-105) return false
        val key=io.netty.util.AttributeKey.valueOf<java.util.concurrent.atomic.AtomicLong>("MSGAT_PENDING_BYTES")
        val count=target.attr(key).get() ?: java.util.concurrent.atomic.AtomicLong().let { target.attr(key).setIfAbsent(it) ?: it }
        val cost=maxOf(4096,105+packet.payloadLen).toLong()
        if (count.addAndGet(cost)>4L*1024*1024) { count.addAndGet(-cost); target.close(); return false }
        val promise=target.newPromise()
        promise.addListener { result -> count.addAndGet(-cost); if (!result.isSuccess) target.close() }
        return try { target.writeAndFlush(packet,promise); true }
        catch (error: RuntimeException) { promise.tryFailure(error); false }
    }
}
