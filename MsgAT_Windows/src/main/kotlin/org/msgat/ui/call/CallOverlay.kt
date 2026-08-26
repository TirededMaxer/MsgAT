package org.msgat.ui.call

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.msgat.AppState
import org.msgat.core.call.AudioDeviceManager
import org.msgat.core.call.CallManager
import org.msgat.core.call.CallState
import org.msgat.utils.ImageUtils
import java.io.File

@Composable
fun CallOverlay(
    modifier: Modifier = Modifier
) {
    val callManager = remember { CallManager.getInstance() }
    val audioDeviceManager = remember { AudioDeviceManager.getInstance() }
    val callState by callManager.callState.collectAsState()
    val duration by callManager.callDurationSeconds.collectAsState()
    val rtt by callManager.rttMs.collectAsState()
    val peerUuid = callManager.currentPeerUuid

    val isVisible = callState != CallState.IDLE && peerUuid != null

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        var isMinimized by remember { mutableStateOf(false) }
        var alias by remember { mutableStateOf("Unknown") }
        var avatar by remember { mutableStateOf("") }

        LaunchedEffect(peerUuid) {
            if (peerUuid == null) return@LaunchedEffect
            val conv = AppState.msgDao.getConversationByUuid(peerUuid)
            if (conv != null) {
                alias = conv.alias
                avatar = conv.avatarPath
            }
        }

        AnimatedContent(
            targetState = isMinimized,
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.8f)).togetherWith(fadeOut() + scaleOut(targetScale = 0.8f))
            },
            label = "CallOverlayTransition"
        ) { minimized ->
            if (minimized) {
                MinimizedCallOverlay(
                    duration = duration,
                    alias = alias,
                    avatar = avatar,
                    onExpand = { isMinimized = false }
                )
            } else {
                if (callState == CallState.INCOMING_RINGING) {
                    IncomingCallScreen(
                        alias = alias,
                        avatar = avatar,
                        onAccept = { callManager.acceptCall() },
                        onReject = { callManager.rejectCall() }
                    )
                } else {
                    CallScreen(
                        alias = alias,
                        avatar = avatar,
                        state = callState,
                        duration = duration,
                        rtt = rtt,
                        isMuted = audioDeviceManager.isMicrophoneMuted.collectAsState().value,
                        isSpeakerOn = audioDeviceManager.isSpeakerphoneOn.collectAsState().value,
                        onToggleMute = {
                            val newMute = !audioDeviceManager.isMicrophoneMuted.value
                            audioDeviceManager.setMicrophoneMute(newMute)
                            callManager.setMicrophoneMute(newMute)
                        },
                        onToggleSpeaker = { audioDeviceManager.toggleSpeakerphone() },
                        onEndCall = { callManager.endCall() },
                        onMinimize = { isMinimized = true }
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
    onReject: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2C2C2C))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(100.dp))
            
            val avatarFile = if (avatar.isNotEmpty()) File(avatar) else null
            val bitmap = remember(avatar) { ImageUtils.loadBitmap(avatarFile) }

            Surface(
                shape = CircleShape,
                color = Color.DarkGray,
                modifier = Modifier.size(120.dp)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(alias.take(1).uppercase(), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = alias, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Incoming Voice Call...", color = Color.Gray, fontSize = 16.sp)

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 60.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val rejectInteraction = remember { MutableInteractionSource() }
                    val isRejectPressed by rejectInteraction.collectIsPressedAsState()
                    val rejectScale by animateFloatAsState(targetValue = if (isRejectPressed) 0.85f else 1f, label = "rejectScale")

                    IconButton(
                        onClick = onReject,
                        interactionSource = rejectInteraction,
                        modifier = Modifier
                            .size(72.dp)
                            .scale(rejectScale)
                            .background(Color.Red, CircleShape)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Reject", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Decline", color = Color.White)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val acceptInteraction = remember { MutableInteractionSource() }
                    val isAcceptPressed by acceptInteraction.collectIsPressedAsState()
                    val acceptScale by animateFloatAsState(targetValue = if (isAcceptPressed) 0.85f else 1f, label = "acceptScale")

                    IconButton(
                        onClick = onAccept,
                        interactionSource = acceptInteraction,
                        modifier = Modifier
                            .size(72.dp)
                            .scale(acceptScale)
                            .background(Color(0xFF4CAF50), CircleShape)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Accept", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun CallScreen(
    alias: String,
    avatar: String,
    state: CallState,
    duration: Long,
    rtt: Long,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit,
    onMinimize: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2C2C2C))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        IconButton(
            onClick = onMinimize,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", tint = Color.White, modifier = Modifier.size(32.dp))
        }

        if (state == CallState.CONNECTED) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val color = if (rtt < 150) Color.Green else if (rtt < 300) Color.Yellow else Color.Red
                Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "${rtt}ms", color = Color.White, fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(100.dp))

            val avatarFile = if (avatar.isNotEmpty()) File(avatar) else null
            val bitmap = remember(avatar) { ImageUtils.loadBitmap(avatarFile) }

            Surface(
                shape = CircleShape,
                color = Color.DarkGray,
                modifier = Modifier.size(120.dp)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(alias.take(1).uppercase(), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = alias, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))

            val statusText = when (state) {
                CallState.OUTGOING_RINGING -> "Calling..."
                CallState.CONNECTING -> "Connecting..."
                CallState.CONNECTED -> {
                    val mins = duration / 60
                    val secs = duration % 60
                    String.format("%02d:%02d", mins, secs)
                }
                CallState.ENDED -> "Call Ended"
                else -> ""
            }
            Text(text = statusText, color = if (state == CallState.ENDED) Color.Red else Color.LightGray, fontSize = 18.sp)

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val muteInteraction = remember { MutableInteractionSource() }
                val isMutePressed by muteInteraction.collectIsPressedAsState()
                val muteScale by animateFloatAsState(targetValue = if (isMutePressed) 0.85f else 1f, label = "muteScale")

                IconButton(
                    onClick = onToggleMute,
                    interactionSource = muteInteraction,
                    modifier = Modifier
                        .size(64.dp)
                        .scale(muteScale)
                        .background(if (isMuted) Color.White else Color(0x33FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute",
                        tint = if (isMuted) Color.Black else Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                val speakerInteraction = remember { MutableInteractionSource() }
                val isSpeakerPressed by speakerInteraction.collectIsPressedAsState()
                val speakerScale by animateFloatAsState(targetValue = if (isSpeakerPressed) 0.85f else 1f, label = "speakerScale")

                IconButton(
                    onClick = onToggleSpeaker,
                    interactionSource = speakerInteraction,
                    modifier = Modifier
                        .size(64.dp)
                        .scale(speakerScale)
                        .background(if (isSpeakerOn) Color.White else Color(0x33FFFFFF), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Speaker",
                        tint = if (isSpeakerOn) Color.Black else Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            val endInteraction = remember { MutableInteractionSource() }
            val isEndPressed by endInteraction.collectIsPressedAsState()
            val endScale by animateFloatAsState(targetValue = if (isEndPressed) 0.85f else 1f, label = "endScale")

            IconButton(
                onClick = onEndCall,
                interactionSource = endInteraction,
                modifier = Modifier
                    .size(72.dp)
                    .scale(endScale)
                    .background(Color.Red, CircleShape)
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}

@Composable
fun MinimizedCallOverlay(
    duration: Long,
    alias: String,
    avatar: String,
    onExpand: () -> Unit
) {
    var offsetX by remember { mutableStateOf(20f) }
    var offsetY by remember { mutableStateOf(100f) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .offset(x = offsetX.dp, y = offsetY.dp)
                .size(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCC000000))
                .clickable { onExpand() }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceAtLeast(0f)
                        offsetY = (offsetY + dragAmount.y).coerceAtLeast(0f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val avatarFile = if (avatar.isNotEmpty()) File(avatar) else null
                val bitmap = remember(avatar) { ImageUtils.loadBitmap(avatarFile) }

                Surface(
                    shape = CircleShape,
                    color = Color.DarkGray,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(alias.take(1).uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val mins = duration / 60
                val secs = duration % 60
                Text(text = String.format("%02d:%02d", mins, secs), color = Color.Green, fontSize = 12.sp)
            }
        }
    }
}
