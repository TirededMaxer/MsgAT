package org.msgat

import android.app.Application
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.core.network.NettyClient
import org.msgat.data.db.AppDatabase

class MsgATApp : Application() {
    lateinit var securePrefs: SecurePrefs
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var nettyClient: NettyClient
        private set

    @Volatile var isAppInForeground = false
    var startupFailure: String? = null
        private set
    val isReady: Boolean get() = startupFailure == null && ::nettyClient.isInitialized

    // Initializes global application state and sets up activity lifecycle callbacks.
    override fun onCreate() {
        super.onCreate()

        instance = this

        try {
            securePrefs = SecurePrefs(applicationContext)
            org.msgat.core.crypto.SecurityCore.requireAvailable()
            db = AppDatabase.getInstance(applicationContext)
            nettyClient = NettyClient(securePrefs, db) { sender ->
                if (!isAppInForeground) NotificationHelper.showPrivateMessageNotification(applicationContext,sender)
            }
        } catch (error: org.msgat.core.crypto.securestorage.HardwareProtectionUnavailableException) {
            startupFailure = error.message
            return
        } catch (error: LinkageError) {
            startupFailure = "The native security component could not be loaded. Existing identity data was not replaced."
            return
        } catch (error: Exception) {
            startupFailure = "The device identity vault could not be opened. Existing identity data was not replaced."
            return
        }

        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            // Handles application transition to the foreground.
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                isAppInForeground = true
                NotificationHelper.clearAllNotifications(applicationContext)
            }
            // Handles application transition to the background.
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                isAppInForeground = false
            }
        })
    }

    companion object {
        lateinit var instance: MsgATApp
            private set
    }
}
