package org.msgat

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.core.network.NettyClient
import org.msgat.data.db.AppDatabase

class MsgATApp : Application() {
    enum class StartupState { LOADING, READY, FAILED }
    private val _startupState=MutableStateFlow(StartupState.LOADING)
    val startupState=_startupState.asStateFlow()
    private val startupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    lateinit var securePrefs: SecurePrefs
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var nettyClient: NettyClient
        private set
    @Volatile var isAppInForeground=false
    @Volatile var startupFailure: String?=null
        private set
    val isReady: Boolean get()=startupState.value==StartupState.READY
    suspend fun awaitStartup(): StartupState=startupState.first { it!=StartupState.LOADING }

    override fun onCreate() {
        super.onCreate()
        instance=this
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                isAppInForeground=true
                NotificationHelper.clearAllNotifications(applicationContext)
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { isAppInForeground=false }
        })
        startupScope.launch {
            try {
                org.msgat.core.crypto.SecurityCore.requireAvailable()
                securePrefs=SecurePrefs(applicationContext)
                // The helper actually opens/authenticates SQLCipher here, on IO,
                // before a chat screen or network client can become available.
                db=AppDatabase.getInstance(applicationContext)
                nettyClient=NettyClient(securePrefs,db) { sender ->
                    if (!isAppInForeground) NotificationHelper.showPrivateMessageNotification(applicationContext,sender)
                }
                _startupState.value=StartupState.READY
            } catch (error: org.msgat.core.crypto.securestorage.HardwareProtectionUnavailableException) {
                failed(error.message ?: "Hardware identity protection is unavailable.")
            } catch (error: LinkageError) {
                failed("The native security component could not be loaded. Existing identity data was not replaced.")
            } catch (error: Exception) {
                failed("The device identity vault could not be opened. Existing identity data was not replaced.")
            }
        }
    }

    private fun failed(message: String) {
        startupFailure=message
        _startupState.value=StartupState.FAILED
        if (::db.isInitialized) runCatching { db.close() }
    }

    companion object {
        lateinit var instance: MsgATApp
            private set
    }
}
