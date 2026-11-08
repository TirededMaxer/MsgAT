package org.msgat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.msgat.MsgATApp
import org.msgat.core.network.MsgATService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if (intent.action!=Intent.ACTION_BOOT_COMPLETED) return
        if (MsgATApp.instance.startupState.value==MsgATApp.StartupState.FAILED) return
        // Target-35+ apps cannot launch a dataSync foreground service from boot
        // on Android 15+. Background push support must use its proper OS/vendor path.
        if (android.os.Build.VERSION.SDK_INT>=35) return
        try { context.startForegroundService(Intent(context,MsgATService::class.java)) }
        catch (error: RuntimeException) {
            android.util.Log.w("MsgAT_Service","Boot start refused: ${error.javaClass.simpleName}")
        }
    }
}
