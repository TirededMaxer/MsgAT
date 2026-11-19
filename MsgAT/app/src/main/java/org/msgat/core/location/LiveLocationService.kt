// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.msgat.MainActivity
import org.msgat.MsgATApp

internal class LiveLocationService : Service() {
    private var coordinator: LiveLocationCoordinator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MsgATApp
        if (intent == null || !app.isReady) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val owner = app.nettyClient.liveLocations
        if (intent.action == ACTION_STOP) {
            if (coordinator === owner) owner.stopAll(this) else stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            coordinator = owner
            owner.attached(this)
        } catch (_: SecurityException) {
            owner.startFailed()
            stopSelf(startId)
        } catch (_: IllegalStateException) {
            owner.startFailed()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        coordinator?.stopAll(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        coordinator?.detached(this)
        coordinator = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Live location", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(this, NOTIFICATION_ID, Intent(this, LiveLocationService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing live location")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stop)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1002
        private const val CHANNEL = "live_location"
        private const val ACTION_STOP = "org.msgat.action.STOP_LIVE_LOCATION"
    }
}
