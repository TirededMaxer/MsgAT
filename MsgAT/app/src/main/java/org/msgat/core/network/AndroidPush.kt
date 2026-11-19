// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.msgat.MsgATApp
import org.msgat.core.crypto.RootKeyHierarchy
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.ui.chat.SyncState
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Optional distributor wake-ups. Message delivery still goes through the Signal mailbox. */
internal class AndroidPush(
    private val app: MsgATApp,
    private val prefs: SecurePrefs,
    private val netty: NettyClient
) {
    enum class Status {
        OFF, WAITING_ENDPOINT, WAITING_RELAY, VERIFYING, ACTIVE, EXPIRED,
        TLS_REQUIRED, UNSUPPORTED, REJECTED, DISTRIBUTOR_ERROR, RELAY_CHANGED
    }
    data class State(val enabled: Boolean, val status: Status)

    private data class Endpoint(val url: String, val publicKey: String, val auth: String)
    private data class Registration(
        val instance: String,
        val generation: String,
        val relay: RelayEndpoint,
        val endpoint: Endpoint? = null,
        val expiresAt: Long = 0,
        val challenge: String? = null,
        val confirmed: String? = null,
        val seen: Map<String, Long> = emptyMap()
    ) {
        fun serialize(): String = JSONObject().apply {
            put("instance", instance)
            put("generation", generation)
            put("host", relay.host)
            put("port", relay.port)
            put("pin", relay.certificatePin)
            put("development", relay.allowDevelopmentCertificate)
            endpoint?.let { put("endpoint", it.url); put("publicKey", it.publicKey); put("auth", it.auth) }
            put("expires", expiresAt)
            put("challenge", challenge)
            put("confirmed", confirmed)
            put("seen", JSONObject(seen))
        }.toString()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Mutex()
    private val exchangeLock = Mutex()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var registration: Registration? = null
    private var verifiedEpoch: Long? = null
    private val _state = MutableStateFlow(State(false, Status.OFF))
    val state = _state.asStateFlow()
    private val identity = prefs.myUuid

    init {
        try {
            registration = prefs.readPushSubscription()?.let(::read)
            if (registration != null) status(Status.WAITING_ENDPOINT)
        } catch (_: org.json.JSONException) {
            status(Status.DISTRIBUTOR_ERROR)
        } catch (_: IllegalArgumentException) {
            status(Status.DISTRIBUTOR_ERROR)
        } catch (_: ClassCastException) {
            status(Status.DISTRIBUTOR_ERROR)
        }
        scope.launch { netty.pushRelay.collect { signal() } }
        scope.launch {
            while (isActive) {
                val retry = try { reconcile() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    Log.w("MsgAT_Push", "Registration will retry (" + error.javaClass.simpleName + ")")
                    30_000L
                }
                if (retry == null) changes.receive()
                else withTimeoutOrNull(retry) { changes.receive() }
            }
        }
    }

    fun distributors(): List<String> = UnifiedPush.getDistributors(app)

    private fun hasSelectedDistributor(): Boolean =
        UnifiedPush.getSavedDistributor(app)?.let { it in distributors() } == true

    fun refreshDistributor() = launch {
        val current = stateLock.withLock { registration } ?: return@launch
        if (!hasSelectedDistributor()) distributorError(current.instance)
        else signal()
    }

    fun retryRegistration() = launch {
        val missingDistributor = stateLock.withLock {
            val current = registration ?: return@withLock null
            if (!hasSelectedDistributor()) return@withLock current.instance
            if (current.relay != currentRelay()) {
                status(Status.RELAY_CHANGED)
            } else if (current.endpoint == null) {
                register(current.instance)
            }
            null
        }
        if (missingDistributor != null) distributorError(missingDistributor)
        else signal()
    }

    fun enable(distributor: String) = launch {
        stateLock.withLock {
            if (registration != null) return@withLock
            val relay = currentRelay()
            if (relay == null || !relay.verifiesServer) { status(Status.TLS_REQUIRED); return@withLock }
            if (distributor !in distributors()) { status(Status.DISTRIBUTOR_ERROR); return@withLock }
            val subscription = Registration(newID(), newID(), relay)
            save(subscription)
            try {
                UnifiedPush.saveDistributor(app, distributor)
                register(subscription.instance)
            } catch (_: RuntimeException) { status(Status.DISTRIBUTOR_ERROR) }
        }
        signal()
    }

    private fun register(instance: String) {
        status(Status.WAITING_ENDPOINT)
        try { UnifiedPush.register(app, instance = instance) }
        catch (_: RuntimeException) { status(Status.DISTRIBUTOR_ERROR) }
    }

    fun disable() = launch {
        val removed = stateLock.withLock {
            val previous = registration ?: return@withLock null
            save(null)
            PushCatchUpJob.cancel(app)
            runCatching { UnifiedPush.unregister(app, instance = previous.instance) }
            status(Status.OFF)
            previous
        }
        if (removed != null) unsubscribe(removed)
    }

    fun endpointChanged(instance: String, update: PushEndpoint) = launch {
        val keySet = update.pubKeySet ?: return@launch
        if (update.url.length !in 1..1024 || keySet.pubKey.length != 87 || keySet.auth.length != 22) return@launch
        stateLock.withLock {
            val current = registration?.takeIf { it.instance == instance } ?: return@withLock
            val endpoint = Endpoint(update.url, keySet.pubKey, keySet.auth)
            if (current.endpoint == endpoint) return@withLock
            save(current.copy(generation = newID(), endpoint = endpoint, expiresAt = 0, challenge = null,
                confirmed = null, seen = emptyMap()))
            PushCatchUpJob.cancel(app)
            status(Status.WAITING_RELAY)
        }
        signal()
    }

    fun distributorError(instance: String) = launch {
        val removed = stateLock.withLock {
            val current = registration?.takeIf { it.instance == instance } ?: return@withLock null
            save(null)
            PushCatchUpJob.cancel(app)
            status(Status.DISTRIBUTOR_ERROR)
            current
        }
        if (removed != null) unsubscribe(removed)
    }

    fun received(instance: String, message: PushMessage) = launch {
        if (!message.decrypted || message.content.size != 58) return@launch
        stateLock.withLock {
            val current = registration?.takeIf { it.instance == instance && it.endpoint != null && it.relay == currentRelay() }
                ?: return@withLock
            val buffer = ByteBuffer.wrap(message.content)
            if (buffer.get() != 1.toByte()) return@withLock
            val kind = buffer.get().toInt()
            val expires = buffer.long
            val now = now()
            val proof = ByteArray(16).also { buffer.get(it) }
            if (kind !in 1..2 || expires <= now || expires > now + 180) return@withLock
            val valid = withWakeKey(current) { key ->
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(key, "HmacSHA256"))
                mac.update(DOMAIN)
                mac.update(message.content, 0, 26)
                MessageDigest.isEqual(mac.doFinal(), message.content.copyOfRange(26, 58))
            }
            if (!valid) return@withLock
            val nonce = encode(proof)
            val seen = current.seen.filterValues { it > now }
            if (nonce in seen || seen.size >= 32 || (kind == 1 && current.confirmed == nonce)) return@withLock
            val queuedRegistration = if (kind == 1 && current.challenge != nonce) {
                current.copy(challenge = nonce, expiresAt = 0).also(::save)
            } else current
            if (kind == 1) {
                signal()
            }
            val remainingMillis = (expires * 1_000L - Instant.now().toEpochMilli()).coerceAtMost(120_000L)
            if (PushCatchUpJob.schedule(app, current.generation, remainingMillis)) {
                save(queuedRegistration.copy(seen = seen + (nonce to expires)))
            }
        }
    }

    private suspend fun reconcile(): Long? = exchangeLock.withLock exchange@ {
        val current = stateLock.withLock { registration } ?: return@exchange null
        if (current.relay != currentRelay()) {
            report(current, Status.RELAY_CHANGED)
            return@exchange null
        }
        val endpoint = current.endpoint ?: return@exchange null
        val relay = netty.pushRelay.value
        if (relay == null || relay.endpoint != current.relay) {
            val now = now()
            if (current.expiresAt > now) {
                report(current, Status.ACTIVE)
                return@exchange (current.expiresAt - now).coerceAtMost(3_600) * 1_000
            }
            report(current, if (current.expiresAt > 0) Status.EXPIRED else Status.WAITING_RELAY)
            return@exchange null
        }
        if (!relay.supported) {
            report(current, Status.UNSUPPORTED)
            return@exchange null
        }
        val challenge = current.challenge
        if (challenge == null && verifiedEpoch == relay.epoch && current.expiresAt > now() + 7 * 86400) {
            report(current, Status.ACTIVE)
            return@exchange 3_600_000L
        }
        val command = if (challenge != null) request("confirm", current).put("challenge", challenge)
        else withWakeKey(current) { key ->
            request("register", current).put("endpoint", endpoint.url).put("publicKey", endpoint.publicKey)
                .put("auth", endpoint.auth).put("wakeKey", encode(key))
        }
        val result = send(relay, command)
        stateLock.withLock {
            val latest = registration?.takeIf { it.generation == current.generation && it.challenge == challenge }
                ?: return@withLock 250L
            when (result?.optString("status")) {
                "active" -> {
                    verifiedEpoch = relay.epoch
                    save(latest.copy(expiresAt = checkNotNull(result).getLong("expires"), challenge = null,
                        confirmed = challenge ?: latest.confirmed))
                    status(Status.ACTIVE)
                    3_600_000L
                }
                "pending" -> { status(Status.VERIFYING); 30_000L }
                "gone" -> {
                    save(latest.copy(expiresAt = 0, challenge = null))
                    status(Status.WAITING_RELAY)
                    1_000L
                }
                "invalid" -> { status(Status.REJECTED); null }
                else -> { status(Status.WAITING_RELAY); 30_000L }
            }
        }
    }

    /** The job owns the overall deadline. A slow but progressing mailbox pass keeps its nonce. */
    suspend fun catchUp(generation: String) {
        val operation = scope.async {
            val current = stateLock.withLock { registration?.takeIf { it.generation == generation } } ?: return@async
            if (current.relay != currentRelay()) return@async
            app.withPushReception {
                while (isActive) {
                    val latest = stateLock.withLock { registration?.takeIf { it.generation == generation } }
                    if (latest == null || latest.relay != currentRelay()) return@withPushReception
                    val relay = withTimeoutOrNull(MAILBOX_IDLE_MS) {
                        combine(netty.pushRelay, netty.connectionState) { relay, state ->
                            relay?.takeIf { it.endpoint == latest.relay && state == SyncState.SYNCED }
                        }.first { it != null }
                    }
                    if (relay == null) {
                        netty.recoverStalledMailbox(netty.connectionEpoch, MAILBOX_IDLE_MS)
                        continue
                    }
                    if (!relay.supported || !relay.endpoint.verifiesServer ||
                        stateLock.withLock { registration?.generation } != generation) return@withPushReception
                    if (latest.challenge != null) reconcile()
                    if (pullWakeMailbox(relay.epoch)) return@withPushReception
                    delay(500)
                }
            }
        }
        return try { operation.await() }
        finally { withContext(NonCancellable) { operation.cancelAndJoin() } }
    }

    private suspend fun pullWakeMailbox(epoch: Long): Boolean = coroutineScope {
        val pass = async { netty.secureMessaging.pullForPush(epoch) }
        val retired = async { netty.pushRelay.first { it?.epoch != epoch } }
        try {
            var result: Boolean? = null
            while (result == null) {
                result = withTimeoutOrNull(MAILBOX_IDLE_MS) {
                    select<Boolean> {
                        pass.onAwait { it }
                        retired.onAwait { false }
                    }
                }
                if (result == null && netty.recoverStalledMailbox(epoch, MAILBOX_IDLE_MS)) return@coroutineScope false
            }
            result == true
        } finally {
            pass.cancel()
            retired.cancel()
        }
    }

    suspend fun closeForIdentityReset() {
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private suspend fun unsubscribe(current: Registration) = exchangeLock.withLock {
        val relay = netty.pushRelay.value?.takeIf { it.endpoint == current.relay && it.supported } ?: return@withLock
        send(relay, request("remove", current))
    }

    private suspend fun send(relay: NettyClient.PushRelay, command: JSONObject): JSONObject? {
        val bytes = command.toString().toByteArray(Charsets.UTF_8)
        return netty.updateAndroidPush(relay, identity, bytes)?.let { JSONObject(String(it, Charsets.UTF_8)) }
            ?.takeIf { it.optString("generation") == command.optString("generation") }
    }

    private fun request(operation: String, current: Registration) =
        JSONObject().put("op", operation).put("generation", current.generation)

    private suspend fun report(current: Registration, value: Status) = stateLock.withLock {
        if (registration?.generation == current.generation) status(value)
    }

    private fun status(value: Status) { _state.value = State(registration != null, value) }

    private fun save(value: Registration?) {
        prefs.writePushSubscription(value?.serialize())
        if (registration?.generation != value?.generation) verifiedEpoch = null
        registration = value
    }

    private fun currentRelay() = if (prefs.isConfigured()) runCatching { RelayEndpoint.from(prefs) }.getOrNull() else null
    private fun signal() { changes.trySend(Unit) }

    private fun launch(operation: suspend () -> Unit) {
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w("MsgAT_Push", "Push update failed (" + error.javaClass.simpleName + ")") }
        }
    }

    private fun <T> withWakeKey(current: Registration, operation: (ByteArray) -> T): T {
        val key = prefs.withRootSeed { RootKeyHierarchy.derive(it, "android/wake/" + current.generation) }
        return try { operation(key) } finally { key.fill(0) }
    }

    private fun read(serialized: String): Registration {
        val data = JSONObject(serialized)
        val relay = RelayEndpoint(data.getString("host"), data.getInt("port"), data.getString("pin"), data.getBoolean("development"))
        val endpoint = if (data.has("endpoint")) Endpoint(data.getString("endpoint"), data.getString("publicKey"), data.getString("auth")) else null
        val seen = data.getJSONObject("seen")
        return Registration(data.getString("instance"), data.getString("generation"), relay, endpoint,
            data.getLong("expires"), data.optString("challenge").ifEmpty { null }, data.optString("confirmed").ifEmpty { null },
            seen.keys().asSequence().associateWith(seen::getLong))
    }

    companion object {
        private const val MAILBOX_IDLE_MS = 25_000L
        private val DOMAIN = "MsgAT/v2/android-wake".toByteArray(Charsets.US_ASCII)
        private fun newID() = UUID.randomUUID().toString().replace("-", "")
        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun now() = Instant.now().epochSecond

        fun destroySystemRegistration(context: Context) {
            PushCatchUpJob.cancel(context)
            runCatching { UnifiedPush.removeDistributor(context) }
        }
    }
}
