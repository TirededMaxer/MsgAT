// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.msgat.R
import org.msgat.core.media.RecordingMediaSource
import org.msgat.core.media.RecordingPhase
import org.msgat.ui.theme.uiString
import java.util.Locale

@Composable
internal fun VoiceCaptureButton(
    enabled: Boolean, session: VoiceCaptureSession, tap: () -> Unit, hold: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val onTap by rememberUpdatedState(tap)
    val onHold by rememberUpdatedState(hold)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val label = uiString(R.string.ui_attachment_record_voice)
    Box(
        modifier = if (!enabled) modifier else modifier
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick { onTap(); true }
            }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.Spacebar)) {
                    onTap()
                    true
                } else false
            }
            .focusable()
            .pointerInput(session, rtl) {
                val cancelDistance = 72.dp.toPx()
                val lockDistance = 64.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val finishedTap = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            onTap()
                        }
                        true
                    }
                    if (finishedTap != null || !onHold()) return@awaitEachGesture
                    try {
                        var motion = Offset.Zero
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            motion += change.position - change.previousPosition
                            change.consume()
                            val towardStart = if (rtl) motion.x else -motion.x
                            when {
                                towardStart >= cancelDistance -> {
                                    session.discard()
                                    break
                                }
                                -motion.y >= lockDistance -> {
                                    session.lock()
                                    break
                                }
                                !change.pressed -> {
                                    session.release()
                                    break
                                }
                            }
                        }
                    } finally {
                        if (session.held) session.discard()
                    }
                }
            }
    )
}

@Composable
internal fun VoiceCaptureControls(
    session: VoiceCaptureSession, canPreview: Boolean, onRecorded: (RecordingMediaSource) -> Unit,
    onDiscard: () -> Unit, onRecordAgain: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var scrub by remember(session.previewDurationMs) { mutableStateOf<Float?>(null) }
        val reviewing = session.phase == RecordingPhase.READY
        val elapsed = if (reviewing && session.previewDurationMs > 0) {
            (scrub?.toLong() ?: session.previewPositionMs) / 1_000
        } else session.elapsedMs / 1_000
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.phase == RecordingPhase.RECORDING) {
                Spacer(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            }
            val clock = recordingTime(elapsed * 1_000)
            Text(if (reviewing && session.previewDurationMs > 0) "$clock / ${recordingTime(session.previewDurationMs)}" else clock,
                style = MaterialTheme.typography.titleMedium)
            if (session.held) Text(
                uiString(if (LocalLayoutDirection.current == LayoutDirection.Rtl)
                    R.string.ui_record_slide_right else R.string.ui_record_slide_left),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (session.phase == RecordingPhase.RECORDING || session.phase == RecordingPhase.PAUSED) {
                val levelColor = if (session.phase == RecordingPhase.RECORDING) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Canvas((if (session.held) Modifier.width(72.dp) else Modifier.weight(1f)).height(24.dp)) {
                    val step = 4.dp.toPx()
                    val levels = session.inputLevels
                    val slots = (size.width / step).toInt().coerceAtMost(28)
                    repeat(slots) { index ->
                        val level = levels.getOrNull(levels.size - slots + index) ?: 0f
                        val x = (index + 0.5f) * step
                        val center = if (rtl) slots * step - x else size.width - slots * step + x
                        val halfHeight = 1.dp.toPx() + level * (size.height / 2 - 2.dp.toPx())
                        drawLine(levelColor, Offset(center, size.height / 2 - halfHeight),
                            Offset(center, size.height / 2 + halfHeight), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            if (reviewing && session.previewDurationMs > 0) {
                val seekLabel = uiString(R.string.ui_record_playback_position)
                Slider(
                    value = scrub ?: session.previewPositionMs.toFloat().coerceIn(0f, session.previewDurationMs.toFloat()),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        scrub?.let { session.seekPreview(it.toLong()) }
                        scrub = null
                    },
                    valueRange = 0f..session.previewDurationMs.toFloat(),
                    enabled = canPreview && !session.closing,
                    modifier = Modifier.weight(1f).semantics { contentDescription = seekLabel }
                )
            }
        }
        session.error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
        if (session.busy || session.closing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!session.held && session.active) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !session.closing, onClick = onDiscard) {
                    Icon(Icons.Default.DeleteOutline, uiString(R.string.ui_discard), tint = MaterialTheme.colorScheme.error)
                }
                if (reviewing || session.phase == RecordingPhase.CLOSED) {
                    IconButton(enabled = !session.busy && !session.closing, onClick = onRecordAgain) {
                        Icon(Icons.Default.Refresh, uiString(R.string.ui_record_again))
                    }
                }
                when (session.phase) {
                    RecordingPhase.RECORDING, RecordingPhase.PAUSED -> {
                        val paused = session.phase == RecordingPhase.PAUSED
                        IconButton(enabled = !session.busy && !session.closing, onClick = session::togglePause) {
                            Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                uiString(if (paused) R.string.ui_record_resume else R.string.ui_pause))
                        }
                        FilledIconButton(enabled = !session.busy && !session.closing, onClick = session::finish) {
                            Icon(Icons.Default.Stop, uiString(R.string.ui_record_finish))
                        }
                    }
                    RecordingPhase.READY -> {
                        IconButton(enabled = canPreview && !session.closing, onClick = session::togglePreview) {
                            Icon(if (session.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                uiString(if (session.playing) R.string.ui_pause else R.string.ui_play_audio))
                        }
                        Button(enabled = !session.closing, onClick = { session.use(onRecorded) }) {
                            Text(uiString(R.string.ui_record_continue))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

internal fun recordingTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
internal fun VoiceRecordingDialog(
    session: VoiceCaptureSession, canRecord: Boolean, onStart: () -> Unit,
    onDismiss: () -> Unit, onRecorded: (RecordingMediaSource) -> Unit
) {
    fun dismiss() { if (!session.closing) session.discard(onDismiss) }
    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).widthIn(max = 560.dp).fillMaxWidth(.94f),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(uiString(R.string.ui_voice_message), style = MaterialTheme.typography.titleLarge)
                if (session.active) {
                    VoiceCaptureControls(session, canPreview = canRecord, onRecorded = onRecorded,
                        onDiscard = { dismiss() }, onRecordAgain = { session.discard() })
                } else {
                    if (!canRecord) Text(uiString(R.string.ui_record_end_call))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { dismiss() }) { Text(uiString(R.string.ui_cancel)) }
                        Button(enabled = canRecord, onClick = onStart) { Text(uiString(R.string.ui_attachment_record_voice)) }
                    }
                }
            }
        }
    }
}
