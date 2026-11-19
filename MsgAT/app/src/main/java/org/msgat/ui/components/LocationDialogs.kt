// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.core.location.currentLocation
import org.msgat.core.location.hasLocationPermission
import org.msgat.core.location.locationFromDegrees
import org.msgat.core.network.LocationPoint
import org.msgat.core.network.SendOptions
import org.msgat.data.db.LiveLocationSnapshot
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocationSendDialog(
    recipient: String,
    initial: SendOptions,
    send: suspend (LocationPoint, String?, SendOptions) -> Boolean,
    shareLive: suspend (LocationPoint, Long, String?, Boolean) -> Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var accuracy by remember { mutableStateOf<Int?>(null) }
    var locating by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(initial) }
    var choosingOptions by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(15 * 60 * 1_000L) }
    var requestLivePermission by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val selected = locationFromText(latitude, longitude)
    val title = label.trim().takeIf { it.isNotEmpty() }
    val validTitle = title == null || (title.toByteArray(Charsets.UTF_8).size <= 256 && title.none { it.isISOControl() })
    val colors = LocalMessagingColors.current

    fun locate() {
        if (locating || sending) return
        error = null
        locating = true
        scope.launch {
            try {
                val result = currentLocation(context)
                val point = result?.let { locationFromDegrees(it.latitude, it.longitude) }
                if (point == null) error = R.string.ui_location_unavailable_manual
                else {
                    latitude = locationDegree(point.latitudeE6)
                    longitude = locationDegree(point.longitudeE6)
                    accuracy = result?.takeIf { it.hasAccuracy() }?.accuracy?.takeIf { it.isFinite() }?.roundToInt()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.ui_location_unavailable_manual
            } finally { locating = false }
        }
    }

    fun startLive() {
        if (locating || sending || !validTitle) return
        error = null
        sending = true
        scope.launch {
            try {
                val point = currentLocation(context)?.let { locationFromDegrees(it.latitude, it.longitude) }
                when {
                    point == null -> error = R.string.ui_location_unavailable
                    shareLive(point, duration, title, options.silent) -> onDismiss()
                    else -> error = R.string.ui_live_location_start_error
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.ui_live_location_start_error
            } finally {
                sending = false
            }
        }
    }

    val permissions = remember { arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) {
            if (requestLivePermission && live) startLive() else if (!requestLivePermission && !live) locate()
        } else {
            error = if (live) R.string.ui_location_access else R.string.ui_location_access_manual
        }
    }

    Dialog(onDismissRequest = { if (!sending) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.safeDrawingPadding().imePadding().widthIn(max = 500.dp).fillMaxWidth(.92f),
            shape = MaterialTheme.shapes.large, color = colors.chrome) {
            Column {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(uiString(R.string.ui_location_share), style = MaterialTheme.typography.titleLarge)
                    Text(uiString(R.string.ui_recipient, recipient), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color = colors.divider)
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !live, enabled = !sending && !locating,
                            onClick = { live = false; error = null }, label = { Text(uiString(R.string.ui_location)) })
                        FilterChip(selected = live, enabled = !sending && !locating,
                            onClick = { live = true; error = null }, label = { Text(uiString(R.string.ui_live_location)) })
                    }
                    if (live) {
                        Text(uiString(R.string.ui_live_location_explain_recipient, recipient))
                        Column(Modifier.selectableGroup()) {
                            val durations = listOf(
                                R.string.ui_location_duration_15 to 15L,
                                R.string.ui_location_duration_60 to 60L,
                                R.string.ui_location_duration_480 to 480L
                            )
                            durations.forEach { (label, minutes) ->
                                val value = minutes * 60 * 1_000
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected = duration == value, enabled = !sending,
                                        role = Role.RadioButton, onClick = { duration = value }),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = duration == value, enabled = !sending, onClick = null)
                                    Text(uiString(label))
                                }
                            }
                        }
                    } else {
                        OutlinedButton(enabled = !sending && !locating, onClick = {
                            if (hasLocationPermission(context)) locate()
                            else { requestLivePermission = false; permission.launch(permissions) }
                        }) { Text(if (locating) uiString(R.string.ui_location_finding) else uiString(R.string.ui_location_find)) }
                        OutlinedTextField(latitude, { latitude = it.take(20); accuracy = null; error = null },
                            modifier = Modifier.fillMaxWidth(), label = { Text(uiString(R.string.ui_location_latitude)) }, singleLine = true,
                            enabled = !sending && !locating, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                        OutlinedTextField(longitude, { longitude = it.take(20); accuracy = null; error = null },
                            modifier = Modifier.fillMaxWidth(), label = { Text(uiString(R.string.ui_location_longitude)) }, singleLine = true,
                            enabled = !sending && !locating, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                    }
                    OutlinedTextField(label, { label = it.take(256) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiString(R.string.ui_location_label)) }, singleLine = true, enabled = !sending, isError = !validTitle)
                    if (!validTitle) {
                        Text(uiString(R.string.ui_location_label_invalid), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (live) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(options.silent, enabled = !sending, role = Role.Checkbox,
                                onValueChange = { options = options.copy(silent = it) }),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(options.silent, onCheckedChange = null, enabled = !sending)
                            Text(uiString(R.string.ui_location_silent))
                        }
                    } else {
                        selected?.let { point ->
                            SelectionContainer { Text(locationCoordinates(point), style = MaterialTheme.typography.bodyMedium) }
                        }
                        accuracy?.let { Text(uiString(R.string.ui_location_accuracy, it), style = MaterialTheme.typography.bodySmall) }
                        if (selected == null && latitude.isNotBlank() && longitude.isNotBlank()) {
                            Text(uiString(R.string.ui_location_coordinates_invalid),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(enabled = !sending, onClick = { choosingOptions = true }) {
                            Text(sendOptionsLabel(options).ifEmpty { uiString(R.string.ui_send_options) })
                        }
                    }
                    error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider(color = colors.divider)
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !sending, onClick = onDismiss) { Text(uiString(R.string.ui_cancel)) }
                    Button(enabled = (live || selected != null) && validTitle && !sending && !locating, onClick = {
                        if (live) {
                            if (hasLocationPermission(context)) startLive()
                            else { requestLivePermission = true; permission.launch(permissions) }
                            return@Button
                        }
                        val point = selected ?: return@Button
                        sending = true
                        error = null
                        scope.launch {
                            try {
                                if (send(point, title, options)) onDismiss()
                                else error = R.string.ui_location_queue_error
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = R.string.ui_location_queue_error
                            } finally { sending = false }
                        }
                    }) { Text(if (sending) uiString(R.string.ui_location_sending) else if (live) uiString(R.string.ui_live_location_share) else uiString(R.string.ui_location_send)) }
                }
            }
        }
    }
    if (choosingOptions) SendOptionsDialog(options, onDismiss = { choosingOptions = false }, onConfirm = {
        options = it
        choosingOptions = false
    })
}

@Composable
internal fun LocationDetailsDialog(label: String, load: suspend () -> LocationPoint?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var point by remember { mutableStateOf<LocationPoint?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        error = null
        loadFailed = false
        try {
            point = load()
            if (point == null) error = R.string.ui_location_missing
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = R.string.ui_location_open_error
            loadFailed = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (label == "Location") uiString(R.string.ui_location) else label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                point?.let { value -> SelectionContainer { Text(locationCoordinates(value)) } }
                if (point == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
                if (loadFailed) TextButton(onClick = { retry++ }) { Text(uiString(R.string.ui_retry)) }
            }
        },
        confirmButton = {
            point?.let { value ->
                TextButton(onClick = {
                    scope.launch {
                        error = null
                        try {
                            if (load() != value) {
                                point = null
                                error = R.string.ui_location_missing
                                return@launch
                            }
                            val coordinates = locationCoordinates(value)
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates")))
                        } catch (_: ActivityNotFoundException) {
                            error = R.string.ui_location_maps_missing
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = R.string.ui_location_open_error
                        }
                    }
                }) { Text(uiString(R.string.ui_location_open_maps)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiString(R.string.ui_close)) } }
    )
}

@Composable
internal fun observeLiveLocation(
    peer: String, messageId: String, changes: SharedFlow<String>, load: suspend () -> LiveLocationSnapshot?
): State<Result<LiveLocationSnapshot?>?> {
    val currentLoad by rememberUpdatedState(load)
    return produceState<Result<LiveLocationSnapshot?>?>(null, peer, messageId, changes) {
        changes.onSubscription { emit(peer) }.filter { it == peer }.conflate().collect {
            value = try {
                Result.success(currentLoad())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }
}

@Composable
internal fun liveLocationExpired(expiresAt: Long?): Boolean {
    val expired by produceState(expiresAt != null && System.currentTimeMillis() >= expiresAt, expiresAt) {
        val deadline = expiresAt ?: return@produceState
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            value = remaining <= 0
            if (remaining <= 0) break
            delay(minOf(remaining, 60_000))
        }
    }
    return expired
}

@Composable
internal fun LiveLocationDetailsDialog(
    label: String, snapshot: Result<LiveLocationSnapshot?>?, mine: Boolean, sharing: Boolean,
    load: suspend () -> LiveLocationSnapshot?, stop: suspend () -> Boolean, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = snapshot?.getOrNull()
    val active = !liveLocationExpired(state?.expiresAt) && state?.ended == false
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (label == "Location") uiString(R.string.ui_live_location) else label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    state == null -> Text(uiString(R.string.ui_location_missing))
                    else -> {
                        Text(when {
                            !active -> uiString(R.string.ui_live_location_ended)
                            mine && !sharing -> uiString(R.string.ui_live_location_paused)
                            else -> uiString(R.string.ui_live_location)
                        }, style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(locationCoordinates(state.point)) }
                        if (mine && active) {
                            OutlinedButton(enabled = !busy, onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        if (!stop()) error = R.string.ui_live_location_stop_error
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        error = R.string.ui_live_location_stop_error
                                    } finally {
                                        busy = false
                                    }
                                }
                            }) { Text(uiString(R.string.ui_live_location_stop)) }
                        }
                    }
                }
                error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state != null) TextButton(onClick = {
                error = null
                scope.launch {
                    try {
                        val latest = load()?.point
                        if (latest == null) {
                            error = R.string.ui_location_missing
                        } else {
                            val coordinates = locationCoordinates(latest)
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates")))
                        }
                    } catch (_: ActivityNotFoundException) {
                        error = R.string.ui_location_maps_missing
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = R.string.ui_location_open_error
                    }
                }
            }) { Text(uiString(R.string.ui_location_open_maps)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiString(R.string.ui_close)) } }
    )
}

private fun locationFromText(latitude: String, longitude: String): LocationPoint? =
    locationFromDegrees(latitude.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN,
        longitude.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN)

private fun locationDegree(value: Int): String = String.format(Locale.ROOT, "%.6f", value / 1_000_000.0)
private fun locationCoordinates(point: LocationPoint) = "${locationDegree(point.latitudeE6)},${locationDegree(point.longitudeE6)}"
