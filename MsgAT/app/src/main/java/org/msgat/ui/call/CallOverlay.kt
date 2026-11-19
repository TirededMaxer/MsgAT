package org.msgat.ui.call

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withStateAtLeast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.core.call.AudioDeviceManager
import org.msgat.core.call.CallManager
import org.msgat.core.call.CallOutcome
import org.msgat.core.call.CallState
import org.msgat.core.call.CallVideoFrames
import org.msgat.core.call.CallVideoSession
import org.msgat.data.db.AppDatabase
import org.msgat.ui.theme.uiString
import org.msgat.ui.theme.AppLanguage
import org.msgat.ui.components.rememberAvatarPainter
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private val callSurface = Color(0xFF222B27)
private val callBackground = Color(0xFF171E1B)
private val callSecondaryText = Color(0xFFA9BDB3)
private val callControl = Color(0xFF35473D)
private val callSelected = Color(0xFFBDE7CD)
private val callAccept = Color(0xFF126741)
private val callDecline = Color(0xFFAD3543)

@Composable
fun CallOverlay(
    modifier: Modifier = Modifier,
    answerCallId: String? = null,
    onAnswerHandled: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val callManager = remember { CallManager.getInstance() }
    val audioDeviceManager = remember { AudioDeviceManager.getInstance() }
    val callState by callManager.callState.collectAsState()
    val duration by callManager.callDurationSeconds.collectAsState()
    val rtt by callManager.rttMs.collectAsState()
    LaunchedEffect(audioDeviceManager) {
        audioDeviceManager.errors.collect { message ->
            val text = AppLanguage.restore(context).resources(context).getString(message)
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val videoCall by callManager.isVideoCall.collectAsState()
    val videoSession by callManager.videoSession.collectAsState()
    val peerUuid = callManager.currentPeerUuid
    val microphoneHint = uiString(R.string.ui_call_microphone_permission)
    val mediaHint = uiString(R.string.ui_call_media_permission)
    var permissionCallId by remember { mutableStateOf<String?>(null) }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val id = permissionCallId
        permissionCallId = null
        if (id != null && id == callManager.currentCallId && callManager.callState.value == CallState.INCOMING_RINGING) {
            val video = callManager.isVideoCall.value
            if (callManager.missingCallPermissions(video).isEmpty()) callManager.acceptCall()
            else android.widget.Toast.makeText(context, if (video) mediaHint else microphoneHint,
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun answer(id: String) {
        if (id != callManager.currentCallId || callManager.callState.value != CallState.INCOMING_RINGING ||
            permissionCallId != null) return
        val missing = callManager.missingCallPermissions(callManager.isVideoCall.value)
        if (missing.isEmpty()) callManager.acceptCall()
        else {
            permissionCallId = id
            callPermission.launch(missing.toTypedArray())
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(answerCallId, lifecycle) {
        val id = answerCallId ?: return@LaunchedEffect
        lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
            answer(id)
            onAnswerHandled(id)
        }
    }

    val isVisible = callState != CallState.IDLE && peerUuid != null
    val minimized by callManager.isMinimized.collectAsState()
    val recoveringConnection by callManager.recoveringConnection.collectAsState()
    val status = if (callState == CallState.CONNECTED && recoveringConnection) uiString(R.string.ui_call_reconnecting)
        else callStatusText(callState, videoCall, duration, callManager.endedOutcome)
    val conversationFlow = remember(peerUuid) {
        peerUuid?.let { AppDatabase.getInstance(context).msgDao().observeConversation(it) }
            ?: kotlinx.coroutines.flow.flowOf(null)
    }
    val peer = key(peerUuid) { conversationFlow.collectAsState(initial = null).value }
    val alias = peer?.alias?.takeIf { it.isNotBlank() } ?: uiString(R.string.ui_call_unknown_contact)
    val avatar = peer?.avatarPath.orEmpty()
    var dockCorner by remember(callManager.currentCallId) { mutableStateOf(CallDockCorner.TopRight) }
    var cameraCorner by remember(callManager.currentCallId) { mutableStateOf(CallDockCorner.BottomRight) }
    AnimatedVisibility(
        visible = isVisible && minimized,
        modifier = modifier,
        enter = slideInVertically(animationSpec = tween(300), initialOffsetY = { it }) + fadeIn(tween(300)),
        exit = slideOutVertically(animationSpec = tween(300), targetOffsetY = { it }) + fadeOut(tween(300))
    ) {
        MinimizedCallOverlay(
            status = status,
            isMuted = audioDeviceManager.isMicrophoneMuted.collectAsState().value,
            alias = alias,
            avatar = avatar,
            dockCorner = dockCorner,
            onDock = { dockCorner = it },
            onExpand = { callManager.isMinimized.value = false }
        )
    }

    if (isVisible && !minimized) {
        Dialog(
            onDismissRequest = { callManager.isMinimized.value = true },
            properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)
        ) {
            if (callState == CallState.INCOMING_RINGING) {
                IncomingCallScreen(
                    alias = alias, avatar = avatar, accepting = permissionCallId != null,
                    onAccept = { callManager.currentCallId?.let(::answer) },
                    onReject = { callManager.rejectCall() }, video = videoCall
                )
            } else {
                val video = videoSession
                if (video != null) {
                    VideoCallScreen(alias, callState, status, video, callManager, audioDeviceManager,
                        cameraCorner, onDockCamera = { cameraCorner = it })
                } else {
                    CallScreen(
                        alias = alias,
                        avatar = avatar,
                        state = callState,
                        status = status,
                        rtt = rtt,
                        isMuted = audioDeviceManager.isMicrophoneMuted.collectAsState().value,
                        audioDevices = audioDeviceManager,
                        onToggleMute = {
                            val newMute = !audioDeviceManager.isMicrophoneMuted.value
                            audioDeviceManager.setMicrophoneMute(newMute)
                            callManager.setMicrophoneMute(newMute)
                        },
                        onEndCall = { callManager.endCall() },
                        onMinimize = { callManager.isMinimized.value = true }
                    )
                }
            }
        }
    }
}

@Composable
fun IncomingCallScreen(
    alias: String,
    avatar: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    video: Boolean = false,
    accepting: Boolean = false
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(callSurface, callBackground)))
            .safeDrawingPadding()
    ) {
        val compact = maxHeight < 480.dp
        val actionSize = if (compact) 48.dp else 64.dp
        val rejectLabel = uiString(R.string.ui_reject_call)
        val acceptLabel = uiString(R.string.ui_accept_call)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            CallIdentity(alias, avatar,
                uiString(if (video) R.string.ui_incoming_video_call else R.string.ui_incoming_voice_call), compact)
            Spacer(Modifier.weight(2f))
            Row(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .padding(bottom = if (compact) 8.dp else 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IncomingCallAction(rejectLabel, Icons.Default.CallEnd, callDecline, actionSize,
                    modifier = Modifier.weight(1f), onClick = onReject)
                IncomingCallAction(acceptLabel, if (video) Icons.Default.Videocam else Icons.Default.Call,
                    callAccept, actionSize, modifier = Modifier.weight(1f), enabled = !accepting, onClick = onAccept)
            }
        }
    }
}

@Composable
private fun IncomingCallAction(
    label: String,
    icon: ImageVector,
    color: Color,
    size: Dp,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(150), label = "Call action")
    Column(
        modifier.clip(MaterialTheme.shapes.medium).clickable(
            interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick
        ).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(size).scale(scale).background(if (enabled) color else callControl, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(label, color = if (enabled) Color.White else callSecondaryText,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CallIdentity(alias: String, avatar: String, status: String, compact: Boolean, ended: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 10.dp)
    ) {
        CallAvatar(alias, avatar, Modifier.size(if (compact) 56.dp else 104.dp))
        Text(alias, color = Color.White,
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, color = if (ended) Color(0xFFFFABB1) else callSecondaryText,
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VideoCallScreen(
    alias: String, state: CallState, status: String, session: CallVideoSession,
    manager: CallManager, audio: AudioDeviceManager,
    cameraCorner: CallDockCorner, onDockCamera: (CallDockCorner) -> Unit
) {
    val enabled by session.enabled.collectAsState()
    val front by session.front.collectAsState()
    val remote by session.remoteAvailable.collectAsState()
    val error by session.error.collectAsState()
    val muted by audio.isMicrophoneMuted.collectAsState()
    Column(
        Modifier.fillMaxSize().background(callSurface).windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { manager.isMinimized.value = true }) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = uiString(R.string.ui_minimize_call), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(alias, color = Color.White, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status,
                    color = callSecondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            if (remote) VideoSurface(session.remoteFrames, session.eglContext, false, false, Modifier.matchParentSize())
            else Text(uiString(R.string.ui_call_waiting_video), color = Color.LightGray, modifier = Modifier.align(Alignment.Center))
            if (enabled) {
                val width = minOf(108.dp, maxWidth * 0.4f, maxHeight * 0.3f)
                DockedCallTile(DpSize(width, width * (4f / 3f)), cameraCorner, onDockCamera) {
                    VideoSurface(session.localFrames, session.eglContext, front, true, Modifier.matchParentSize())
                }
            }
        }
        error?.let { Text(it, color = Color.White, modifier = Modifier.padding(8.dp)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                audio.setMicrophoneMute(!muted)
                manager.setMicrophoneMute(!muted)
            }) {
                Icon(if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                    uiString(if (muted) R.string.ui_unmute else R.string.ui_mute), tint = Color.White)
            }
            IconButton(onClick = { manager.setCameraEnabled(!enabled) }) {
                Icon(if (enabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    uiString(if (enabled) R.string.ui_call_camera_off else R.string.ui_call_camera_on), tint = Color.White)
            }
            IconButton(enabled = enabled, onClick = session::switchCamera) {
                Icon(Icons.Default.Refresh, contentDescription = uiString(R.string.ui_call_switch_camera),
                    tint = if (enabled) Color.White else Color.Gray)
            }
            CallAudioRouteButton(audio, enabled = state != CallState.ENDED)
            IconButton(onClick = manager::endCall, modifier = Modifier.background(callDecline, CircleShape)) {
                Icon(Icons.Default.CallEnd, contentDescription = uiString(R.string.ui_end_call), tint = Color.White)
            }
        }
    }
}

@Composable
private fun VideoSurface(frames: CallVideoFrames, egl: EglBase.Context, mirror: Boolean, local: Boolean, modifier: Modifier) {
    key(frames) {
        AndroidView(
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    setSecure(true)
                    setZOrderMediaOverlay(local)
                    init(egl, null)
                    setScalingType(if (local) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    frames.attach(this)
                }
            },
            modifier = modifier,
            onReset = null,
            onRelease = { renderer ->
                frames.detach(renderer)
                renderer.release()
            },
            update = { it.setMirror(mirror) }
        )
    }
}

@Composable
private fun CallAudioRouteButton(audio: AudioDeviceManager, enabled: Boolean, modifier: Modifier = Modifier) {
    val devices by audio.availableDevices.collectAsState()
    val selected by audio.selectedDevice.collectAsState()
    val automatic by audio.automatic.collectAsState()
    val switching by audio.switching.collectAsState()
    var showRoutes by remember { mutableStateOf(false) }
    val switchingLabel = uiString(R.string.ui_call_switching_audio)
    val devicesLabel = uiString(R.string.ui_call_audio_devices)
    val automaticLabel = uiString(R.string.ui_call_audio_automatic)
    val deviceName = selected?.let { callAudioDeviceLabel(it) }
    Box {
        IconButton(onClick = { showRoutes = true }, enabled = enabled, modifier = modifier) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (switching) switchingLabel
                    else deviceName?.let { uiString(R.string.ui_call_selected_audio, it) } ?: devicesLabel,
                tint = Color.White)
        }
        DropdownMenu(expanded = showRoutes && enabled, onDismissRequest = { showRoutes = false }) {
            DropdownMenuItem(text = { Text(if (automatic && deviceName != null)
                uiString(R.string.ui_call_audio_automatic_device, deviceName) else automaticLabel) },
                onClick = { showRoutes = false; audio.selectDevice(null) },
                modifier = Modifier.semantics { this.selected = automatic },
                trailingIcon = { if (automatic) Icon(Icons.Default.Check, contentDescription = null) })
            for (device in devices) {
                val chosen = !automatic && selected?.id == device.id
                DropdownMenuItem(text = { Text(callAudioDeviceLabel(device)) },
                    onClick = { showRoutes = false; audio.selectDevice(device) },
                    modifier = Modifier.semantics { this.selected = chosen },
                    trailingIcon = { if (chosen) Icon(Icons.Default.Check, contentDescription = null) })
            }
        }
    }
}

@Composable
private fun callAudioDeviceLabel(device: android.media.AudioDeviceInfo): String =
    when (device.type) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> uiString(R.string.ui_call_speaker)
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> uiString(R.string.ui_call_earpiece)
        else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: uiString(R.string.ui_call_audio_device)
    }

@Composable
private fun CallScreen(
    alias: String,
    avatar: String,
    state: CallState,
    status: String,
    rtt: Long,
    isMuted: Boolean,
    audioDevices: AudioDeviceManager,
    onToggleMute: () -> Unit,
    onEndCall: () -> Unit,
    onMinimize: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(callSurface, callBackground)))
            .safeDrawingPadding()
    ) {
        val compact = maxHeight < 480.dp
        val actionSize = if (compact) 52.dp else 64.dp
        IconButton(
            onClick = onMinimize,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = uiString(R.string.ui_minimize_call),
                tint = Color.White, modifier = Modifier.size(32.dp))
        }

        if (state == CallState.CONNECTED) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection quality indicator
                val color = if (rtt <= 0) Color.Gray else if (rtt < 150) Color.Green else if (rtt < 300) Color.Yellow else Color.Red
                Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = if (rtt > 0) "${rtt}ms" else "—", color = Color.White, fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(if (compact) 44.dp else 76.dp))
            CallIdentity(alias, avatar, status, compact, ended = state == CallState.ENDED)

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = if (compact) 12.dp else 36.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallAudioRouteButton(audioDevices, enabled = state != CallState.ENDED,
                    modifier = Modifier.size(actionSize).background(callControl, CircleShape))

                val muteInteraction = remember { MutableInteractionSource() }
                val isMutePressed by muteInteraction.collectIsPressedAsState()
                val muteScale by animateFloatAsState(targetValue = if (isMutePressed) 0.85f else 1f, animationSpec = tween(150), label = "muteScale")

                IconButton(
                    onClick = onToggleMute,
                    enabled = state != CallState.ENDED,
                    interactionSource = muteInteraction,
                    modifier = Modifier
                        .size(actionSize)
                        .scale(muteScale)
                        .background(if (isMuted) callSelected else callControl, CircleShape)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = uiString(if (isMuted) R.string.ui_unmute else R.string.ui_mute),
                        tint = if (isMuted) callBackground else Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                val endInteraction = remember { MutableInteractionSource() }
                val isEndPressed by endInteraction.collectIsPressedAsState()
                val endScale by animateFloatAsState(targetValue = if (isEndPressed) 0.85f else 1f,
                    animationSpec = tween(150), label = "endScale")
                IconButton(
                    onClick = onEndCall,
                    enabled = state != CallState.ENDED,
                    interactionSource = endInteraction,
                    modifier = Modifier.size(actionSize).scale(endScale).background(callDecline, CircleShape)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = uiString(R.string.ui_end_call),
                        tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun CallAvatar(alias: String, avatar: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(callControl).clearAndSetSemantics {},
        contentAlignment = Alignment.Center) {
        Text(alias.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (avatar.isNotEmpty()) {
            Image(rememberAvatarPainter(avatar), contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop)
        }
    }
}

private enum class CallDockCorner(val right: Boolean, val bottom: Boolean) {
    TopLeft(false, false), TopRight(true, false),
    BottomLeft(false, true), BottomRight(true, true)
}

@Composable
private fun callStatusText(state: CallState, video: Boolean, duration: Long, outcome: CallOutcome): String = when (state) {
    CallState.OUTGOING_RINGING -> uiString(if (video) R.string.ui_video_calling else R.string.ui_calling)
    CallState.INCOMING_RINGING -> uiString(if (video) R.string.ui_incoming_video_call else R.string.ui_incoming_voice_call)
    CallState.CONNECTING -> uiString(R.string.ui_call_connecting)
    CallState.CONNECTED -> "%02d:%02d".format(duration / 60, duration % 60)
    CallState.ENDED -> uiString(when (outcome) {
        CallOutcome.Completed -> R.string.ui_call_ended
        CallOutcome.Missed -> R.string.ui_call_missed
        CallOutcome.Declined -> R.string.ui_call_declined
        CallOutcome.Busy -> R.string.ui_call_busy
        CallOutcome.Canceled -> R.string.ui_call_canceled
        CallOutcome.Failed -> R.string.ui_call_failed
    })
    else -> ""
}

@Composable
private fun MinimizedCallOverlay(
    status: String,
    isMuted: Boolean,
    alias: String,
    avatar: String,
    dockCorner: CallDockCorner,
    onDock: (CallDockCorner) -> Unit,
    onExpand: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val tileWidth = minOf(158.dp, maxWidth)
        val tileHeight = minOf(70.dp, maxHeight)
        val shape = RoundedCornerShape(14.dp)
        DockedCallTile(
            size = DpSize(tileWidth, tileHeight),
            dockCorner = dockCorner,
            onDock = onDock,
            modifier = Modifier
                .clip(shape)
                .background(callSurface)
                .border(1.dp, callControl, shape)
                .clickable(onClickLabel = uiString(R.string.ui_restore_call), role = Role.Button, onClick = onExpand)
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CallAvatar(alias, avatar, Modifier.size(34.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(alias, color = Color.White, style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(status, color = callSecondaryText, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (isMuted) {
                            Icon(Icons.Default.MicOff, uiString(R.string.ui_call_microphone_muted),
                                tint = callSecondaryText, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.DockedCallTile(
    size: DpSize,
    dockCorner: CallDockCorner,
    onDock: (CallDockCorner) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val maxX = (maxWidth - size.width).value.coerceAtLeast(0f)
    val maxY = (maxHeight - size.height).value.coerceAtLeast(0f)
    val insetX = minOf(12f, maxX / 2)
    val insetY = minOf(12f, maxY / 2)
    val dockedPosition = Offset(
        if (dockCorner.right) maxX - insetX else insetX,
        if (dockCorner.bottom) maxY - insetY else insetY
    )
    var draggedPosition by remember(maxX, maxY) { mutableStateOf<Offset?>(null) }
    val updateDock by rememberUpdatedState(onDock)
    val position by animateOffsetAsState(
        targetValue = draggedPosition ?: dockedPosition,
        animationSpec = if (draggedPosition == null) tween(350, easing = LinearOutSlowInEasing) else snap(),
        label = "CallDockPosition"
    )

    Box(
        modifier = Modifier
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset {
                IntOffset(
                    position.x.coerceIn(0f, maxX).dp.roundToPx(),
                    position.y.coerceIn(0f, maxY).dp.roundToPx()
                )
            }
            .size(size.width, size.height)
            .then(modifier)
            .pointerInput(density.density, maxX, maxY) {
                detectDragGestures(
                    onDragStart = { draggedPosition = position },
                    onDragCancel = { draggedPosition = null },
                    onDragEnd = {
                        draggedPosition?.let { end ->
                            val right = end.x >= maxX / 2
                            val bottom = end.y >= maxY / 2
                            updateDock(CallDockCorner.entries.first { it.right == right && it.bottom == bottom })
                        }
                        draggedPosition = null
                    }
                ) { change, dragAmount ->
                    val start = draggedPosition ?: return@detectDragGestures
                    change.consume()
                    draggedPosition = Offset(
                        (start.x + dragAmount.x / density.density).coerceIn(0f, maxX),
                        (start.y + dragAmount.y / density.density).coerceIn(0f, maxY)
                    )
                }
            },
        contentAlignment = Alignment.Center,
        content = content
    )
}
