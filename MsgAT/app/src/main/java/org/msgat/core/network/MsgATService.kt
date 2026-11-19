package org.msgat.core.network

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.msgat.MsgATApp
import org.msgat.NotificationHelper
import org.msgat.R

class MsgATService : Service() {
    private companion object { const val NOTIFICATION_ID = 1 }
    private val serviceScope=CoroutineScope(Dispatchers.IO+SupervisorJob())
    private var startupJob: Job?=null
    private var notificationJob: Job?=null
    override fun onBind(intent: Intent?): IBinder?=null

    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if (startupJob != null) return START_STICKY
        val app = MsgATApp.instance
        BackgroundReception.reportStarting()
        try {
            val notification=NotificationHelper.getForegroundNotification(this)
            if(android.os.Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID,notification)
        }
        catch (error: RuntimeException) {
            android.util.Log.w("MsgAT_Service","Foreground start refused: ${error.javaClass.simpleName}")
            BackgroundReception.reportFailure(R.string.ui_bg_start_refused)
            stopSelf()
            return START_NOT_STICKY
        }
        startupJob=serviceScope.launch {try {
            val state=app.awaitStartup()
            withContext(Dispatchers.Main) {
                if (state == MsgATApp.StartupState.READY && !app.securePrefs.persistentReceptionEnabled) {
                    stopSelf()
                    return@withContext
                }
                if (state!=MsgATApp.StartupState.READY || !app.securePrefs.isConfigured()) {
                    BackgroundReception.reportFailure(R.string.ui_bg_configure_receiver)
                    stopSelf()
                    return@withContext
                }
                app.attachBackgroundReceiver(this@MsgATService)
                BackgroundReception.reportRunning()
                notificationJob = serviceScope.launch(Dispatchers.Main) updates@{
                    val manager = getSystemService(NotificationManager::class.java) ?: return@updates
                    combine(app.nettyClient.connectionState, app.nettyClient.networkStatus) { connection, network ->
                        BackgroundReception.connectionLabel(connection, network)
                    }.distinctUntilChanged().collect { connectionLabel ->
                        try {
                            manager.notify(NOTIFICATION_ID,
                                NotificationHelper.getForegroundNotification(this@MsgATService, connectionLabel))
                        } catch (_: SecurityException) {
                            // Android owns notification permission. Reception can continue.
                        }
                    }
                }
            }
            // V2 alone owns durable ciphertext retries; never re-encrypt an old
            // pending text through the retired V1 MessageResender path.
        } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
          catch(error:Exception){
            android.util.Log.w("MsgAT_Service","Message receiver stopped: ${error.javaClass.simpleName}")
            BackgroundReception.reportFailure(R.string.ui_bg_start_failed)
            withContext(Dispatchers.Main){stopSelf()}
          }
        }
        return START_STICKY
    }

    @androidx.annotation.RequiresApi(35)
    override fun onTimeout(startId: Int,fgsType: Int) {
        BackgroundReception.reportFailure(R.string.ui_bg_stopped_by_system)
        startupJob?.cancel()
        notificationJob?.cancel()
        MsgATApp.instance.detachBackgroundReceiver(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        BackgroundReception.reportStopped()
        serviceScope.cancel()
        MsgATApp.instance.detachBackgroundReceiver(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
