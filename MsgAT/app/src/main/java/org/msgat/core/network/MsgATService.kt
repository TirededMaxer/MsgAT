package org.msgat.core.network

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.launch
import org.msgat.MsgATApp
import org.msgat.NotificationHelper

class MsgATService : Service() {

    private var networkMonitor: NetworkMonitor? = null

    // Returns null as this foreground service does not support IPC binding.
    override fun onBind(intent: Intent?): IBinder? {
        return null 
    }

    // Starts the service in the foreground and connects the Netty client.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationHelper.getForegroundNotification(this)
        startForeground(1, notification)

        val app = MsgATApp.instance
        if (networkMonitor == null) {
            networkMonitor = NetworkMonitor(applicationContext, app.nettyClient)
            networkMonitor?.startMonitoring()
        }
        if (app.securePrefs.isConfigured()) {
            app.nettyClient.connect()
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            app.nettyClient.connectionState.collect { state ->
                if (state == org.msgat.ui.chat.SyncState.SYNCED) {
                    MessageResender.resendPendingMessages()
                }
            }
        }

        return START_STICKY
    }

    // Cleans up resources and disconnects the network client when the service is destroyed.
    override fun onDestroy() {
        super.onDestroy()
        networkMonitor?.stopMonitoring()
        networkMonitor = null
        MsgATApp.instance.nettyClient.disconnect()
    }
}
