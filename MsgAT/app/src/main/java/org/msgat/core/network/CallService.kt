package org.msgat.core.network

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import org.msgat.NotificationHelper
import org.msgat.core.call.CallManager
import org.msgat.core.call.CallState
import org.msgat.data.db.AppDatabase

class CallService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isForeground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "ACTION_ACCEPT_CALL") {
            CallManager.getInstance().acceptCall()
            return START_NOT_STICKY
        } else if (action == "ACTION_END_CALL") {
            val state = CallManager.getInstance().callState.value
            if (state == CallState.INCOMING_RINGING) {
                CallManager.getInstance().rejectCall()
            } else {
                CallManager.getInstance().endCall()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val peerUuid = intent?.getStringExtra("peerUuid") ?: return START_NOT_STICKY
        val isIncoming = intent.getBooleanExtra("isIncoming", false)

        scope.launch {
            val db = AppDatabase.getInstance(applicationContext).msgDao()
            val conv = withContext(Dispatchers.IO) { db.getConversationByUuid(peerUuid) }
            val alias = conv?.alias ?: "Unknown"

            val notification = NotificationHelper.createCallNotification(
                applicationContext,
                peerUuid,
                alias,
                isIncoming,
                CallManager.getInstance().callState.value == CallState.CONNECTED
            )

            if (!isForeground) {
                try {
                    startForeground(NotificationHelper.CALL_NOTIFICATION_ID, notification)
                    isForeground = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                notificationManager.notify(NotificationHelper.CALL_NOTIFICATION_ID, notification)
            }
        }

        // Monitor call state to stop service when call ends
        scope.launch {
            CallManager.getInstance().callState.collect { state ->
                if (state == CallState.ENDED || state == CallState.IDLE) {
                    stopSelf()
                } else if (state == CallState.CONNECTED && isForeground) {
                    // Update notification to show ongoing call
                    val db = AppDatabase.getInstance(applicationContext).msgDao()
                    val conv = withContext(Dispatchers.IO) { db.getConversationByUuid(peerUuid) }
                    val notification = NotificationHelper.createCallNotification(
                        applicationContext,
                        peerUuid,
                        conv?.alias ?: "Unknown",
                        isIncoming = false,
                        isOngoing = true
                    )
                    val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                    notificationManager.notify(NotificationHelper.CALL_NOTIFICATION_ID, notification)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
        scope.cancel()
    }
}
