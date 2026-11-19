// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import org.msgat.R
import org.msgat.core.network.BackgroundReception
import org.msgat.core.network.AndroidPush
import org.msgat.ui.theme.uiString

@Composable
internal fun BackgroundReceptionDialog(onDismiss:()->Unit){
    val context=LocalContext.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    var permissions by remember {mutableStateOf(BackgroundReception.permissions(context))}
    var settingsFailed by remember { mutableStateOf(false) }
    var requestedNotifications by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissions = BackgroundReception.permissions(context)
    }
    val status by BackgroundReception.status.collectAsState()
    val app = org.msgat.MsgATApp.instance
    val connection by app.nettyClient.connectionState.collectAsState()
    val networkStatus by app.nettyClient.networkStatus.collectAsState()
    val push = app.androidPush
    var persistentEnabled by remember { mutableStateOf(app.securePrefs.persistentReceptionEnabled) }
    val pushState by push.state.collectAsState()
    var distributors by remember { mutableStateOf(push.distributors()) }
    var chooseDistributor by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle,context){
        val observer=object:androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner:androidx.lifecycle.LifecycleOwner){
                permissions=BackgroundReception.permissions(context)
                distributors=push.distributors()
                push.refreshDistributor()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose{lifecycle.lifecycle.removeObserver(observer)}
    }
    fun settings(open: () -> Boolean) { settingsFailed = !open() }
    AlertDialog(onDismissRequest=onDismiss,properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
        title={Text(uiString(R.string.ui_background_reception))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = uiString(R.string.ui_bg_persistent)
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(checked = persistentEnabled, onCheckedChange = { enabled ->
                    BackgroundReception.setEnabled(context, enabled)
                    persistentEnabled = app.securePrefs.persistentReceptionEnabled
                }, modifier = Modifier.semantics { contentDescription = label })
            }
            Text(if (!persistentEnabled) uiString(R.string.ui_bg_persistent_off) else when(status.state){
                BackgroundReception.State.RUNNING -> {
                    val network = BackgroundReception.connectionLabel(connection, networkStatus)
                    uiString(R.string.ui_bg_running, uiString(network))
                }
                BackgroundReception.State.STARTING -> uiString(R.string.ui_bg_starting)
                BackgroundReception.State.FAILED -> uiString(status.explanation ?: R.string.ui_bg_start_failed)
                BackgroundReception.State.STOPPED -> uiString(R.string.ui_bg_stopped)
            })
            if (persistentEnabled) Text(uiString(R.string.ui_bg_description))
            if (!permissions.notifications) {
                Text(uiString(R.string.ui_bg_notifications_blocked))
                val canRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !requestedNotifications
                if (canRequest) {
                    OutlinedButton(onClick = {
                        requestedNotifications = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text(uiString(R.string.ui_bg_allow_notifications)) }
                }
            } else {
                val anyBlocked = permissions.channels.any { it.importance == NotificationManager.IMPORTANCE_NONE }
                Text(uiString(if (anyBlocked) R.string.ui_bg_channels_blocked else R.string.ui_bg_notifications_allowed))
                permissions.channels.forEach { channel ->
                    val channelStatus = uiString(if (channel.importance == NotificationManager.IMPORTANCE_NONE)
                        R.string.ui_bg_channel_off else R.string.ui_bg_channel_on)
                    OutlinedButton(
                        onClick = { settings { BackgroundReception.notificationSettings(context, channel.id) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(channel.name.toString(), modifier = Modifier.weight(1f))
                            Text(channelStatus)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { settings { BackgroundReception.notificationSettings(context) } }) {
                Text(uiString(R.string.ui_bg_manage_notifications))
            }
            Text(uiString(if (permissions.batteryExempt) R.string.ui_bg_battery_unrestricted else R.string.ui_bg_battery_optimized))
            if (!permissions.batteryExempt) {
                OutlinedButton(onClick = { settings { BackgroundReception.requestBatteryExemption(context) } }) {
                    Text(uiString(R.string.ui_bg_allow_battery))
                }
            }
            Text(uiString(if (permissions.backgroundRestricted) R.string.ui_bg_restricted else R.string.ui_bg_unrestricted))
            Text(uiString(R.string.ui_bg_manufacturer, permissions.manufacturer))
            OutlinedButton(onClick = { settings { BackgroundReception.appSettings(context) } }) {
                Text(uiString(R.string.ui_bg_app_settings))
            }
            if (persistentEnabled && (status.state == BackgroundReception.State.STOPPED || status.state == BackgroundReception.State.FAILED)) {
                OutlinedButton(onClick = { BackgroundReception.start(context) }) {
                    Text(uiString(R.string.ui_bg_start))
                }
            }
            HorizontalDivider()
            Text(uiString(R.string.ui_bg_push_title), style = MaterialTheme.typography.titleSmall)
            Text(uiString(when (pushState.status) {
                AndroidPush.Status.OFF -> if (distributors.isEmpty()) R.string.ui_bg_push_needs_distributor else R.string.ui_bg_push_description
                AndroidPush.Status.WAITING_ENDPOINT -> R.string.ui_bg_push_waiting_endpoint
                AndroidPush.Status.WAITING_RELAY -> R.string.ui_bg_push_waiting_relay
                AndroidPush.Status.VERIFYING -> R.string.ui_bg_push_verifying
                AndroidPush.Status.ACTIVE -> R.string.ui_bg_push_active
                AndroidPush.Status.EXPIRED -> R.string.ui_bg_push_expired
                AndroidPush.Status.TLS_REQUIRED -> R.string.ui_bg_push_tls_required
                AndroidPush.Status.UNSUPPORTED -> R.string.ui_bg_push_unsupported
                AndroidPush.Status.REJECTED -> R.string.ui_bg_push_rejected
                AndroidPush.Status.DISTRIBUTOR_ERROR -> R.string.ui_bg_push_distributor_error
                AndroidPush.Status.RELAY_CHANGED -> R.string.ui_bg_push_relay_changed
            }))
            if (pushState.enabled) {
                if (pushState.status in setOf(AndroidPush.Status.WAITING_ENDPOINT, AndroidPush.Status.DISTRIBUTOR_ERROR,
                        AndroidPush.Status.WAITING_RELAY, AndroidPush.Status.EXPIRED, AndroidPush.Status.REJECTED)) {
                    OutlinedButton(onClick = { push.retryRegistration() }) { Text(uiString(R.string.ui_retry)) }
                }
                OutlinedButton(onClick = { push.disable() }) { Text(uiString(R.string.ui_bg_push_disable)) }
            } else if (distributors.isNotEmpty()) {
                Box {
                    OutlinedButton(onClick = { chooseDistributor = true }) { Text(uiString(R.string.ui_bg_push_choose)) }
                    DropdownMenu(expanded = chooseDistributor, onDismissRequest = { chooseDistributor = false }) {
                        distributors.forEach { distributor ->
                            val label = remember(distributor) {
                                runCatching {
                                    @Suppress("DEPRECATION")
                                    val info = context.packageManager.getApplicationInfo(distributor, 0)
                                    info.loadLabel(context.packageManager).toString()
                                }.getOrDefault(distributor)
                            }
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                chooseDistributor = false
                                push.enable(distributor)
                            })
                        }
                    }
                }
            }
            if (settingsFailed) Text(uiString(R.string.ui_bg_settings_failed), color = MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(onClick=onDismiss){Text(uiString(R.string.ui_close))}})
}
