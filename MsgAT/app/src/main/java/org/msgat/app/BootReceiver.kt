package org.msgat.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.msgat.MsgATApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if (intent.action!=Intent.ACTION_BOOT_COMPLETED && intent.action!=Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (MsgATApp.instance.startupState.value==MsgATApp.StartupState.FAILED) return
        // This is continuous IM reception, not a finite dataSync transfer. The
        // OS can still refuse background launch; the foreground settings expose it.
        org.msgat.core.network.BackgroundReception.start(context)
    }
}
