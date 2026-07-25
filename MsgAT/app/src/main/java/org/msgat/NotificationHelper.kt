package org.msgat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.msgat.data.db.ChatMessage

object NotificationHelper {
    private const val CHANNEL_ID = "msgat_messages"
    private const val FOREGROUND_CHANNEL_ID = "msgat_foreground"
    private const val CALL_CHANNEL_ID = "msgat_calls"
    const val CALL_NOTIFICATION_ID = 1001

    // Creates the necessary Android notification channels for background services and messages.
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Chat message notifications"
                enableVibration(true)
            }

            val fgChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Background Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps connection alive in background"
                setShowBadge(false)
            }

            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                "Voice Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming and ongoing voice calls"
                enableVibration(true)
            }

            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(fgChannel)
            manager.createNotificationChannel(callChannel)
        }
    }

    // Builds and returns a persistent notification to keep the background service alive.
    fun getForegroundNotification(context: Context): Notification {
        createChannels(context)
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, FOREGROUND_CHANNEL_ID)
            .setContentTitle("MsgAT toggle running")
            .setContentText("Listening for messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // Displays a high-priority heads-up notification for incoming chat messages.
    fun showMessageNotification(context: Context, senderUuid: String, senderAlias: String, ext: String, content: String) {
        createChannels(context)

        val displayContent = if (ext == "txt") content else "[Media]"
        val title = if (senderAlias.isNotBlank() && senderAlias != "Unknown") senderAlias else senderUuid.take(8)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP

        }

        val pendingIntent = PendingIntent.getActivity(
            context, senderUuid.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(displayContent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(senderUuid.hashCode(), builder.build())
    }

    // Dismisses all active notifications for the application.
    fun clearAllNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNotifications = manager.activeNotifications
            for (notification in activeNotifications) {
                if (notification.id != 1 && notification.id != CALL_NOTIFICATION_ID) {
                    manager.cancel(notification.id)
                }
            }
        } else {
            manager.cancelAll()
        }
    }
    // Creates a high-priority, full-screen intent notification for voice calls.
    fun createCallNotification(
        context: Context,
        peerUuid: String,
        alias: String,
        isIncoming: Boolean,
        isOngoing: Boolean
    ): Notification {
        createChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("action", "show_call")
            putExtra("peerUuid", peerUuid)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(alias)
            .setContentText(if (isOngoing) "Ongoing call" else if (isIncoming) "Incoming call" else "Calling...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)

        // Accept Action (Only for incoming)
        val acceptIntent = Intent(context, org.msgat.core.network.CallService::class.java).apply {
            action = "ACTION_ACCEPT_CALL"
        }
        val acceptPendingIntent = PendingIntent.getService(
            context, 1, acceptIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Reject/End Action
        val endIntent = Intent(context, org.msgat.core.network.CallService::class.java).apply {
            action = "ACTION_END_CALL"
        }
        val endPendingIntent = PendingIntent.getService(
            context, 2, endIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callerPerson = androidx.core.app.Person.Builder().setName(alias).build()

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
                builder.addAction(android.R.drawable.ic_menu_call, "Accept", acceptPendingIntent)
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", endPendingIntent)
            } else {
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", endPendingIntent)
            }
        }

        return builder.build()
    }
}
