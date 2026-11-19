// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.location

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.msgat.core.network.LiveLocationShare
import org.msgat.core.network.LocationPoint
import org.msgat.core.network.SecureMessaging
import org.msgat.data.db.DraftSendCommit

internal class LiveLocationCoordinator(private val context: Context, private val messaging: SecureMessaging) {
    private data class ActiveShare(val share: LiveLocationShare, val elapsedDeadline: Long) {
        fun remaining(): Long = minOf(share.expiresAt - System.currentTimeMillis(), elapsedDeadline - SystemClock.elapsedRealtime())
    }

    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + lifetime)
    private val gate = Mutex()
    private val shares = mutableMapOf<String, ActiveShare>()
    private val _active = MutableStateFlow<Map<String, LiveLocationShare>>(emptyMap())
    val active = _active.asStateFlow()
    private var foreground: LiveLocationService? = null
    private var starting: CompletableDeferred<Boolean>? = null
    private var sampler: Job? = null
    private var closed = false

    suspend fun start(peer: String, point: LocationPoint, duration: Long, label: String?, replyId: String?,
                      draft: DraftSendCommit, silent: Boolean): Boolean = withContext(Dispatchers.Main.immediate) {
        gate.withLock {
            if (closed || shares.containsKey(peer) || !hasLocationPermission(context)) return@withLock false
            try {
                if (!ensureForeground()) return@withLock false
                currentCoroutineContext().ensureActive()
                val service = foreground ?: return@withLock false
                withContext(NonCancellable) commit@ {
                    val share = messaging.startLiveLocation(peer, point, duration, label, replyId, draft, silent)
                        ?: return@commit false
                    if (foreground !== service) {
                        messaging.stopLiveLocation(peer, share.messageId)
                        return@commit true
                    }
                    val remaining = (share.expiresAt - System.currentTimeMillis()).coerceIn(0, duration)
                    shares[peer] = ActiveShare(share, SystemClock.elapsedRealtime() + remaining)
                    publish()
                    if (sampler == null) sampler = scope.launch { sample() }
                    true
                }
            } finally {
                finishIfIdle()
            }
        }
    }

    suspend fun stop(peer: String, messageId: String): Boolean = withContext(Dispatchers.Main.immediate + NonCancellable) {
        gate.withLock {
            if (closed) return@withLock false
            if (shares[peer]?.share?.messageId == messageId) shares.remove(peer)
            publish()
            finishIfIdle()
            messaging.stopLiveLocation(peer, messageId)
        }
    }

    suspend fun close() {
        withContext(Dispatchers.Main.immediate + NonCancellable) {
            gate.withLock {
                closed = true
                stopAllLocked()
            }
            lifetime.cancelAndJoin()
        }
    }

    fun attached(service: LiveLocationService) {
        if (closed || starting == null && shares.isEmpty()) {
            service.stopSelf()
            return
        }
        foreground = service
        starting?.complete(true)
    }

    fun startFailed() { starting?.complete(false) }

    fun detached(service: LiveLocationService) {
        if (foreground !== service) return
        foreground = null
        scope.launch { gate.withLock { if (foreground == null) stopAllLocked() } }
    }

    fun stopAll(service: LiveLocationService) {
        scope.launch { gate.withLock { if (foreground === service) stopAllLocked() } }
    }

    private suspend fun ensureForeground(): Boolean {
        if (foreground != null) return true
        val result = CompletableDeferred<Boolean>()
        starting = result
        val intent = Intent(context, LiveLocationService::class.java)
        return try {
            ContextCompat.startForegroundService(context, intent)
            withTimeoutOrNull(5_000) { result.await() } == true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            starting = null
            if (foreground == null) context.stopService(intent)
        }
    }

    private suspend fun sample() {
        while (currentCoroutineContext().isActive) {
            val wait = gate.withLock {
                shares.entries.removeAll { it.value.remaining() <= 0 }
                publish()
                finishIfIdle()
                shares.values.minOfOrNull { it.remaining() } ?: return
            }
            delay(minOf(wait, 15_000).coerceAtLeast(1))
            val window = gate.withLock {
                shares.entries.removeAll { it.value.remaining() <= 0 }
                publish()
                finishIfIdle()
                shares.values.minOfOrNull { it.remaining() } ?: return
            }
            if (!hasLocationPermission(context) || context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
                gate.withLock { stopAllLocked() }
                return
            }
            val location = try {
                withTimeoutOrNull(window.coerceIn(1, 12_000)) { currentLocation(context, providerTimeoutMs = 3_000) }
            } catch (_: SecurityException) {
                gate.withLock { stopAllLocked() }
                return
            } catch (_: IllegalArgumentException) {
                null
            }
            val point = location?.let { locationFromDegrees(it.latitude, it.longitude) } ?: continue
            gate.withLock {
                val iterator = shares.iterator()
                while (iterator.hasNext()) {
                    val (peer, session) = iterator.next()
                    if (session.remaining() <= 0 || !messaging.updateLiveLocation(peer, session.share.messageId, point)) {
                        iterator.remove()
                    }
                }
                publish()
                finishIfIdle()
            }
        }
    }

    private suspend fun stopAllLocked() {
        val stopped = shares.mapValues { it.value.share.messageId }
        shares.clear()
        publish()
        withContext(NonCancellable) {
            finishIfIdle()
            for ((peer, messageId) in stopped) messaging.stopLiveLocation(peer, messageId)
        }
    }

    private fun publish() { _active.value = shares.mapValues { it.value.share } }

    private fun finishIfIdle() {
        if (shares.isNotEmpty()) return
        sampler?.cancel()
        sampler = null
        val service = foreground
        foreground = null
        service?.stopSelf()
    }
}
