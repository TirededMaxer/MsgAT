package org.msgat

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.core.network.NettyClient
import org.msgat.core.network.MsgATService
import org.msgat.core.network.NetworkMonitor
import org.msgat.core.network.AndroidPush
import org.msgat.data.db.AppDatabase

class MsgATApp : Application() {
    enum class StartupState { LOADING, READY, FAILED }
    private val _startupState=MutableStateFlow(StartupState.LOADING)
    val startupState=_startupState.asStateFlow()
    private val startupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var revocationWorker: Job? = null
    private var foregroundReceptionRequested = false
    private var backgroundReceiver: MsgATService? = null
    private var pushReceptionOwners = 0
    private var callReceptionRequested = false
    private var networkMonitor: NetworkMonitor? = null
    lateinit var securePrefs: SecurePrefs
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var nettyClient: NettyClient
        private set
    internal lateinit var androidPush: AndroidPush
        private set
    @Volatile var isAppInForeground=false
    @get:StringRes
    @Volatile var startupFailure: Int? = null
        private set
    @Volatile internal var canResetUnreadableIdentity = false
        private set
    @Volatile private var identityResetCommitted = false
    val isReady: Boolean get()=startupState.value==StartupState.READY && !identityResetCommitted
    suspend fun awaitStartup(): StartupState=startupState.first { it!=StartupState.LOADING }

    internal fun onPushStartup(action: (StartupState) -> Unit) {
        startupScope.launch { action(awaitStartup()) }
    }

    override fun onCreate() {
        super.onCreate()
        instance=this
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                isAppInForeground=true
                updateMessageReception()
                NotificationHelper.clearAllNotifications(applicationContext)
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                isAppInForeground = false
                updateMessageReception()
            }
        })
        startupScope.launch {
            try {
                org.msgat.core.crypto.SecurityCore.requireAvailable()
                AppDatabase.requireProfileLease(applicationContext)
                org.msgat.core.crypto.securestorage.IdentityLifecycle.resume(applicationContext)
                securePrefs = try {
                    SecurePrefs(applicationContext).also { it.withRootSeed { } }
                } catch (error: Exception) {
                    canResetUnreadableIdentity =
                        org.msgat.core.crypto.securestorage.IdentityLifecycle.canDiscardUnreadableIdentity(applicationContext)
                    throw error
                }
                // The helper actually opens/authenticates SQLCipher here, on IO,
                // before a chat screen or network client can become available.
                db=AppDatabase.getInstance(applicationContext)
                nettyClient=NettyClient(applicationContext,securePrefs,db) { sender,id,silent,pinned ->
                    if (!isAppInForeground) {
                        val notice = org.msgat.data.db.MessageNotificationStore.prepare(db, sender, id, silent, pinned)
                        if (notice != null && !isAppInForeground) NotificationHelper.showMessageNotification(applicationContext, notice)
                    }
                }
                val oldName=securePrefs.legacyNickname
                val oldAvatar=securePrefs.legacyAvatarPath
                val own=org.msgat.data.db.UserProfileStore.read(db.openHelper.readableDatabase,securePrefs.myUuid)
                securePrefs.installProfile(own)
                if(own==null && !oldName.isNullOrBlank()) {
                    val uri=oldAvatar?.let {if(it.startsWith("/"))android.net.Uri.fromFile(java.io.File(it)) else android.net.Uri.parse(it)}
                    val bytes=uri?.let {org.msgat.core.media.ProfileImages.jpeg(applicationContext,it)}
                    try {check(nettyClient.secureMessaging.updateProfile(oldName,bytes)){"Profile could not be secured"}}
                    finally {bytes?.fill(0)}
                }
                if(oldAvatar!=null && securePrefs.myNickname!=null) {
                    val old=java.io.File(oldAvatar).canonicalFile
                    if(old.parentFile==org.msgat.core.media.MediaStorageManager.getMediaDir(applicationContext).canonicalFile && old.name=="avatar_self.jpg")old.delete()
                }
                androidPush = AndroidPush(this@MsgATApp, securePrefs, nettyClient)
                revocationWorker = startupScope.launch {
                    while(kotlinx.coroutines.currentCoroutineContext().isActive) {
                        org.msgat.core.crypto.securestorage.IdentityLifecycle.deliver(applicationContext)
                        kotlinx.coroutines.delay(30_000)
                    }
                }
                _startupState.value=StartupState.READY
            } catch (_: org.msgat.core.crypto.securestorage.HardwareProtectionUnavailableException) {
                failed(R.string.ui_startup_hardware_unavailable)
            } catch (_: LinkageError) {
                failed(R.string.ui_startup_security_unavailable)
            } catch (_: Exception) {
                failed(R.string.ui_startup_vault_unavailable)
            }
        }
    }

    @MainThread
    internal fun requestForegroundReception() {
        foregroundReceptionRequested = true
        updateMessageReception(requestConnection = true)
    }

    @MainThread
    private fun stopReceptionForIdentityReset() {
        identityResetCommitted = true
        networkMonitor?.stopMonitoring()
        networkMonitor = null
        stopService(android.content.Intent(this, MsgATService::class.java))
    }

    internal suspend fun shutdownForIdentityReset() = withContext(Dispatchers.IO + NonCancellable) {
        try {
            withContext(Dispatchers.Main.immediate) {
                stopReceptionForIdentityReset()
                org.msgat.core.network.PushCatchUpJob.cancel(this@MsgATApp)
                org.msgat.core.call.CallManager.getInstance().endCallAndDrainVideo()
            }
        } finally {
            closeInitializedResources()
        }
    }

    @MainThread
    internal fun attachBackgroundReceiver(service: MsgATService) {
        backgroundReceiver = service
        updateMessageReception()
    }

    @MainThread
    internal fun detachBackgroundReceiver(service: MsgATService) {
        if (backgroundReceiver !== service) return
        backgroundReceiver = null
        updateMessageReception()
    }

    @MainThread
    private fun updateMessageReception(requestConnection: Boolean = false) {
        if (!isReady) return
        val foreground = isAppInForeground && foregroundReceptionRequested
        if ((foreground || backgroundReceiver != null || pushReceptionOwners > 0 || callReceptionRequested) && nettyClient.isConfigured()) {
            val existing = networkMonitor
            val monitor = existing ?: NetworkMonitor(applicationContext, nettyClient).also {
                networkMonitor = it
            }
            monitor.startMonitoring()
            if (existing == null || requestConnection) nettyClient.connect()
        } else {
            val monitor = networkMonitor ?: return
            networkMonitor = null
            nettyClient.disconnect()
            monitor.stopMonitoring()
        }
    }

    internal suspend fun <T> withPushReception(receive: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        pushReceptionOwners++
        try {
            updateMessageReception(requestConnection = true)
            receive()
        } finally {
            pushReceptionOwners--
            updateMessageReception()
        }
    }

    @MainThread
    internal fun setCallReceptionRequested(requested: Boolean) {
        if (callReceptionRequested == requested) return
        callReceptionRequested = requested
        updateMessageReception()
    }

    private suspend fun failed(@StringRes message: Int) {
        try { closeInitializedResources() }
        catch (error: Exception) {
            android.util.Log.w("MsgAT_Startup", "Cleanup failed (${error.javaClass.simpleName})")
        } finally {
            startupFailure = message
            _startupState.value = StartupState.FAILED
        }
    }

    private suspend fun closeInitializedResources() = withContext(Dispatchers.IO + NonCancellable) {
        try {
            revocationWorker?.cancelAndJoin()
            if (::androidPush.isInitialized) androidPush.closeForIdentityReset()
        } finally {
            try {
                if (::nettyClient.isInitialized) nettyClient.close()
            } finally {
                if (::db.isInitialized) AppDatabase.closeCurrent()
            }
        }
    }

    companion object {
        lateinit var instance: MsgATApp
            private set
    }
}
