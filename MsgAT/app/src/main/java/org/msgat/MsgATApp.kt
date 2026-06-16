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

    var isAppInForeground = false

    // Initializes global application state and sets up activity lifecycle callbacks.
    override fun onCreate() {
        super.onCreate()

        instance = this

        securePrefs = SecurePrefs(applicationContext)
        db = AppDatabase.getInstance(applicationContext)
        nettyClient = NettyClient(securePrefs, db.msgDao())

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
