// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import android.app.ActivityManager
import android.app.NotificationChannel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.msgat.NotificationHelper
import org.msgat.MsgATApp
import org.msgat.R
import org.msgat.ui.chat.SyncState

/** OS-visible, user-controlled IM receiver. This is not a vendor push registration. */
object BackgroundReception {
    enum class State { STOPPED, STARTING, RUNNING, FAILED }
    data class Status(val state: State = State.STOPPED, @StringRes val explanation: Int? = null)
    data class Permissions(
        val notifications: Boolean,
        val channels: List<NotificationChannel>,
        val batteryExempt: Boolean,
        val backgroundRestricted: Boolean,
        val manufacturer: String
    )
    private val current=MutableStateFlow(Status())
    val status=current.asStateFlow()
    fun reportStarting(){if(current.value.state!=State.RUNNING)current.value=Status(State.STARTING)}
    fun reportRunning(){current.value=Status(State.RUNNING)}
    fun reportFailure(@StringRes explanation: Int) { current.value = Status(State.FAILED, explanation) }
    fun reportStopped(){if(current.value.state!=State.FAILED)current.value=Status()}

    @StringRes
    internal fun connectionLabel(connection: SyncState, network: RelayNetworkStatus): Int = when (connection) {
        SyncState.SYNCED -> R.string.ui_connected
        SyncState.SYNCING -> R.string.ui_syncing
        SyncState.DISCONNECTED -> when (network) {
            RelayNetworkStatus.CHECKING -> R.string.ui_bg_network_checking
            RelayNetworkStatus.BLOCKED -> R.string.ui_bg_network_blocked
            RelayNetworkStatus.UNAVAILABLE -> R.string.ui_bg_no_network
            else -> R.string.ui_disconnected
        }
    }

    fun start(context: Context): Boolean {
        val app = context.applicationContext as MsgATApp
        if (app.isReady && !app.securePrefs.persistentReceptionEnabled) return false
        if (current.value.state == State.RUNNING || current.value.state == State.STARTING) return true
        reportStarting()
        return try {
            context.applicationContext.startForegroundService(Intent(context, MsgATService::class.java))
            true
        } catch (error: RuntimeException) {
            android.util.Log.w("MsgAT_Service", "Background start refused: ${error.javaClass.simpleName}")
            reportFailure(R.string.ui_bg_start_refused)
            false
        }
    }
    internal fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext as MsgATApp
        if (!app.isReady) return
        app.securePrefs.persistentReceptionEnabled = enabled
        if (enabled) start(context)
        else {
            stop(context)
            current.value = Status()
        }
    }
    internal fun stop(context: Context) {
        context.applicationContext.stopService(Intent(context, MsgATService::class.java))
        reportStopped()
    }
    fun permissions(context:Context)=Permissions(
        NotificationManagerCompat.from(context).areNotificationsEnabled(),
        NotificationHelper.receptionChannels(context),
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName),
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted,
        android.os.Build.MANUFACTURER
    )
    fun appSettings(context:Context)=open(context,Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))
    fun notificationSettings(context: Context, channelId: String? = null): Boolean {
        val intent = if (channelId == null) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        return open(context, intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    /** Call only after the user chooses the corresponding button. The OS owns consent. */
    fun requestBatteryExemption(context:Context)=open(context,Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:${context.packageName}")))
    private fun open(context:Context,intent:Intent):Boolean {
        try {context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true}
        catch(_:android.content.ActivityNotFoundException){ }
        catch(_:SecurityException){ }
        return try {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true}
        catch(_:RuntimeException){false}
    }
}
