package org.msgat.core.network

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.msgat.MsgATApp
import org.msgat.NotificationHelper
import org.msgat.core.call.CallManager
import org.msgat.core.call.CallState

class CallService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var monitor: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MsgATApp.instance.isReady) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val call = CallManager.getInstance()
        val token = intent?.getStringExtra("callToken")
        if (token == null || token != call.currentCallId) {
            if (monitor == null) stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            "ACTION_END_CALL" -> {
                if (call.callState.value == CallState.INCOMING_RINGING) call.rejectCall() else call.endCall()
                stopSelf(startId)
            }
            else -> {
                try {
                    startForeground(NotificationHelper.CALL_NOTIFICATION_ID, notification(call, token, call.callState.value),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (_: SecurityException) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                } catch (_: IllegalStateException) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                monitor?.cancel()
                monitor = scope.launch {
                    call.callState.collect { state ->
                        if (call.currentCallId != token || state == CallState.ENDED || state == CallState.IDLE) {
                            stopSelf(startId)
                            cancel()
                        } else {
                            getSystemService(NotificationManager::class.java)
                                ?.notify(NotificationHelper.CALL_NOTIFICATION_ID, notification(call, token, state))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(call: CallManager, token: String, state: CallState) =
        NotificationHelper.createCallNotification(
            applicationContext,
            isIncoming = state == CallState.INCOMING_RINGING,
            isOngoing = state == CallState.CONNECTING || state == CallState.CONNECTED,
            video = call.isVideoCall.value,
            callToken = token
        )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
