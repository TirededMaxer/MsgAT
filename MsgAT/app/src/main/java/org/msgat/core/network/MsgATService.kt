package org.msgat.core.network

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.msgat.MsgATApp
import org.msgat.NotificationHelper

class MsgATService : Service() {
    private var networkMonitor: NetworkMonitor?=null
    private val serviceScope=CoroutineScope(Dispatchers.IO+SupervisorJob())
    private var startupJob: Job?=null
    override fun onBind(intent: Intent?): IBinder?=null

    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        try { startForeground(1,NotificationHelper.getForegroundNotification(this)) }
        catch (error: RuntimeException) {
            android.util.Log.w("MsgAT_Service","Foreground start refused: ${error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (startupJob==null) startupJob=serviceScope.launch {
            val app=MsgATApp.instance
            val state=app.awaitStartup()
            withContext(Dispatchers.Main) {
                if (state!=MsgATApp.StartupState.READY || !app.securePrefs.isConfigured()) {
                    stopSelf()
                    return@withContext
                }
                networkMonitor=NetworkMonitor(applicationContext,app.nettyClient).also { it.startMonitoring() }
                app.nettyClient.connect()
            }
            // V2 alone owns durable ciphertext retries; never re-encrypt an old
            // pending text through the retired V1 MessageResender path.
        }
        return START_STICKY
    }

    @androidx.annotation.RequiresApi(35)
    override fun onTimeout(startId: Int,fgsType: Int) {
        // Android 15's dataSync budget is finite. Stop promptly instead of
        // crashing or pretending to offer unrestricted push delivery.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        networkMonitor?.stopMonitoring()
        networkMonitor=null
        serviceScope.cancel()
        if (MsgATApp.instance.isReady) MsgATApp.instance.nettyClient.disconnect()
        super.onDestroy()
    }
}
