package org.msgat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import org.msgat.data.db.MessageNotificationStore
import org.msgat.ui.theme.AppLanguage

object NotificationHelper {
    private const val CHANNEL_ID = "msgat_messages"
    private const val QUIET_CHANNEL_ID="msgat_messages_quiet"
    private const val FOREGROUND_CHANNEL_ID = "msgat_foreground"
    private const val CALL_CHANNEL_ID = "msgat_calls"
    const val CALL_NOTIFICATION_ID = 1001
    internal const val ACTION_OPEN_BACKGROUND_RECEPTION = "org.msgat.action.OPEN_BACKGROUND_RECEPTION"
    internal const val ACTION_ANSWER_CALL = "org.msgat.action.ANSWER_CALL"

    internal fun receptionChannels(context: Context): List<NotificationChannel> {
        createChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return listOf(CHANNEL_ID, QUIET_CHANNEL_ID, CALL_CHANNEL_ID, FOREGROUND_CHANNEL_ID)
            .mapNotNull { id -> manager.getNotificationChannel(id) }
    }

    // Creates the necessary Android notification channels for background services and messages.
    fun createChannels(context: Context) = createChannels(context, AppLanguage.restore(context).resources(context))

    private fun createChannels(context: Context, strings: Resources) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                CHANNEL_ID,
                strings.getString(R.string.ui_channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = strings.getString(R.string.ui_channel_messages_desc)
                enableVibration(true)
            }

            val fgChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                strings.getString(R.string.ui_channel_background),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = strings.getString(R.string.ui_channel_background_desc)
                setShowBadge(false)
            }

            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                strings.getString(R.string.ui_channel_calls),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = strings.getString(R.string.ui_channel_calls_desc)
                enableVibration(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            }

            val quiet=NotificationChannel(QUIET_CHANNEL_ID,strings.getString(R.string.ui_channel_silent),NotificationManager.IMPORTANCE_DEFAULT).apply {
                description=strings.getString(R.string.ui_channel_silent_desc);setSound(null,null);enableVibration(false)
            }
            manager.createNotificationChannel(quiet)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(fgChannel)
            manager.createNotificationChannel(callChannel)
        }
    }

    // Builds and returns a persistent notification to keep the background service alive.
    internal fun getForegroundNotification(context: Context, @StringRes connectionLabel: Int? = null): Notification {
        val strings = AppLanguage.restore(context).resources(context)
        createChannels(context, strings)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val settingsIntent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_BACKGROUND_RECEPTION)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val settingsAction = PendingIntent.getActivity(
            context, 0, settingsIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val status = if (connectionLabel == null) strings.getString(R.string.ui_bg_starting)
            else strings.getString(R.string.ui_bg_running, strings.getString(connectionLabel))
        return NotificationCompat.Builder(context, FOREGROUND_CHANNEL_ID)
            .setContentTitle("MsgAT")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(0, strings.getString(R.string.ui_bg_notification_settings), settingsAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .build()
    }

    // Displays a high-priority heads-up notification for incoming chat messages.
    internal fun showMessageNotification(context: Context, notice: MessageNotificationStore.Notice) {
        val timeout = notice.expiresAt?.minus(System.currentTimeMillis())
        if (timeout != null && timeout <= 0L) return
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val strings = AppLanguage.restore(context).resources(context)
        createChannels(context, strings)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = android.net.Uri.Builder().scheme("msgat").authority("notification").appendPath(notice.token).build()
        }
        val pendingIntent = PendingIntent.getActivity(context,0,intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val channel = if (notice.silent) QUIET_CHANNEL_ID else CHANNEL_ID
        val unlocked = context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == false
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (unlocked) notice.title else "MsgAT")
            .setContentText(notice.text?.takeIf { unlocked } ?: strings.getString(
                if (notice.pinned) R.string.ui_message_pinned else R.string.ui_new_message))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if(notice.silent)NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setSilent(notice.silent)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        if (timeout != null) builder.setTimeoutAfter(timeout)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notice.replacedToken?.let { manager.cancel("message:$it", 0) }
            manager.notify("message:${notice.token}",0,builder.build())
        }
        catch (_: SecurityException) { /* Permission can be revoked between check and delivery. */ }
    }

    fun clearMessageNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications.filter { it.tag?.startsWith("message:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    internal fun dismissMessageNotifications(context: Context, tokens: List<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (token in tokens) manager?.cancel("message:$token", 0)
    }

    // Dismisses all active notifications for the application.
    fun clearAllNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNotifications = manager.activeNotifications
            for (notification in activeNotifications) {
                if (notification.id != 1 && notification.id != CALL_NOTIFICATION_ID &&
                    notification.id != org.msgat.core.location.LiveLocationService.NOTIFICATION_ID) {
                    manager.cancel(notification.tag,notification.id)
                }
            }
        } else {
            manager.cancelAll()
        }
    }
    // Creates a high-priority, full-screen intent notification for voice calls.
    fun createCallNotification(
        context: Context,
        isIncoming: Boolean,
        isOngoing: Boolean,
        video: Boolean = false,
        callToken: String? = null,
        showFullScreen: Boolean = isIncoming
    ): Notification {
        val strings = AppLanguage.restore(context).resources(context)
        createChannels(context, strings)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("action", "show_call")
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("MsgAT")
            .setContentText(strings.getString(when {
                video && isOngoing -> R.string.ui_ongoing_video_call
                video && isIncoming -> R.string.ui_incoming_video_call
                video -> R.string.ui_video_calling
                isOngoing -> R.string.ui_ongoing_call
                isIncoming -> R.string.ui_incoming_voice_call
                else -> R.string.ui_calling
            }))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOnlyAlertOnce(!isIncoming)
            .setSilent(!isIncoming)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setOngoing(true)
        if (isIncoming && showFullScreen) builder.setFullScreenIntent(pendingIntent, true)

        // Accept Action (Only for incoming)
        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_ANSWER_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("callToken", callToken)
            data = android.net.Uri.Builder().scheme("msgat").authority("call-action").appendPath(callToken.orEmpty()).build()
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            context, 1, acceptIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Reject/End Action
        val endIntent = Intent(context, org.msgat.core.network.CallService::class.java).apply {
            action = "ACTION_END_CALL"
            putExtra("callToken", callToken)
            data = android.net.Uri.Builder().scheme("msgat").authority("call-action").appendPath(callToken.orEmpty()).build()
        }
        val endPendingIntent = PendingIntent.getService(
            context, 2, endIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callerPerson = androidx.core.app.Person.Builder().setName("MsgAT").build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callStyle = if (isOngoing) {
                NotificationCompat.CallStyle.forOngoingCall(callerPerson, endPendingIntent)
            } else if (isIncoming) {
                NotificationCompat.CallStyle.forIncomingCall(callerPerson, endPendingIntent, acceptPendingIntent)
            } else {
                NotificationCompat.CallStyle.forOngoingCall(callerPerson, endPendingIntent)
            }
            builder.setStyle(callStyle)
        } else {
            if (isIncoming && !isOngoing) {
                builder.addAction(android.R.drawable.ic_menu_call, strings.getString(R.string.ui_accept_call), acceptPendingIntent)
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, strings.getString(R.string.ui_reject_call), endPendingIntent)
            } else {
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, strings.getString(R.string.ui_end_call), endPendingIntent)
            }
        }

        return builder.build().apply {
            if (isIncoming && !isOngoing) flags = flags or Notification.FLAG_INSISTENT
        }
    }
}
