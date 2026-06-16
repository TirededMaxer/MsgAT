package org.msgat.core.network

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.msgat.MsgATApp
import org.msgat.NotificationHelper
import android.content.Context
import android.os.PowerManager

class MsgATService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    // Returns null as this foreground service does not support IPC binding.
    override fun onBind(intent: Intent?): IBinder? {
        return null 
    }

    // Starts the service in the foreground and connects the Netty client.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationHelper.getForegroundNotification(this)
        startForeground(1, notification)

        val app = MsgATApp.instance
        if (app.securePrefs.isConfigured()) {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MsgAT::ConnectionWakeLock")
                wakeLock?.acquire()
            }
            app.nettyClient.connect()
        }

        return START_STICKY
    }

    // Cleans up resources and disconnects the network client when the service is destroyed.
    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        MsgATApp.instance.nettyClient.disconnect()
    }
}
