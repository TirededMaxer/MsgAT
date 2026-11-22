package org.msgat.ui.chat

import org.msgat.ui.theme.uiString
import org.msgat.R
import org.msgat.core.network.PeerActivityStatus
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.CancellationException
import org.msgat.core.media.SharedMediaCursor
import org.msgat.core.media.SharedMediaCategory
import org.msgat.core.media.SharedMediaItem
import org.msgat.core.media.VisualMediaNeighbors
import org.msgat.ui.theme.LocalAppearanceSettings
import org.msgat.ui.theme.LocalMessagingColors
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Edit

import java.util.Date
import java.util.Locale

enum class SyncState { DISCONNECTED, SYNCING, SYNCED }

private data class TimelineLayout(
    val day: java.time.LocalDate?,
    val joinsOlder: Boolean,
    val joinsNewer: Boolean
)

private data class TimelineFollowSample(
    val newest: Pair<String, Long>?,
    val viewport: IntSize,
    val latestReady: Boolean,
    val atBottom: Boolean,
    val scrolling: Boolean
)

@Composable
private fun Modifier.timelineArrival(startedAt: Long?, isMine: Boolean): Modifier {
    if (startedAt == null) return this
    val duration = 250
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val origin = androidx.compose.ui.graphics.TransformOrigin(if (isMine != rtl) 1f else 0f, 1f)
    val movement = remember(startedAt) {
        val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0)
        Animatable((elapsed / duration.toFloat()).coerceIn(0f, 1f))
    }
    LaunchedEffect(startedAt) {
        val remaining = (duration - (android.os.SystemClock.uptimeMillis() - startedAt))
            .coerceIn(0L, duration.toLong()).toInt()
        if (remaining > 0) {
            movement.animateTo(1f, tween(remaining, easing = CubicBezierEasing(0.199f, 0.011f, 0.279f, 0.910f)))
        } else movement.snapTo(1f)
    }
    return graphicsLayer {
        transformOrigin = origin
        val progress = movement.value
        scaleX = 0.9f + progress * 0.1f
        scaleY = scaleX
        alpha = progress
    }
}

private data class QuoteReturnAnchor(val messageId: String, val offset: Int)
internal data class ChatHistoryPosition(val messageId: String, val offset: Int)

private fun canJoinTimelineMessages(older: MessageUIModel, newer: MessageUIModel): Boolean =
    older.isMine == newer.isMine && !older.isRevoked && !newer.isRevoked &&
        older.forwardInfo == null && newer.forwardInfo == null &&
        older.ext in clusteredMessageTypes && newer.ext in clusteredMessageTypes &&
        older.attachment?.album == null && newer.attachment?.album == null &&
        older.timestamp >= 0 && newer.timestamp >= older.timestamp && newer.timestamp - older.timestamp <= 5 * 60 * 1000

private val clusteredMessageTypes = setOf("txt", "attachment", "contact", "location", "live_location")

@Composable
private fun TimelineDayLabel(day: java.time.LocalDate, onClick: () -> Unit) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.getDefault()
    val today = java.time.LocalDate.now()
    val label = when (day) {
        today -> uiString(R.string.ui_timeline_today)
        today.minusDays(1) -> uiString(R.string.ui_timeline_yesterday)
        else -> remember(day, locale) {
            day.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 7.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TimelineUnreadDivider() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val accent = MaterialTheme.colorScheme.primary
        HorizontalDivider(Modifier.weight(1f), color = accent.copy(alpha = 0.35f))
        Text(uiString(R.string.ui_unread_messages), color = accent, style = MaterialTheme.typography.labelMedium)
        HorizontalDivider(Modifier.weight(1f), color = accent.copy(alpha = 0.35f))
    }
}

@Composable
private fun ChatHeaderTitle(
    alias: String,
    avatarPath: String,
    connection: SyncState,
    peerActivity: PeerActivityStatus,
    peerVerified: Boolean,
    muted: Boolean,
    openProfile: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val profileLabel = uiString(R.string.ui_contact_profile)
    val status = uiString(when (connection) {
        SyncState.DISCONNECTED -> R.string.ui_disconnected
        SyncState.SYNCING -> R.string.ui_syncing
        SyncState.SYNCED -> R.string.ui_connected
    })
    Row(
        modifier = Modifier.heightIn(min = 48.dp).clickable(
            role = Role.Button, onClickLabel = profileLabel, onClick = openProfile
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(shape = CircleShape, color = colors.secondaryContainer, modifier = Modifier.size(36.dp)) {
            if (avatarPath.isNotEmpty()) {
                androidx.compose.foundation.Image(
                    painter = org.msgat.ui.components.rememberAvatarPainter(avatarPath),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(alias.take(1).uppercase(), color = colors.onSecondaryContainer, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        Column(Modifier.weight(1f, fill = false)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(alias, modifier = Modifier.weight(1f, fill = false), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                if (peerVerified) Icon(Icons.Default.VerifiedUser, uiString(R.string.ui_contact_verified),
                    Modifier.size(14.dp), tint = colors.primary)
                if (muted) Icon(Icons.Default.NotificationsOff, uiString(R.string.ui_conversation_muted),
                    Modifier.size(13.dp), tint = colors.onSurfaceVariant)
            }
            val activityText = when (peerActivity) {
                PeerActivityStatus.NONE -> null
                PeerActivityStatus.TYPING -> uiString(R.string.ui_peer_typing)
                PeerActivityStatus.ONLINE -> uiString(R.string.ui_peer_online)
                PeerActivityStatus.OFFLINE -> uiString(R.string.ui_peer_offline)
            }
            val typing = peerActivity == PeerActivityStatus.TYPING
            val statusColor = if (typing || peerActivity == PeerActivityStatus.ONLINE) colors.primary else colors.onSurfaceVariant
            Row(
                modifier = if (activityText == null) Modifier else Modifier.semantics { stateDescription = status },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (connection) {
                    SyncState.DISCONNECTED -> Box(Modifier.size(7.dp).background(colors.error, CircleShape))
                    SyncState.SYNCING -> CircularProgressIndicator(
                        color = colors.primary, modifier = Modifier.size(10.dp).clearAndSetSemantics {},
                        strokeWidth = 1.5.dp
                    )
                    SyncState.SYNCED -> Box(Modifier.size(7.dp).background(colors.primary, CircleShape))
                }
                if (typing) TypingDots(statusColor)
                Text(activityText ?: status, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp, color = statusColor)
            }
        }
    }
}

@Composable
private fun TypingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "Typing dots")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(960, easing = LinearEasing)),
        label = "Typing cycle"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            Box(Modifier.size(3.dp).graphicsLayer {
                val angle = (phase.value - index / 6.0) * (2 * Math.PI)
                val pulse = (0.5 + 0.5 * kotlin.math.cos(angle)).toFloat()
                alpha = 0.45f + 0.55f * pulse
            }.background(color, CircleShape))
        }
    }
}

internal data class ChatMessageNavigation(
    val peer: String,
    val messageId: String?,
    val open: suspend () -> Boolean,
    val cancel: () -> Unit
)

private data class EditExitRequest(val proceed: () -> Unit, val keep: () -> Unit)

private val ChatColumnWidth = 856.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
// Renders the main chat interface including the message history and input fields.
internal fun ChatScreen(
    alias: String,
    avatarPath: String,
    peerActivity: PeerActivityStatus,
    peerVerified: Boolean,
    sendOptions:org.msgat.core.network.SendOptions,
    preparations:List<org.msgat.core.media.PendingPreparation>,
    onCancelPreparation:(String)->Unit,
    onSetSendOptions:(org.msgat.core.network.SendOptions)->Unit,
    onChatActivity:(Boolean,Boolean)->Unit,
    myUuid: String,
    peerUuid: String,
    muteUntil: Long,
    onChangeConversationMute: (String, Long?) -> Unit,
    initialMessageId: String?,
    searchRevealRevision: Long?,
    incomingShare: org.msgat.ui.components.IncomingShare?,
    onIncomingShareHandled: (org.msgat.ui.components.IncomingShare) -> Unit,
    workspacePanelCoveringChat: Boolean,
    messageNavigation: ChatMessageNavigation?,
    initialUnreadMessageId: String?,
    unreadCount: Int,
    recentReadIds: Set<String>,
    onSearch: () -> Unit,
    searchQuery: String?,
    searchMoving: Boolean,
    onReturnToSearch: (() -> Unit)?,
    onMoveSearch: ((Boolean) -> Unit)?,
    onCloseSearch: (() -> Unit)?,
    onRevealMessage: (String?) -> Unit,
    onNewestMessage: suspend () -> String?,
    onLatestEditable: suspend () -> MessageUIModel?,
    onAdjacentReply: suspend (MessageUIModel?, Boolean) -> MessageUIModel?,
    onMessageAtDate: suspend (Long, Long) -> String?,
    onFirstUnread: suspend () -> String?,
    onLoadExpiration: suspend () -> org.msgat.data.db.MessageRetentionSettings,
    onSetExpiration: suspend (org.msgat.data.db.MessageRetentionSettings) -> Boolean,
    removedMessages: kotlinx.coroutines.flow.SharedFlow<Set<String>>,
    observeMessageReceipts: (List<String>) -> kotlinx.coroutines.flow.Flow<Map<String, Int>>,
    observeReplySource: (String) -> kotlinx.coroutines.flow.Flow<MessageUIModel?>,
    pinnedPreview: ChatViewModel.PinnedPreview?,
    onCyclePinnedPreview: () -> Unit,
    onMessagePinState:suspend(String)->Boolean,
    onSetMessagePinned:suspend(String,Boolean,Boolean)->Boolean,
    onForwardMessages: (List<String>, () -> Unit) -> Unit,
    onSaveMessages: (List<String>, () -> Unit, () -> Unit, () -> Unit, (String) -> Unit) -> Unit,
    onLoadSelectedText: suspend (List<String>) -> List<String>,
    canQuoteMessage: suspend (String, String) -> Boolean,
    syncState: SyncState,
    messages: androidx.paging.compose.LazyPagingItems<MessageUIModel>,
    attachmentProgress: Map<String, org.msgat.core.network.AttachmentProgress>,
    initialDraft: DraftEditorState?,
    webPreview: org.msgat.core.network.WebPreviewDraft?,
    previewEnabled: Boolean,
    previewChoices: List<String>,
    previewTarget: String?,
    previewAboveText: Boolean,
    previewCompactImage: Boolean,
    previewLoading: Boolean,
    onTogglePreviewImageSize: () -> Unit,
    onTogglePreviewPlacement: () -> Unit,
    onSelectPreview: (String) -> Unit,
    onPreviewEnabled: (Boolean)->Unit,
    onRemovePreview: ()->Unit,
    onRetryPreview: ()->Unit,
    onDraftChanged: (String, String?, String?) -> Unit,
    onBack: () -> Unit,
    onSendMessage: (text: String, replyToId: String?, replyQuote: String?, options: org.msgat.core.network.SendOptions?, onSuccess: () -> Unit, onFail: () -> Unit) -> Unit,
    textSendPending: Boolean,
    onSelectMedia: (uris: List<android.net.Uri>, text: String, replyToId: String?, replyQuote: String?, choice:org.msgat.core.network.MediaSendChoice, onSuccess: () -> Unit, onFail: () -> Unit, onComplete: () -> Unit, onResult: (org.msgat.core.media.PreparationSendResult) -> Unit, onPreparationStarted: (String) -> Unit) -> Unit,
    onSendSticker: suspend (String, String, String?, String?) -> org.msgat.core.media.PreparationSendResult,
    localContactName: String,
    onLoadCardContacts: suspend () -> List<org.msgat.data.db.Conversation>,
    onSendContactCard: suspend (String, String, String, String?, String?, org.msgat.core.network.SendOptions) -> Boolean,
    onReadCardAccount: suspend (String) -> String?,
    onOpenContactChat: (String, () -> Unit) -> Unit,
    onSendLocation: suspend (org.msgat.core.network.LocationPoint, String?, String, String?, String?, org.msgat.core.network.SendOptions) -> Boolean,
    onReadLocation: suspend (String) -> org.msgat.core.network.LocationPoint?,
    onStartLiveLocation: suspend (org.msgat.core.network.LocationPoint, Long, String?, String, String?, String?, Boolean) -> Boolean,
    onReadLiveLocation: suspend (String) -> org.msgat.data.db.LiveLocationSnapshot?,
    onStopLiveLocation: suspend (String) -> Boolean,
    liveLocationChanges: kotlinx.coroutines.flow.SharedFlow<String>,
    activeLiveLocations: kotlinx.coroutines.flow.StateFlow<Map<String, org.msgat.core.network.LiveLocationShare>>,
    onStartCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onMarkAsRead: suspend (List<String>) -> Boolean,
    onRevokeMessage: (messageId: String) -> Unit = {},
    onEditMessage: suspend (messageId: String, newContent: String) -> Boolean = { _, _ -> false },
    historyPosition: ChatHistoryPosition? = null,
    hasHistoryMessage: suspend (id: String, includeRevoked: Boolean) -> Boolean,
    onSaveHistoryPosition: (ChatHistoryPosition?) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val composerState = rememberTextFieldState()
    val inputText = composerState.text.toString()
    var composerFocused by remember { mutableStateOf(false) }
    var unreadTimelineAnchor by rememberSaveable(peerUuid, initialUnreadMessageId) { mutableStateOf(initialUnreadMessageId) }
    var showAttachmentMenu by remember(peerUuid) { mutableStateOf(false) }
    var attachmentPanel by remember(peerUuid) { mutableStateOf(AttachmentPanelState()) }
    var returnToAttachmentPanel by remember(peerUuid) { mutableStateOf(false) }
    var pickerSection by rememberSaveable(peerUuid) { mutableStateOf<AttachmentSection?>(null) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showStickers by remember(peerUuid) { mutableStateOf(false) }
    var stickersExpanded by remember(peerUuid) { mutableStateOf(false) }
    var stickerBusy by remember(peerUuid) { mutableStateOf(false) }
    val panelFocus = androidx.compose.ui.platform.LocalFocusManager.current
    fun openStickers() {
        panelFocus.clearFocus()
        keyboard?.hide()
        showAttachmentMenu = false
        showStickers = true
    }
    var showPhotoCapture by remember(peerUuid){mutableStateOf(false)}
    var showVideoCapture by remember(peerUuid){mutableStateOf(false)}
    val callManager = remember { org.msgat.core.call.CallManager.getInstance() }
    val callState by callManager.callState.collectAsState()
    val isCallMinimized by callManager.isMinimized.collectAsState()
    val captureAllowed = callState == org.msgat.core.call.CallState.IDLE || callState == org.msgat.core.call.CallState.ENDED
    LaunchedEffect(captureAllowed) {
        if (!captureAllowed) showPhotoCapture = false
    }
    var showVoiceRecording by remember(peerUuid){mutableStateOf(false)}
    var showContactPicker by remember(peerUuid) { mutableStateOf(false) }
    var openedContactCard by remember(peerUuid) { mutableStateOf<MessageUIModel?>(null) }
    var showLocationPicker by remember(peerUuid) { mutableStateOf(false) }
    var openedLocation by remember(peerUuid) { mutableStateOf<MessageUIModel?>(null) }
    val liveShares by activeLiveLocations.collectAsState()
    var sendOptionsDialog by remember(peerUuid) { mutableStateOf<Boolean?>(null) }
    var showSendQueue by remember(peerUuid){mutableStateOf(false)}
    var showSendMenu by remember(peerUuid) { mutableStateOf(false) }
    var showHistoryMenu by remember(peerUuid){mutableStateOf(false)}
    var showMuteOptions by remember(peerUuid) { mutableStateOf(false) }
    val muted by produceState(muteUntil > System.currentTimeMillis(), muteUntil) {
        var remaining = muteUntil - System.currentTimeMillis()
        value = remaining > 0
        while (remaining > 0 && muteUntil != org.msgat.data.db.ConversationIndexStore.PERMANENT_MUTE) {
            delay(minOf(remaining, 60_000))
            remaining = muteUntil - System.currentTimeMillis()
            value = remaining > 0
        }
    }
    var showExpiration by remember(peerUuid) { mutableStateOf(false) }
    var showSharedMedia by remember(peerUuid) { mutableStateOf(false) }
    var sharedMediaStart by remember(peerUuid) { mutableStateOf(SharedMediaCategory.MEDIA) }
    var showContactProfile by remember(peerUuid) { mutableStateOf(false) }
    var showPinnedMessages by remember(peerUuid){mutableStateOf(false)}
    var mediaConfirmation by remember(peerUuid){mutableStateOf<org.msgat.ui.components.MediaConfirmation?>(null)}
    var replyDraft by remember(peerUuid) { mutableStateOf<ComposerReply?>(null) }
    val replyingToMessage = replyDraft?.message
    val replyId = replyingToMessage?.id
    var liveReplySource by remember(peerUuid, replyId) { mutableStateOf<MessageUIModel?>(null) }
    val observeReply by rememberUpdatedState(observeReplySource)
    LaunchedEffect(peerUuid, replyId) {
        val id = replyId ?: return@LaunchedEffect
        observeReply(id).collect { latest ->
            val active = replyDraft?.takeIf { it.message.id == id } ?: return@collect
            if (latest == null || latest.isRevoked ||
                active.selection != null && latest.content != active.message.content) {
                replyDraft = null
                liveReplySource = null
            } else liveReplySource = latest
        }
    }
    var quoteTarget by remember(peerUuid) { mutableStateOf<MessageUIModel?>(null) }
    var draftReady by remember { mutableStateOf(false) }
    var selectedIds by remember(peerUuid) {mutableStateOf<Set<String>>(emptySet())}
    var selectionRevision by remember(peerUuid) {mutableLongStateOf(0)}
    var savingSelection by remember(peerUuid) {mutableStateOf(false)}
    var copyingSelection by remember(peerUuid) { mutableStateOf(false) }
    val hiddenSelectionText = uiString(R.string.ui_hidden_message_text)
    val selectionCopied = uiString(R.string.ui_message_copied)
    val selectionCopyFailed = uiString(R.string.ui_message_copy_failed)
    val selectionHasNoText = uiString(R.string.ui_selected_messages_no_text)
    var checkingLocalDeletion by remember(peerUuid) { mutableStateOf(false) }
    var pendingLocalDeletion by remember(peerUuid) { mutableStateOf<Pair<List<String>, Boolean>?>(null) }
    val localDeletionUnavailable = uiString(R.string.ui_local_delete_unavailable)
    val revokeSelectionUnavailable = uiString(R.string.ui_revoke_selected_unavailable)
    val isSelecting=selectedIds.isNotEmpty()
    val selectionCanBeKept = isSelecting &&
        messages.itemSnapshotList.items.none { it.id in selectedIds && it.expiresAt != null }
    androidx.activity.compose.BackHandler(enabled=isSelecting) {
        if(!savingSelection){selectedIds=emptySet();selectionRevision++}
    }
    fun toggleSelection(id: String) {
        if(savingSelection) return
        if(id !in selectedIds && selectedIds.size>=100) {
            android.widget.Toast.makeText(context,R.string.ui_message_selection_limit,android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        selectedIds=if(id in selectedIds)selectedIds-id else selectedIds+id
        selectionRevision++
    }
    fun forwardMessages(ids: List<String>) {
        val version=selectionRevision;val previous=selectedIds
        onForwardMessages(ids.toList()) {if(selectionRevision==version && selectedIds==previous){selectedIds=emptySet();selectionRevision++}}
    }
    fun copySelectedMessages() {
        if (savingSelection || copyingSelection || selectedIds.isEmpty()) return
        val ids = selectedIds.toList()
        val version = selectionRevision
        copyingSelection = true
        coroutineScope.launch {
            try {
                val text = onLoadSelectedText(ids).map { visibleMessageCopy(it, hiddenSelectionText) }
                    .filter { it.isNotEmpty() }.joinToString("\n\n")
                if (version != selectionRevision) return@launch
                if (text.isNotEmpty()) putMessageOnClipboard(context, text)
                android.widget.Toast.makeText(context, if (text.isEmpty()) selectionHasNoText else selectionCopied,
                    android.widget.Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == selectionRevision) {
                    android.widget.Toast.makeText(context, selectionCopyFailed, android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                copyingSelection = false
            }
        }
    }
    val saveMessageSuccess = uiString(R.string.ui_shared_media_saved)
    val saveMessageFailure = uiString(R.string.ui_shared_media_save_failed)
    fun saveMessages(ids: List<String>) {
        if(savingSelection) return
        savingSelection=true
        val version=selectionRevision;val previous=selectedIds
        onSaveMessages(ids.toList(),{
            savingSelection=false
            if(selectionRevision==version && selectedIds==previous){selectedIds=emptySet();selectionRevision++}
            android.widget.Toast.makeText(context,saveMessageSuccess,android.widget.Toast.LENGTH_SHORT).show()
        },{
            savingSelection=false
            android.widget.Toast.makeText(context,saveMessageFailure,android.widget.Toast.LENGTH_SHORT).show()
        }, { savingSelection = false }, {})
    }
    fun requestLocalDeletion(ids: List<String>) {
        if (savingSelection || checkingLocalDeletion || pendingLocalDeletion != null || ids.isEmpty()) return
        val targets = ids.toSet()
        val version = selectionRevision
        val fromSelection = selectedIds == targets
        checkingLocalDeletion = true
        coroutineScope.launch {
            try {
                val canRevoke = org.msgat.MsgATApp.instance.nettyClient.secureMessaging.deletionCanRevoke(peerUuid, targets)
                if (selectionRevision != version || savingSelection) return@launch
                if (canRevoke == null) {
                    android.widget.Toast.makeText(context, localDeletionUnavailable, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    pendingLocalDeletion = targets.toList() to (fromSelection && canRevoke)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (selectionRevision == version)
                    android.widget.Toast.makeText(context, localDeletionUnavailable, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                checkingLocalDeletion = false
            }
        }
    }
    pendingLocalDeletion?.let { (ids, canRevoke) ->
        var revoke by remember(peerUuid, ids) { mutableStateOf(false) }
        val revokeForBoth = canRevoke && revoke
        AlertDialog(
            onDismissRequest = { pendingLocalDeletion = null },
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
            title = { Text(uiString(if (canRevoke) R.string.ui_delete_messages_title else R.string.ui_local_delete_title)) },
            text = {
                Column {
                    Text(uiString(if (revokeForBoth) R.string.ui_revoke_selected_message else R.string.ui_local_delete_message))
                    if (canRevoke) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = revoke, role = Role.Checkbox, onValueChange = { revoke = it }),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = revoke, onCheckedChange = null)
                            Spacer(Modifier.width(10.dp))
                            Text(uiString(R.string.ui_revoke_selected))
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { pendingLocalDeletion = null }) { Text(uiString(R.string.ui_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    pendingLocalDeletion = null
                    savingSelection = true
                    coroutineScope.launch {
                        try {
                            val messaging = org.msgat.MsgATApp.instance.nettyClient.secureMessaging
                            val committed = if (revokeForBoth) messaging.revokeSentMessages(peerUuid, ids)
                                else messaging.deleteLocalMessages(peerUuid, ids)
                            if (committed) {
                                val remaining = selectedIds - ids.toSet()
                                if (remaining != selectedIds) { selectedIds = remaining; selectionRevision++ }
                            } else if (revokeForBoth) {
                                android.widget.Toast.makeText(context, revokeSelectionUnavailable, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            val unavailable = if (revokeForBoth) revokeSelectionUnavailable else localDeletionUnavailable
                            android.widget.Toast.makeText(context, unavailable, android.widget.Toast.LENGTH_SHORT).show()
                        } finally { savingSelection = false }
                    }
                }) {
                    Text(uiString(if (revokeForBoth) R.string.ui_revoke else R.string.ui_local_delete),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
    LaunchedEffect(messages.itemSnapshotList.items.filter {it.isRevoked}.map {it.id}) {
        val removed=messages.itemSnapshotList.items.filter {it.isRevoked}.map {it.id}.toSet()
        val filtered=selectedIds-removed
        if(filtered!=selectedIds){selectedIds=filtered;selectionRevision++}
        if (openedContactCard?.id?.let { it in removed } == true) openedContactCard = null
        if (openedLocation?.id?.let { it in removed } == true) openedLocation = null
    }

    if (showContactPicker) org.msgat.ui.components.ContactCardPicker(
        recipient = alias,
        local = org.msgat.data.db.Conversation(myUuid, localContactName),
        initial = sendOptions,
        load = onLoadCardContacts,
        send = { account, name, options ->
            val text = composerState.text.toString()
            val reply = replyDraft
            val sent = onSendContactCard(account, name, text, reply?.message?.id, reply?.selection, options)
            if (sent && composerState.text.toString() == text && replyDraft == reply) replyDraft = null
            sent
        },
        onDismiss = { showContactPicker = false }
    )
    openedContactCard?.let { card ->
        card.contactAccount?.let { account ->
            org.msgat.ui.components.ContactCardDetails(card.content, account, myUuid,
                isCurrent = { onReadCardAccount(card.id) == account }, onOpenChat = { peer ->
                    onOpenContactChat(peer) { openedContactCard = card }
                }) {
                openedContactCard = null
            }
        }
    }
    if (showLocationPicker) org.msgat.ui.components.LocationSendDialog(alias, sendOptions, send = { point, label, options ->
        val text = composerState.text.toString()
        val reply = replyDraft
        val sent = onSendLocation(point, label, text, reply?.message?.id, reply?.selection, options)
        if (sent && composerState.text.toString() == text && replyDraft == reply) replyDraft = null
        sent
    }, shareLive = { point, duration, label, silent ->
        val text = composerState.text.toString()
        val reply = replyDraft
        val sent = onStartLiveLocation(point, duration, label, text, reply?.message?.id, reply?.selection, silent)
        if (sent && composerState.text.toString() == text && replyDraft == reply) replyDraft = null
        sent
    }, onDismiss = { showLocationPicker = false })
    openedLocation?.let { location ->
        key(location.id) {
            if (location.ext == "live_location") {
                val snapshot by org.msgat.ui.components.observeLiveLocation(peerUuid, location.id, liveLocationChanges) {
                    onReadLiveLocation(location.id)
                }
                org.msgat.ui.components.LiveLocationDetailsDialog(location.content, snapshot, location.isMine,
                    liveShares[peerUuid]?.messageId == location.id, load = { onReadLiveLocation(location.id) },
                    stop = { onStopLiveLocation(location.id) }) { openedLocation = null }
            } else {
                org.msgat.ui.components.LocationDetailsDialog(location.content, { onReadLocation(location.id) }) { openedLocation = null }
            }
        }
    }

    val attachmentLimit = uiString(R.string.ui_attachment_limit)
    fun reviewAttachments(
        uris: List<android.net.Uri>,
        asFile: Boolean = false,
        returnToPanel: Boolean = false,
        initialCaption: String = "",
        onComplete: () -> Unit = {}
    ): Boolean {
        val sources = uris.distinct()
        if (!draftReady || isSelecting || mediaConfirmation != null || sources.isEmpty()) {
            onComplete()
            return false
        }
        if (sources.size > 10) {
            android.widget.Toast.makeText(context, attachmentLimit, android.widget.Toast.LENGTH_SHORT).show()
            onComplete()
            return false
        }
        val text = composerState.text.toString()
        val reply = replyDraft
        showAttachmentMenu = false
        returnToAttachmentPanel = returnToPanel
        mediaConfirmation = org.msgat.ui.components.MediaConfirmation(sources, alias, sendOptions, asFile, { items, choice, finished, result, started ->
            onSelectMedia(items, text, reply?.message?.id, reply?.selection, choice, {
                if (composerState.text.toString() == text && replyDraft == reply) replyDraft = null
            }, {}, finished, result, started)
        }, onComplete, initialCaption)
        return true
    }
    mediaConfirmation?.let { selection ->
        org.msgat.ui.components.MediaSendDialog(
            selection,
            preparations = preparations,
            onCancelPreparation = onCancelPreparation,
            onDismiss = {
                mediaConfirmation = null
                selection.close()
                if (returnToAttachmentPanel) showAttachmentMenu = true
                returnToAttachmentPanel = false
            },
            onQueued = {
                mediaConfirmation = null
                returnToAttachmentPanel = false
            }
        )
    }
    val sendSelectedMedia by rememberUpdatedState<(android.net.Uri, () -> Unit) -> Unit> { uri, finished ->
        reviewAttachments(listOf(uri), onComplete = finished)
    }
    val reviewCapture by rememberUpdatedState<(android.net.Uri, () -> Unit) -> Unit> { uri, finished ->
        if (!reviewAttachments(attachmentPanel.gallerySelection + uri, returnToPanel = true, onComplete = finished)) {
            showAttachmentMenu = true
        }
    }
    if (showPhotoCapture && captureAllowed) org.msgat.ui.components.PhotoCaptureDialog(
        onDismiss = { showPhotoCapture = false; showAttachmentMenu = true },
        onCaptured = { source -> showPhotoCapture = false; reviewCapture(source.uri, source::close) }
    )
    if (showVideoCapture) org.msgat.ui.components.VideoRecordingDialog(
        onDismiss = { showVideoCapture = false; showAttachmentMenu = true },
        onRecorded = { source -> showVideoCapture = false; reviewCapture(source.uri, source::close) }
    )
    LaunchedEffect(peerUuid, initialDraft?.value?.token) {
        val restored = initialDraft ?: return@LaunchedEffect
        composerState.setTextAndPlaceCursorAtEnd(restored.value.text)
        replyDraft = restored.reply?.let { ComposerReply(it, restored.value.replyQuote, restored.quotePreview) }
        draftReady = true
        snapshotFlow { composerState.text.toString() to replyDraft }.collect { (text, reply) ->
            onDraftChanged(text, reply?.message?.id, reply?.selection)
        }
    }
    DisposableEffect(peerUuid, initialDraft?.value?.token) {
        onDispose { if (draftReady) onDraftChanged(composerState.text.toString(), replyDraft?.message?.id, replyDraft?.selection) }
    }
    var highlightedMessageId by remember(peerUuid) { mutableStateOf<String?>(null) }
    var highlightedQuote by remember(peerUuid) { mutableStateOf<String?>(null) }
    var pendingSendCancellation by remember(peerUuid) { mutableStateOf<String?>(null) }
    var checkingSendCancellation by remember(peerUuid) { mutableStateOf(false) }
    var retryingSends by remember(peerUuid) { mutableStateOf<Set<String>>(emptySet()) }
    val cancelUnavailable = uiString(R.string.ui_send_cancel_unavailable)
    val retryUnavailable = uiString(R.string.ui_send_retry_unavailable)
    val attachmentMessaging = org.msgat.MsgATApp.instance.nettyClient.secureMessaging
    pendingSendCancellation?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingSendCancellation = null },
            properties = androidx.compose.ui.window.DialogProperties(
                securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn
            ),
            title = { Text(uiString(R.string.ui_send_cancel_title)) },
            text = { Text(uiString(R.string.ui_send_cancel_message)) },
            dismissButton = {
                TextButton(onClick = { pendingSendCancellation = null }) {
                    Text(uiString(R.string.ui_send_cancel_keep))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSendCancellation = null
                    coroutineScope.launch {
                        try {
                            if (!attachmentMessaging.manageSend(id, "cancel", expectedPeer = peerUuid)) {
                                android.widget.Toast.makeText(context, cancelUnavailable, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(context, cancelUnavailable, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text(uiString(R.string.ui_send_cancel_confirm), color = MaterialTheme.colorScheme.error) }
            }
        )
    }
    fun requestSendCancellation(id: String) {
        if (checkingSendCancellation || pendingSendCancellation != null) return
        checkingSendCancellation = true
        coroutineScope.launch {
            try {
                val row = attachmentMessaging.sendQueue(peer = peerUuid, messageId = id).firstOrNull()
                if (row?.canCancel == true) pendingSendCancellation = id
                else android.widget.Toast.makeText(context, cancelUnavailable, android.widget.Toast.LENGTH_SHORT).show()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, cancelUnavailable, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                checkingSendCancellation = false
            }
        }
    }
    fun retrySend(id: String) {
        if (id in retryingSends) return
        retryingSends = retryingSends + id
        coroutineScope.launch {
            try {
                if (!attachmentMessaging.manageSend(id, "retry", expectedPeer = peerUuid)) {
                    android.widget.Toast.makeText(context, retryUnavailable, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, retryUnavailable, android.widget.Toast.LENGTH_SHORT).show()
            } finally { retryingSends = retryingSends - id }
        }
    }
    val quoteJumpState = remember(peerUuid) { mutableStateOf<Job?>(null) }
    var quoteJumpJob by quoteJumpState
    DisposableEffect(quoteJumpState) { onDispose { quoteJumpState.value?.cancel() } }
    val quoteReturnAnchors = remember(peerUuid) { mutableStateListOf<QuoteReturnAnchor>() }
    var retryLatestJump by remember(peerUuid) { mutableStateOf(false) }
    var editingMsg by remember(peerUuid) { mutableStateOf<MessageUIModel?>(null) }
    var showKeyboardForEdit by remember(peerUuid) { mutableStateOf(true) }
    val editState = remember(peerUuid) { androidx.compose.foundation.text.input.TextFieldState() }
    val editing = editingMsg != null
    var savingEdit by remember(peerUuid) { mutableStateOf(false) }
    var pendingEditExit by remember(peerUuid) { mutableStateOf<EditExitRequest?>(null) }
    var restoreEditFocus by remember(peerUuid) { mutableStateOf(false) }
    var restoreDraftFocus by remember(peerUuid) { mutableStateOf(false) }
    val editor = if (editing) editState else composerState
    val focusEditor = remember { androidx.compose.ui.focus.FocusRequester() }
    val editText = editState.text.toString()
    fun editableText(message: MessageUIModel): String =
        if (message.ext == "attachment") message.attachment?.caption.orEmpty() else message.content
    val hasEditChanges = editingMsg?.let { editText != editableText(it) } == true
    val editReady = editingMsg?.let {
        (it.ext == "attachment" || editText.isNotBlank()) && hasEditChanges && !savingEdit
    } == true
    val editSaveError = uiString(R.string.ui_edit_failed)
    val sendFailure = uiString(R.string.ui_send_failed)
    val formatError = uiString(R.string.ui_format_unavailable)
    fun cancelEdit(restoreFocus: Boolean = false) {
        if (editingMsg == null) return
        pendingEditExit = null
        restoreEditFocus = false
        restoreDraftFocus = restoreFocus
        editingMsg = null
    }
    fun requestEditExit(onKeep: () -> Unit = {}, action: () -> Unit) {
        if (savingEdit || pendingEditExit != null) return
        if (hasEditChanges) {
            keyboard?.hide()
            pendingEditExit = EditExitRequest(action, onKeep)
        } else action()
    }
    LaunchedEffect(isSelecting) { if (isSelecting) cancelEdit() }
    androidx.activity.compose.BackHandler(enabled = editing) {
        requestEditExit { cancelEdit(restoreFocus = true) }
    }
    fun keepEditing() {
        val request = pendingEditExit
        pendingEditExit = null
        request?.keep?.invoke()
        restoreEditFocus = true
    }
    pendingEditExit?.let { request ->
        AlertDialog(
            onDismissRequest = ::keepEditing,
            properties = androidx.compose.ui.window.DialogProperties(
                securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn
            ),
            title = { Text(uiString(R.string.ui_discard_message_edit)) },
            confirmButton = {
                TextButton(onClick = { pendingEditExit = null; request.proceed() }) {
                    Text(uiString(R.string.ui_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = ::keepEditing) { Text(uiString(R.string.ui_keep_editing)) }
            }
        )
    }
    LaunchedEffect(editingMsg?.id) {
        if (editing) {
            withFrameNanos { }
            focusEditor.requestFocus()
            if (showKeyboardForEdit) keyboard?.show()
        }
    }
    fun saveEdit() {
        val target = editingMsg ?: return
        if (!editReady) return
        savingEdit = true
        coroutineScope.launch {
            val saved = try { onEditMessage(target.id, editText) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { savingEdit = false }
            if (editingMsg?.id != target.id) return@launch
            if (saved) cancelEdit(restoreFocus = true)
            else android.widget.Toast.makeText(context, editSaveError, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val listState = rememberLazyListState()
    var selectedMedia by remember(peerUuid) { mutableStateOf<MessageUIModel?>(null) }
    var previewOrigin by remember(peerUuid) { mutableStateOf<MediaPhotoOrigin?>(null) }
    val photoFrames = remember(peerUuid) { mutableStateMapOf<String, MediaPhotoFrame>() }
    var previewAlbum by remember(peerUuid) { mutableStateOf<List<MessageUIModel>>(emptyList()) }
    var previewOpenedAsAlbum by remember(peerUuid) { mutableStateOf(false) }
    var previewNeighbors by remember(peerUuid) { mutableStateOf<VisualMediaNeighbors?>(null) }
    var previewReload by remember(peerUuid) { mutableIntStateOf(0) }
    var previewRefreshFailed by remember(peerUuid) { mutableStateOf(false) }
    var previewMove by remember(peerUuid) { mutableStateOf<Job?>(null) }
    var attachmentOpen by remember(peerUuid) { mutableStateOf<Job?>(null) }
    var checkingAttachment by remember(peerUuid) { mutableStateOf(false) }
    val previewStore = org.msgat.MsgATApp.instance.nettyClient.objects
    val attachmentExport = rememberAttachmentExport(peerUuid)
    val saveAttachment: (String, org.msgat.core.media.AttachmentMetadata) -> Unit = { id, media ->
        attachmentExport.save(media) { previewStore.canExportMessage(peerUuid, id, media.reference) }
    }
    val attachmentUnavailable = uiString(R.string.ui_file_details_unavailable)
    val attachmentDownloadQueued = uiString(R.string.ui_download_queued)
    suspend fun verifyPreviewAttachment(id: String, reference: String): Boolean {
        return try {
            val complete = previewStore.hasCompleteCiphertext(reference)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!complete && org.msgat.MsgATApp.instance.nettyClient.secureMessaging
                    .replaceAttachmentCache(peerUuid, id, reference)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                android.widget.Toast.makeText(context, attachmentDownloadQueued, android.widget.Toast.LENGTH_SHORT).show()
            }
            complete
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, attachmentUnavailable, android.widget.Toast.LENGTH_SHORT).show()
            false
        }
    }
    fun openReceivedAttachment(id: String, reference: String, isMine: Boolean,
                               onPending: ((org.msgat.core.media.AttachmentMetadata) -> Unit)? = null,
                               open: () -> Unit) {
        attachmentOpen?.cancel()
        checkingAttachment = !isMine
        if (isMine) {
            open()
            return
        }
        attachmentOpen = coroutineScope.launch {
            try {
                if (verifyPreviewAttachment(id, reference)) open()
                else if (onPending != null) {
                    val current = previewStore.sharedMediaItem(peerUuid, id)?.attachment
                    if (current != null && current.reference == reference && !current.available) onPending(current)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, attachmentUnavailable, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                if (attachmentOpen === kotlin.coroutines.coroutineContext[Job]) checkingAttachment = false
            }
        }
    }
    val inlineAudio = remember(peerUuid) { InlineAudioPlayback(context.applicationContext, previewStore) }
    DisposableEffect(inlineAudio) { onDispose { inlineAudio.close() } }
    LaunchedEffect(inlineAudio, inlineAudio.activeId) {
        val id = inlineAudio.activeId ?: return@LaunchedEffect
        merge(previewStore.observeSharedMediaChanges(), previewStore.availability.map { Unit }).conflate().collect {
            val available = try {
                previewStore.sharedMediaItem(peerUuid, id)?.attachment?.available == true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!available && inlineAudio.activeId == id) inlineAudio.close()
        }
    }
    LaunchedEffect(inlineAudio, inlineAudio.playing, inlineAudio.loading) {
        while (isActive && inlineAudio.playing && !inlineAudio.loading) {
            inlineAudio.update()
            delay(200)
        }
    }

    fun closeMedia() {
        previewMove?.cancel()
        previewNeighbors = null
        previewRefreshFailed = false
        previewOrigin = null
        selectedMedia = null
        previewAlbum = emptyList()
        previewOpenedAsAlbum = false
    }

    fun moveMedia(target: SharedMediaItem) {
        val origin = selectedMedia?.id ?: return
        previewMove?.cancel()
        previewMove = coroutineScope.launch {
            try {
                var current = previewStore.sharedMediaItem(peerUuid, target.id)
                if (current?.attachment?.available != true ||
                    current.attachment.reference != target.attachment.reference) return@launch
                if (!current.isMine && !verifyPreviewAttachment(current.id, current.attachment.reference)) {
                    val refreshed = previewStore.sharedMediaItem(peerUuid, target.id)
                    if (refreshed == null || refreshed.attachment.available ||
                        refreshed.attachment.reference != target.attachment.reference) return@launch
                    current = refreshed
                }
                if (selectedMedia?.id != origin) return@launch
                previewNeighbors = null
                previewOrigin = null
                selectedMedia = MessageUIModel(current.id, current.isMine, current.attachment.reference,
                    "attachment", current.timestamp, attachment = current.attachment)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, attachmentUnavailable, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(peerUuid, selectedMedia?.id, selectedMedia?.content, previewOpenedAsAlbum, previewReload) {
        val shown = selectedMedia ?: return@LaunchedEffect
        val attachment = shown.attachment ?: return@LaunchedEffect
        if (previewOpenedAsAlbum || attachment.isSticker || (!attachment.isImage && !attachment.isVideo)) return@LaunchedEffect
        fun stillShowing() = selectedMedia?.id == shown.id && selectedMedia?.content == shown.content
        previewRefreshFailed = false
        merge(flowOf(Unit), previewStore.observeSharedMediaChanges(), previewStore.availability.map { Unit }).conflate().collect {
            try {
                val current = previewStore.sharedMediaItem(peerUuid, shown.id)
                if (!stillShowing()) return@collect
                if (current == null || current.attachment.reference != shown.content) closeMedia()
                else {
                    val adjacent = previewStore.visualMediaNeighbors(peerUuid, SharedMediaCursor(current.timestamp, current.id))
                    if (stillShowing()) {
                        selectedMedia = selectedMedia?.copy(attachment = current.attachment)
                        previewNeighbors = adjacent
                        previewRefreshFailed = false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (stillShowing()) previewRefreshFailed = true
            }
            delay(120)
        }
    }

    fun removePreviewItems(ids: Set<String>) {
        if (selectedMedia?.id?.let(ids::contains) == true) {
            closeMedia()
        } else {
            previewAlbum = previewAlbum.filterNot { it.id in ids }
        }
    }
    LaunchedEffect(peerUuid, removedMessages) {
        removedMessages.collect { ids ->
            removePreviewItems(ids)
            if (inlineAudio.activeId?.let(ids::contains) == true) inlineAudio.close()
            if (openedContactCard?.id?.let(ids::contains) == true) openedContactCard = null
            if (openedLocation?.id?.let(ids::contains) == true) openedLocation = null
            if (replyDraft?.message?.id?.let(ids::contains) == true) replyDraft = null
            if (quoteTarget?.id?.let(ids::contains) == true) quoteTarget = null
            if (editingMsg?.id?.let(ids::contains) == true) cancelEdit()
            if (selectedIds.any { it in ids }) { selectedIds = selectedIds - ids; selectionRevision++ }
        }
    }

    val revokedPreviewIds = messages.itemSnapshotList.items.filter { it.isRevoked }.map { it.id }.toSet()
    val replySource = replyDraft?.takeIf { it.selection != null }?.let { quote ->
        messages.itemSnapshotList.items.firstOrNull { it.id == quote.message.id }
    }
    val quoteSource = quoteTarget?.let { target -> messages.itemSnapshotList.items.firstOrNull { it.id == target.id } }
    LaunchedEffect(peerUuid, revokedPreviewIds, replySource?.content, quoteSource?.content, quoteTarget?.id) {
        if (revokedPreviewIds.isNotEmpty()) removePreviewItems(revokedPreviewIds)
        if (editingMsg?.id?.let(revokedPreviewIds::contains) == true) cancelEdit()
        if (replyDraft?.message?.id?.let(revokedPreviewIds::contains) == true) replyDraft = null
        if (replySource != null && replyDraft?.selection != null && replyDraft?.message?.id == replySource.id &&
            replyDraft?.message?.content != replySource.content) replyDraft = null
        if (quoteTarget?.id?.let(revokedPreviewIds::contains) == true) quoteTarget = null
        if (quoteSource != null && quoteTarget?.id == quoteSource.id && quoteTarget?.content != quoteSource.content) quoteTarget = null
    }
    val callCoversChat = callState != org.msgat.core.call.CallState.IDLE && !isCallMinimized
    val isChatVisible = !workspacePanelCoveringChat && selectedMedia == null && mediaConfirmation==null && !showSharedMedia && !showContactProfile && !showPinnedMessages &&
        !showPhotoCapture && !showVideoCapture && !showVoiceRecording && !showContactPicker && openedContactCard == null &&
        !showLocationPicker && openedLocation == null && !showExpiration && sendOptionsDialog == null && !showSendQueue &&
        pendingEditExit == null && pendingSendCancellation == null && !attachmentExport.busy &&
        !callCoversChat
    LaunchedEffect(callCoversChat) {
        if (callCoversChat) {
            pendingEditExit = null
            restoreEditFocus = false
            keyboard?.hide()
            panelFocus.clearFocus(force = true)
            showAttachmentMenu = false
        }
    }
    LaunchedEffect(isChatVisible, isSelecting) {
        if (!isChatVisible || isSelecting) attachmentOpen?.cancel()
    }
    val voice = remember(peerUuid) {
        org.msgat.ui.components.VoiceCaptureSession(context, org.msgat.MsgATApp.instance.nettyClient.objects.captures)
    }
    val voiceLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(voiceLifecycle, voice, inlineAudio) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { voice.interrupt(); inlineAudio.close() }
        }
        voiceLifecycle.lifecycle.addObserver(observer)
        onDispose { voiceLifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(voice) { onDispose { voice.dispose() } }
    LaunchedEffect(captureAllowed, voice) { if (!captureAllowed) voice.interrupt() }
    LaunchedEffect(captureAllowed, voice.active, isChatVisible, inlineAudio) {
        if (!captureAllowed || voice.active || !isChatVisible) inlineAudio.close()
    }
    var startVoiceAfterPermission by remember(peerUuid) { mutableStateOf(false) }
    val voiceUnavailableText = uiString(R.string.ui_record_unavailable)
    fun canStartVoice(): Boolean {
        val state = callManager.callState.value
        val microphoneAvailable = state == org.msgat.core.call.CallState.IDLE || state == org.msgat.core.call.CallState.ENDED
        return microphoneAvailable && voiceLifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
            !voice.active && !isSelecting && draftReady
    }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && startVoiceAfterPermission && showVoiceRecording && canStartVoice()) voice.start(hold = false)
        if (!allowed) android.widget.Toast.makeText(context, voiceUnavailableText, android.widget.Toast.LENGTH_SHORT).show()
        startVoiceAfterPermission = false
    }
    fun beginVoice(hold: Boolean): Boolean {
        if (!canStartVoice()) return false
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVoiceAfterPermission = !hold
            voicePermission.launch(android.Manifest.permission.RECORD_AUDIO)
            return false
        }
        voice.start(hold)
        return true
    }
    val finishVoice: (org.msgat.core.media.RecordingMediaSource) -> Unit = { source ->
        showVoiceRecording = false
        sendSelectedMedia(source.uri) { source.close() }
    }
    if (showVoiceRecording) org.msgat.ui.components.VoiceRecordingDialog(
        voice, captureAllowed, onStart = { beginVoice(hold = false) },
        onDismiss = { showVoiceRecording = false }, onRecorded = finishVoice
    )
    val inlineVoice = voice.active && !showVoiceRecording
    val attachmentVisibility = remember(peerUuid) { MutableTransitionState(false) }
    val attachmentsOpen = showAttachmentMenu && pickerSection == null && isChatVisible && !editing && !isSelecting && !inlineVoice
    SideEffect { attachmentVisibility.targetState = attachmentsOpen }
    val attachmentCovering = attachmentsOpen || attachmentVisibility.currentState || !attachmentVisibility.isIdle
    val stickerVisibility = remember(peerUuid) { MutableTransitionState(false) }
    val stickersOpen = showStickers && isChatVisible && !editing && !isSelecting && !inlineVoice
    SideEffect { stickerVisibility.targetState = stickersOpen }
    val stickersCovering = stickersOpen || stickerVisibility.currentState || !stickerVisibility.isIdle
    LaunchedEffect(editing, isSelecting, inlineVoice, isChatVisible) {
        if (editing || isSelecting || inlineVoice || !isChatVisible) showStickers = false
    }
    androidx.activity.compose.BackHandler(enabled = stickersOpen) {
        if (!stickerBusy) showStickers = false
    }
    val canReply = draftReady && !editing && !isSelecting && !inlineVoice && !attachmentCovering && !stickersCovering && isChatVisible
    LaunchedEffect(isChatVisible, isSelecting, editing, inlineVoice, inputText.isBlank(), textSendPending) {
        if (!isChatVisible || isSelecting || editing || inlineVoice || inputText.isBlank() || textSendPending) {
            showSendMenu = false
        }
    }
    fun submitComposer(fromKeyboard: Boolean = false, options: org.msgat.core.network.SendOptions? = null) {
        val confirmingSchedule = sendOptionsDialog == true && options?.sendAt != null && !callCoversChat
        if (!draftReady || (!isChatVisible && !confirmingSchedule) || isSelecting || inlineVoice || stickerBusy ||
            ((fromKeyboard || options != null) && editor.composition != null)) return
        if (editing) {
            saveEdit()
            return
        }
        if (textSendPending) return
        val text = composerState.text.toString()
        if (text.isBlank()) return
        showStickers = false
        val replyId = replyDraft?.message?.id
        val replyQuote = replyDraft?.selection
        onSendMessage(text, replyId, replyQuote, options, {
            if (composerState.text.toString() == text && replyDraft?.message?.id == replyId &&
                replyDraft?.selection == replyQuote && composerState.composition == null) {
                composerState.clearText()
                replyDraft = null
            } else {
                onDraftChanged(composerState.text.toString(), replyDraft?.message?.id, replyDraft?.selection)
            }
        }) {
            android.widget.Toast.makeText(context, sendFailure, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    sendOptionsDialog?.let { scheduleDraft ->
        org.msgat.ui.components.SendOptionsDialog(
            sendOptions, startScheduled = scheduleDraft,
            onDismiss = { sendOptionsDialog = null },
            onConfirm = {
                if (scheduleDraft) submitComposer(options = it)
                else onSetSendOptions(it)
                sendOptionsDialog = null
            }
        )
    }
    val canReplyNow by rememberUpdatedState(canReply)
    var replyFocusRequest by remember(peerUuid) { mutableIntStateOf(0) }
    val quoteUnavailable = uiString(R.string.ui_quote_selection_unavailable)
    androidx.activity.compose.BackHandler(enabled = quoteTarget != null) { quoteTarget = null }
    LaunchedEffect(canReply) { if (!canReply) quoteTarget = null }
    LaunchedEffect(replyFocusRequest, canReply) {
        if (replyFocusRequest == 0) return@LaunchedEffect
        if (canReply && replyingToMessage != null) {
            withFrameNanos { }
            focusEditor.requestFocus()
            keyboard?.show()
        }
        replyFocusRequest = 0
    }
    val attachmentButtonAngle by animateFloatAsState(if (attachmentsOpen) 45f else 0f,
        animationSpec = tween(durationMillis = if (attachmentsOpen) 400 else 180, delayMillis = if (attachmentsOpen) 20 else 0),
        label = "Attachment button")
    androidx.activity.compose.BackHandler(enabled = inlineVoice) { voice.discard() }
    val latestThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
    val isAtBottom by remember(messages, listState, latestThreshold) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= latestThreshold &&
                (messages.loadState.prepend as? androidx.paging.LoadState.NotLoading)?.endOfPaginationReached == true
        }
    }

    val albumGroups by remember(messages) {
        derivedStateOf {
            messages.itemSnapshotList.items
                .filter { !it.isRevoked && it.attachment?.album != null }
                .groupBy { it.isMine to it.attachment!!.album!!.id }
                .values
                .filter { it.size >= 2 }
                .flatMap { group -> group.map { it.id to group } }
                .toMap()
        }
    }
    val displayedAlbums = albumGroups
    val currentAlbums by rememberUpdatedState(displayedAlbums)
    val unreadRowId = unreadTimelineAnchor?.let { id -> displayedAlbums[id]?.firstOrNull()?.id ?: id }
    val timelineZone = java.time.ZoneId.systemDefault()
    val timelineLayout = remember(messages.itemSnapshotList, displayedAlbums, timelineZone, unreadRowId) {
        val rows = messages.itemSnapshotList.items.asReversed()
            .filter { row -> displayedAlbums[row.id]?.first()?.id?.let { it == row.id } ?: true }
        val days = rows.map { row -> java.time.Instant.ofEpochMilli(row.timestamp).atZone(timelineZone).toLocalDate() }
        fun joins(index: Int): Boolean =
            index > 0 && index < rows.size && days[index] == days[index - 1] &&
                rows[index].id != unreadRowId && canJoinTimelineMessages(rows[index - 1], rows[index])
        rows.mapIndexed { index, row ->
            row.id to TimelineLayout(
                days[index].takeIf { index == 0 || it != days[index - 1] },
                joins(index),
                joins(index + 1)
            )
        }.toMap()
    }
    val visibleMessageFrames = remember(peerUuid) { mutableStateMapOf<String, Rect>() }
    var viewportFrame by remember(peerUuid) { mutableStateOf<Rect?>(null) }
    var restoringHistory by remember(peerUuid) { mutableStateOf(initialMessageId != null || historyPosition != null) }
    var positioningHistory by remember(peerUuid) { mutableStateOf(false) }
    var historyJumpRevision by remember(peerUuid) { mutableIntStateOf(0) }
    val unavailableMessage = uiString(R.string.ui_history_message_unavailable)
    val unavailableQuotedMessage = uiString(R.string.ui_quoted_message_unavailable)
    val historyLoadError = uiString(R.string.ui_history_load_failed)
    val historyLookupLabel = uiString(R.string.ui_loading)
    fun currentHistoryAnchor(excluding: String? = null): ChatHistoryPosition? {
        val layout = listState.layoutInfo
        for (row in layout.visibleItemsInfo.sortedBy { it.offset }) {
            if (row.size <= 0 || row.offset + row.size <= layout.viewportStartOffset ||
                row.offset >= layout.viewportEndOffset || row.index !in 0 until messages.itemCount) continue
            val message = messages.peek(row.index) ?: continue
            if (row.key != message.id || message.id == excluding || message.isRevoked) continue
            // In reverse layout, scrolling by this distance restores the same row edge.
            return ChatHistoryPosition(message.id, -row.offset)
        }
        return null
    }
    val saveHistoryPosition by rememberUpdatedState<() -> Unit> {
        if (!restoringHistory && !positioningHistory) {
            val position = currentHistoryAnchor()
            if (position != null) onSaveHistoryPosition(if (isAtBottom) null else position)
        }
    }
    DisposableEffect(peerUuid) { onDispose { saveHistoryPosition() } }

    fun jumpToMessage(targetId: String, rememberOrigin: Boolean = false, returnOffset: Int? = null,
                      restoringPosition: Boolean = false, quotePreview: String? = null,
                      returningTo: QuoteReturnAnchor? = null, openingQuote: Boolean = false) {
        retryLatestJump = false
        val origin = if (rememberOrigin) currentHistoryAnchor(excluding = targetId)
            ?.let { QuoteReturnAnchor(it.messageId, it.offset) } else null
        val revision = ++historyJumpRevision
        quoteJumpJob?.cancel()
        highlightedMessageId = null
        highlightedQuote = null
        positioningHistory = true
        quoteJumpJob = coroutineScope.launch {
            try {
                if (!hasHistoryMessage(targetId, openingQuote)) {
                    if (returningTo != null) quoteReturnAnchors.remove(returningTo)
                    if (restoringPosition) {
                        onSaveHistoryPosition(null)
                        onRevealMessage(null)
                    } else {
                        if (restoringHistory) {
                            if (unreadTimelineAnchor == targetId) unreadTimelineAnchor = null
                            onRevealMessage(null)
                        }
                        val text = if (openingQuote) unavailableQuotedMessage else unavailableMessage
                        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (messages.itemSnapshotList.items.none { it.id == targetId }) onRevealMessage(targetId)
                val destination = withTimeoutOrNull(10_000) {
                    snapshotFlow {
                        val anchor = currentAlbums[targetId]?.firstOrNull()?.id ?: targetId
                        val index = messages.itemSnapshotList.items.indexOfFirst { it.id == anchor }
                        index to (messages.loadState.refresh is androidx.paging.LoadState.Error)
                    }.first { (index, failed) -> index >= 0 || failed }
                }
                val targetIndex = destination?.first ?: -1
                if (targetIndex < 0) {
                    android.widget.Toast.makeText(context, historyLoadError, android.widget.Toast.LENGTH_SHORT).show()
                }
                else {
                    if (restoringPosition) listState.scrollToItem(targetIndex, scrollOffset = returnOffset ?: 0)
                    else listState.animateScrollToItem(targetIndex, scrollOffset = returnOffset ?: 0)
                    if (returnOffset == null) {
                        withTimeoutOrNull(500) {
                            snapshotFlow {
                                val tile = visibleMessageFrames[targetId]
                                val viewport = viewportFrame
                                if (tile != null && viewport != null) {
                                    (viewport.top + viewport.bottom - tile.top - tile.bottom) / 2f
                                } else null
                            }.first { it != null }
                        }?.let { listState.animateScrollBy(it) }
                        highlightedMessageId = targetId
                        highlightedQuote = quotePreview
                    }
                    if (restoringHistory) restoringHistory = false
                    if (returningTo != null) quoteReturnAnchors.remove(returningTo)
                    if (origin != null && quoteReturnAnchors.lastOrNull() != origin) {
                        if (quoteReturnAnchors.size == 16) quoteReturnAnchors.removeAt(0)
                        quoteReturnAnchors.add(origin)
                    }
                    positioningHistory = false
                    if (returnOffset == null) {
                        delay(1500)
                        if (highlightedMessageId == targetId) {
                            highlightedMessageId = null
                            highlightedQuote = null
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, historyLoadError, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                if (historyJumpRevision == revision) positioningHistory = false
            }
        }
    }
    var timelineDatePicker by remember(peerUuid) { mutableStateOf<android.app.DatePickerDialog?>(null) }
    val historyDateTitle = uiString(R.string.ui_jump_to_date)
    val historyJumpLabel = uiString(R.string.ui_jump_to_message)
    val historyCancelLabel = uiString(R.string.ui_cancel)
    val noHistoryMessages = uiString(R.string.ui_no_messages_on_date)
    val noUnreadMessages = uiString(R.string.ui_no_unread_messages)
    DisposableEffect(peerUuid) {
        onDispose { timelineDatePicker?.dismiss() }
    }
    fun openDatePicker(date: java.time.LocalDate) {
        if (timelineDatePicker?.isShowing == true) return
        val picker = android.app.DatePickerDialog(context, null, date.year, date.monthValue - 1, date.dayOfMonth)
        picker.setTitle(historyDateTitle)
        picker.setButton(android.content.DialogInterface.BUTTON_POSITIVE, historyJumpLabel, null as android.content.DialogInterface.OnClickListener?)
        picker.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, historyCancelLabel, null as android.content.DialogInterface.OnClickListener?)
        timelineDatePicker = picker
        var lookup: kotlinx.coroutines.Job? = null
        picker.setOnDismissListener {
            lookup?.cancel()
            if (timelineDatePicker === picker) timelineDatePicker = null
        }
        picker.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        picker.show()
        val jump = picker.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        jump.setOnClickListener {
            picker.datePicker.clearFocus()
            val selected = picker.datePicker
            val day = java.time.LocalDate.of(selected.year, selected.month + 1, selected.dayOfMonth)
            val start = day.atStartOfDay(timelineZone).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(timelineZone).toInstant().toEpochMilli()
            jump.isEnabled = false
            jump.text = historyLookupLabel
            lookup = coroutineScope.launch {
                try {
                    val id = onMessageAtDate(start, end)
                    if (timelineDatePicker !== picker || !picker.isShowing) return@launch
                    if (id != null) {
                        lookup = null
                        picker.dismiss()
                        jumpToMessage(id, rememberOrigin = true)
                    } else {
                        android.widget.Toast.makeText(context, noHistoryMessages, android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (picker.isShowing) android.widget.Toast.makeText(context, historyLoadError, android.widget.Toast.LENGTH_SHORT).show()
                } finally {
                    if (picker.isShowing) {
                        jump.text = historyJumpLabel
                        jump.isEnabled = true
                    }
                }
            }
        }
    }
    var floatingDay by remember(peerUuid) { mutableStateOf<java.time.LocalDate?>(null) }
    var showFloatingDay by remember(peerUuid) { mutableStateOf(false) }
    fun latestPagePosition(): Pair<Boolean, String?> {
        val states = messages.loadState
        val atNewestPage = states.refresh is androidx.paging.LoadState.NotLoading &&
            (states.prepend as? androidx.paging.LoadState.NotLoading)?.endOfPaginationReached == true
        return atNewestPage to messages.itemSnapshotList.items.firstOrNull()?.id
    }
    fun jumpToLatest() {
        if (positioningHistory) return
        val revision = ++historyJumpRevision
        quoteJumpJob?.cancel()
        positioningHistory = true
        retryLatestJump = false
        quoteJumpJob = coroutineScope.launch {
            try {
                val (atNewestPage, loadedNewest) = latestPagePosition()
                if (!atNewestPage || onNewestMessage() != loadedNewest) {
                    onRevealMessage(null)
                    val reachedLatest = withTimeoutOrNull(10_000) {
                        snapshotFlow { latestPagePosition() }.first { (ready, firstId) ->
                            ready && onNewestMessage() == firstId
                        }
                    }
                    if (reachedLatest == null) {
                        retryLatestJump = true
                        return@launch
                    }
                }
                if (messages.itemCount > 0) listState.animateScrollToItem(0)
                quoteReturnAnchors.clear()
                highlightedMessageId = null
                highlightedQuote = null
                showFloatingDay = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retryLatestJump = true
            } finally {
                if (historyJumpRevision == revision) positioningHistory = false
            }
        }
    }
    LaunchedEffect(peerUuid, messages, listState, timelineZone) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val first = layout.visibleItemsInfo.asSequence()
                .filter { it.size > 0 && it.index < messages.itemCount && it.offset + it.size > layout.viewportStartOffset }
                .minByOrNull { it.offset }
            val date = first?.let { messages.peek(it.index)?.timestamp }
                ?.let { java.time.Instant.ofEpochMilli(it).atZone(timelineZone).toLocalDate() }
            listState.isScrollInProgress to date
        }.collectLatest { (scrolling, date) ->
            if (date == null) showFloatingDay = false
            else if (scrolling) {
                floatingDay = date
                showFloatingDay = true
            } else if (showFloatingDay) {
                delay(1600)
                showFloatingDay = false
            }
        }
    }
    LaunchedEffect(peerUuid, initialMessageId, historyPosition, searchRevealRevision) {
        if (initialMessageId != null) restoringHistory = true
        try {
            if (initialMessageId != null) {
                jumpToMessage(initialMessageId)
                quoteJumpJob?.join()
            } else if (historyPosition != null) {
                jumpToMessage(historyPosition.messageId, returnOffset = historyPosition.offset, restoringPosition = true)
                quoteJumpJob?.join()
            }
        } finally {
            restoringHistory = false
        }
    }
    fun beginEdit(target: MessageUIModel, showKeyboard: Boolean = true) {
        if (!draftReady || inlineVoice || editingMsg?.id == target.id || target.isRevoked) return
        requestEditExit {
            showAttachmentMenu = false
            showKeyboardForEdit = showKeyboard
            editState.edit {
                replace(0, length, editableText(target))
                selection = androidx.compose.ui.text.TextRange(length)
            }
            editingMsg = target
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val sendPendingNow by rememberUpdatedState(textSendPending)
    val historyAccessNow by rememberUpdatedState(isChatVisible && !attachmentCovering && !stickersCovering &&
        timelineDatePicker == null && !showSendMenu && quoteTarget == null)
    LaunchedEffect(incomingShare, historyAccessNow, draftReady, editing, isSelecting, inlineVoice, isResumed) {
        val share = incomingShare ?: return@LaunchedEffect
        if (!historyAccessNow || !draftReady || editing || isSelecting || inlineVoice || !isResumed) return@LaunchedEffect
        if (share.files.isNotEmpty()) {
            if (reviewAttachments(share.files, asFile = share.asFile, initialCaption = share.text.orEmpty()))
                onIncomingShareHandled(share)
        } else if (share.text != null) {
            val existing = composerState.text.toString()
            val combined = if (existing.isEmpty()) share.text else "$existing\n${share.text}"
            if (combined.toByteArray(Charsets.UTF_8).size > org.msgat.ui.components.MAX_SHARED_TEXT_BYTES) {
                android.widget.Toast.makeText(context, R.string.ui_share_text_too_long, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                composerState.setTextAndPlaceCursorAtEnd(combined)
            }
            onIncomingShareHandled(share)
        }
    }
    var acceptedNavigation by remember(peerUuid) { mutableStateOf<ChatMessageNavigation?>(null) }
    LaunchedEffect(messageNavigation, historyAccessNow, savingEdit, acceptedNavigation) {
        val request = messageNavigation ?: return@LaunchedEffect
        if (!historyAccessNow || savingEdit) return@LaunchedEffect
        val sameConversation = request.peer == peerUuid
        if (sameConversation || acceptedNavigation == request) {
            val opened = request.open()
            acceptedNavigation = null
            if (opened && sameConversation) request.messageId?.let { jumpToMessage(it) }
        } else {
            requestEditExit(onKeep = request.cancel) { acceptedNavigation = request }
        }
    }
    val composerHistoryLookup = remember(peerUuid) { mutableStateOf<Job?>(null) }
    DisposableEffect(composerHistoryLookup) { onDispose { composerHistoryLookup.value?.cancel() } }
    fun clearComposerReply(restoreFocus: Boolean = false) {
        replyDraft = null
        highlightedMessageId = null
        highlightedQuote = null
        if (restoreFocus) restoreDraftFocus = true
    }
    fun composerHistoryKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!historyAccessNow || restoringHistory || positioningHistory ||
            event.isAltPressed || event.isMetaPressed || event.isShiftPressed) return false
        if (!event.isCtrlPressed && event.key == Key.Escape) {
            if (composerHistoryLookup.value?.isActive == true) {
                composerHistoryLookup.value?.cancel()
                return true
            }
            if (editingMsg != null) {
                if (event.nativeKeyEvent.repeatCount == 0) requestEditExit { cancelEdit(restoreFocus = true) }
                return true
            }
            if (replyDraft != null) {
                clearComposerReply()
                return true
            }
            return false
        }
        if (!canReplyNow || sendPendingNow) return false
        val currentReply = replyDraft
        val editLatest = event.key == Key.DirectionUp && !event.isCtrlPressed &&
            composerState.text.isEmpty() && currentReply == null
        val moveReply = event.isCtrlPressed && (event.key == Key.DirectionUp ||
            event.key == Key.DirectionDown && currentReply != null)
        if (!editLatest && !moveReply) return false
        if (event.nativeKeyEvent.repeatCount > 0 || composerHistoryLookup.value?.isActive == true) return true
        val newer = event.key == Key.DirectionDown
        composerHistoryLookup.value = coroutineScope.launch {
            try {
                val target = if (editLatest) onLatestEditable() else onAdjacentReply(currentReply?.message, newer)
                if (!historyAccessNow || !canReplyNow || !composerFocused || sendPendingNow ||
                    restoringHistory || positioningHistory || composerState.composition != null ||
                    replyDraft != currentReply || (editLatest && composerState.text.isNotEmpty())) return@launch
                if (target != null) {
                    if (editLatest) beginEdit(target, showKeyboard = false)
                    else replyDraft = ComposerReply(target)
                    jumpToMessage(target.id, rememberOrigin = true)
                } else if (moveReply && newer) {
                    clearComposerReply()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (historyAccessNow && composerFocused) {
                    android.widget.Toast.makeText(context, historyLoadError, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }
    if(showPinnedMessages)org.msgat.ui.components.PinnedMessagesDialog(peerUuid,onDismiss={showPinnedMessages=false},onOpen={id->showPinnedMessages=false;jumpToMessage(id, rememberOrigin = true)})

    if (showContactProfile) {
        org.msgat.ui.components.ContactsDialog(
            org.msgat.MsgATApp.instance.nettyClient,
            onDismiss = { showContactProfile = false },
            onOpen = {},
            focusedPeer = peerUuid,
            onSharedMedia = { sharedMediaStart = SharedMediaCategory.MEDIA; showSharedMedia = true },
            onVoiceCall = onStartCall.takeIf { captureAllowed },
            onVideoCall = onStartVideoCall.takeIf { captureAllowed && callManager.supportsVideo },
            notificationMuteUntil = muteUntil,
            notificationsMuted = muted,
            onChangeNotifications = onChangeConversationMute
        )
    }
    if (showSendQueue) org.msgat.ui.components.SendQueueDialog(
        org.msgat.MsgATApp.instance.nettyClient.secureMessaging, org.msgat.MsgATApp.instance.nettyClient.connectionState, peerUuid,
        onOpenChat = { peer, id ->
            if (peer != peerUuid || id == null || !hasHistoryMessage(id, false)) false
            else {
                showSendQueue = false
                jumpToMessage(id, rememberOrigin = true)
                true
            }
        }
    ) { showSendQueue = false }
    if (showSharedMedia) {
        SharedMediaDialog(peerUuid, attachmentProgress, preparations, onCancelPreparation, initialCategory = sharedMediaStart,
            onDismiss = { showSharedMedia = false }, onForward = { ids ->
            showSharedMedia = false
            forwardMessages(ids)
        }, onSave = onSaveMessages, onJump = {
            showSharedMedia = false
            jumpToMessage(it, rememberOrigin = true)
        })
    }

    val hostView=androidx.compose.ui.platform.LocalView.current
    var hasWindowFocus by remember(hostView){mutableStateOf(hostView.hasWindowFocus())}
    DisposableEffect(hostView) {
        val observer=android.view.ViewTreeObserver.OnWindowFocusChangeListener{hasWindowFocus=it}
        hostView.viewTreeObserver.addOnWindowFocusChangeListener(observer)
        onDispose {if(hostView.viewTreeObserver.isAlive)hostView.viewTreeObserver.removeOnWindowFocusChangeListener(observer)}
    }
    val chatActivity by rememberUpdatedState(onChatActivity)
    var previousActivityText by remember(peerUuid){mutableStateOf(inputText)}
    DisposableEffect(peerUuid){val leave=onChatActivity;onDispose{leave(false,false)}}
    LaunchedEffect(restoreDraftFocus, canReply, isResumed, hasWindowFocus, editing, isSelecting, inlineVoice) {
        if (!restoreDraftFocus) return@LaunchedEffect
        if (editing || isSelecting || inlineVoice) {
            restoreDraftFocus = false
            return@LaunchedEffect
        }
        if (!canReply || !isResumed || !hasWindowFocus) return@LaunchedEffect
        withFrameNanos { }
        focusEditor.requestFocus()
        keyboard?.show()
        restoreDraftFocus = false
    }
    LaunchedEffect(restoreEditFocus, editing, isChatVisible, isResumed, hasWindowFocus) {
        if (!restoreEditFocus || !editing || !isChatVisible || !isResumed || !hasWindowFocus) return@LaunchedEffect
        withFrameNanos { }
        focusEditor.requestFocus()
        keyboard?.show()
        restoreEditFocus = false
    }
    val canReportChatActivity = isResumed && isChatVisible && hasWindowFocus && !attachmentCovering &&
        !stickersCovering && !showSendMenu && quoteTarget == null && timelineDatePicker == null
    LaunchedEffect(peerUuid, canReportChatActivity, composerFocused) { chatActivity(canReportChatActivity, false) }
    LaunchedEffect(inputText){
        val changed=previousActivityText!=inputText;previousActivityText=inputText
        if (changed && draftReady) chatActivity(canReportChatActivity, canReportChatActivity && composerFocused && inputText.isNotBlank())
    }
    val readVisibilityThreshold=with(androidx.compose.ui.platform.LocalDensity.current){48.dp.toPx()}
    val markVisibleRead by rememberUpdatedState(onMarkAsRead)
    val receiptSource by rememberUpdatedState(observeMessageReceipts)
    var outgoingReceipts by remember(peerUuid) { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(peerUuid, messages, listState, isResumed, isChatVisible) {
        if (!isResumed || !isChatVisible) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.flatMap { item ->
                val message = if (item.index in 0 until messages.itemCount) messages.peek(item.index) else null
                if (message == null || message.id != item.key || !message.isMine || item.size <= 0) {
                    return@flatMap emptyList()
                }
                val album = currentAlbums[message.id]
                (album ?: listOf(message)).mapNotNull { member -> member.id.takeUnless { member.isRevoked } }
            }.distinct().take(100)
        }.collectLatest { ids ->
            val visibleIds = ids.toSet()
            outgoingReceipts = outgoingReceipts.filterKeys(visibleIds::contains)
            if (ids.isNotEmpty()) receiptSource(ids).collect { committed ->
                // A retry can replace a failed state before the paging snapshot is refreshed.
                outgoingReceipts = committed
            }
        }
    }
    val canReadTimeline = isChatVisible && !attachmentCovering && !stickersCovering && !showSendMenu &&
        !restoringHistory && !positioningHistory && quoteTarget == null && timelineDatePicker == null
    val animateLiveArrivals by rememberUpdatedState(canReadTimeline && isResumed && hasWindowFocus && !isSelecting)
    var recentArrivals by remember(peerUuid) { mutableStateOf(emptyMap<String, Long>()) }
    val readTimelineNow by rememberUpdatedState(canReadTimeline)
    val locallyRead by rememberUpdatedState(recentReadIds)
    LaunchedEffect(peerUuid, messages, isResumed, canReadTimeline, hasWindowFocus, readVisibilityThreshold) {
        if (!isResumed || !canReadTimeline || !hasWindowFocus) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val viewport = viewportFrame ?: return@snapshotFlow emptyList<String>()
            layout.visibleItemsInfo.flatMap { item ->
                val message = if (item.index in 0 until messages.itemCount) messages.peek(item.index) else null
                if (message == null || message.id != item.key || item.size <= 0) return@flatMap emptyList()
                val album = currentAlbums[message.id]
                if (album != null && album.first().id != message.id) return@flatMap emptyList()
                (album ?: listOf(message)).mapNotNull { member ->
                    val frame = visibleMessageFrames[member.id] ?: return@mapNotNull null
                    val overlap = minOf(frame.bottom, viewport.bottom) - maxOf(frame.top, viewport.top)
                    member.takeIf {
                        !it.isMine && !it.isRead && !it.isRevoked && it.id !in locallyRead && frame.height > 0 &&
                            frame.right > viewport.left && frame.left < viewport.right &&
                            overlap >= minOf(frame.height, readVisibilityThreshold)
                    }?.id
                }
            }.distinct().take(100)
        }.collectLatest { ids ->
            if (ids.isEmpty()) return@collectLatest
            fun canSubmit() = readTimelineNow && hostView.hasWindowFocus() &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            delay(150)
            if (canSubmit() && !markVisibleRead(ids)) {
                delay(500)
                if (canSubmit()) markVisibleRead(ids)
            }
        }
    }

    LaunchedEffect(peerUuid, messages, listState, latestThreshold, restoringHistory, positioningHistory) {
        if (restoringHistory || positioningHistory) return@LaunchedEffect
        var previous: TimelineFollowSample? = null
        var following = false
        snapshotFlow {
            TimelineFollowSample(
                newest = messages.itemSnapshotList.items.firstOrNull()?.let { it.id to it.timestamp },
                viewport = listState.layoutInfo.viewportSize,
                latestReady = (messages.loadState.prepend as? androidx.paging.LoadState.NotLoading)
                    ?.endOfPaginationReached == true,
                atBottom = isAtBottom,
                scrolling = listState.isScrollInProgress
            )
        }.collect { sample ->
            if (sample.scrolling) following = false
            if (!sample.latestReady || sample.viewport == IntSize.Zero) return@collect
            val old = previous
            val newest = sample.newest
            val oldNewest = old?.newest
            val appended = oldNewest != null && newest != null &&
                newest.first != oldNewest.first && newest.second >= oldNewest.second
            val resized = old != null && old.viewport != sample.viewport
            val follow = following && newest != null && (appended || resized) &&
                !restoringHistory && !positioningHistory
            if (follow && appended && animateLiveArrivals && !sample.scrolling) {
                val prefix = messages.itemSnapshotList.items.take(13)
                val boundary = prefix.indexOfFirst { it.id == oldNewest?.first }
                if (boundary in 1..12 && prefix.firstOrNull()?.id == newest?.first) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val arrivals = recentArrivals.filterValues { now - it < 250 } +
                        prefix.take(boundary).filterNot { it.isRevoked }.associate { it.id to now }
                    recentArrivals = arrivals.entries.toList().takeLast(24).associate { it.toPair() }
                }
            }
            if (follow) listState.requestScrollToItem(0)
            following = !sample.scrolling && (sample.atBottom || follow)
            previous = sample
        }
    }

    LaunchedEffect(inputText) { if (inputText.isNotEmpty()) showAttachmentMenu = false }
    val pasteFailed = uiString(R.string.ui_paste_image_failed)
    val pasteLimit = uiString(R.string.ui_attachment_limit)
    val handleImagePaste by rememberUpdatedState<(TransferableContent) -> TransferableContent?> { content ->
        if (!canReply || !composerFocused || !isResumed || !hasWindowFocus) content
        else {
            val images = linkedSetOf<android.net.Uri>()
            val remaining = content.consume { item ->
                val uri = item.uri
                if (uri == null || (uri.scheme != "content" && uri.scheme != "file")) return@consume false
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                val image = mime?.startsWith("image/") == true ||
                    (mime == null && content.clipEntry.clipData.itemCount == 1 && content.hasMediaType(MediaType.Image))
                if (image) images.add(uri)
                image
            }
            if (images.size > 10) {
                android.widget.Toast.makeText(context, pasteLimit, android.widget.Toast.LENGTH_SHORT).show()
            } else if (images.isNotEmpty()) {
                val lease = ImagePasteLease(content)
                val sendImmediately = content.source != TransferableContent.Source.DragAndDrop && images.all { uri ->
                    uri.scheme == "content" && context.checkUriPermission(
                        uri, android.os.Process.myPid(), android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (sendImmediately) {
                    val text = composerState.text.toString()
                    val reply = replyDraft
                    val choice = org.msgat.core.network.MediaSendChoice(options = sendOptions, asAlbum = images.size > 1)
                    onSelectMedia(images.toList(), text, reply?.message?.id, reply?.selection, choice, {
                        if (composerState.text.toString() == text && replyDraft == reply) replyDraft = null
                    }, {
                        android.widget.Toast.makeText(context, pasteFailed, android.widget.Toast.LENGTH_SHORT).show()
                    }, lease::release, {}, {})
                } else reviewAttachments(images.toList(), onComplete = lease::release)
            }
            remaining
        }
    }
    val receiveImages = remember(peerUuid, myUuid) { ReceiveContentListener { handleImagePaste(it) } }

    var pickerResult by remember(peerUuid) { mutableStateOf<List<android.net.Uri>?>(null) }
    LaunchedEffect(pickerResult, draftReady, editing, isSelecting, inlineVoice, isChatVisible) {
        val selected = pickerResult ?: return@LaunchedEffect
        val section = pickerSection ?: return@LaunchedEffect
        if (!draftReady) return@LaunchedEffect
        pickerResult = null
        pickerSection = null
        if (!isChatVisible || editing || isSelecting || inlineVoice) return@LaunchedEffect
        attachmentPanel.section = section
        val previous = when (section) {
            AttachmentSection.Gallery -> attachmentPanel.gallerySelection
            AttachmentSection.Audio -> attachmentPanel.audioSelection
            AttachmentSection.Files -> emptyList()
        }
        if (selected.isEmpty() || !reviewAttachments(previous + selected,
                asFile = section == AttachmentSection.Files, returnToPanel = true)) {
            showAttachmentMenu = true
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris -> if (pickerSection == AttachmentSection.Gallery) pickerResult = uris }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (pickerSection == AttachmentSection.Files || pickerSection == AttachmentSection.Audio) pickerResult = uris }

    if (showExpiration) MessageExpirationDialog(onLoadExpiration, onSetExpiration) { showExpiration = false }

    val messagingColors = LocalMessagingColors.current
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = messagingColors.timeline,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = messagingColors.chrome,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(enabled = !savingSelection && !savingEdit, onClick = {
                        if (isSelecting) {
                            selectedIds = emptySet()
                            selectionRevision++
                        } else if (editing) {
                            requestEditExit { cancelEdit(); onBack() }
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(if(isSelecting)Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription=if(isSelecting) uiString(R.string.ui_cancel_message_selection) else "Back",
                            tint=MaterialTheme.colorScheme.onSurface)
                    }
                },
                title = {
                    if (isSelecting) {
                        Text(uiString(R.string.ui_message_selection_count, selectedIds.size), maxLines = 1)
                    } else {
                        ChatHeaderTitle(alias, avatarPath, syncState, peerActivity, peerVerified, muted) {
                            showContactProfile = true
                        }
                    }
                },
                actions = {
                    if(isSelecting) {
                        IconButton(enabled = !savingSelection && !copyingSelection, onClick = ::copySelectedMessages) {
                            Icon(Icons.Default.ContentCopy, uiString(R.string.ui_copy_selected_messages))
                        }
                        IconButton(enabled=!savingSelection && selectionCanBeKept,onClick={forwardMessages(selectedIds.toList())}) {
                            Icon(Icons.AutoMirrored.Filled.Send, uiString(R.string.ui_forward_selected_messages))
                        }
                        IconButton(enabled=!savingSelection && selectionCanBeKept,onClick={saveMessages(selectedIds.toList())}) {
                            Icon(Icons.Default.Bookmark, uiString(R.string.ui_save_selected_messages))
                        }
                        IconButton(enabled = !savingSelection, onClick = { requestLocalDeletion(selectedIds.toList()) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = uiString(R.string.ui_delete_messages))
                        }
                    } else {
                        IconButton(enabled = captureAllowed, onClick = onStartCall) {
                            Icon(Icons.Default.Call, contentDescription = uiString(R.string.ui_voice_call))
                        }
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Default.Search, contentDescription = uiString(R.string.ui_search_messages))
                        }
                        Box {
                            IconButton(onClick = { showHistoryMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = uiString(R.string.ui_chat_actions))
                            }
                            DropdownMenu(expanded = showHistoryMenu, onDismissRequest = {
                                showHistoryMenu = false
                                showMuteOptions = false
                            }) {
                                if (showMuteOptions) {
                                    DropdownMenuItem(text = { Text(uiString(R.string.ui_conversation_notifications)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                                        onClick = { showMuteOptions = false })
                                    HorizontalDivider()
                                    org.msgat.ui.components.ConversationMuteMenuItems(muteUntil) { action, until ->
                                        showHistoryMenu = false
                                        showMuteOptions = false
                                        onChangeConversationMute(action, until)
                                    }
                                } else {
                                    if (callManager.supportsVideo) {
                                        DropdownMenuItem(text = { Text(uiString(R.string.ui_video_call)) }, enabled = captureAllowed, onClick = {
                                            showHistoryMenu = false
                                            onStartVideoCall()
                                        })
                                    }
                                    DropdownMenuItem(text = { Text(uiString(R.string.ui_shared_media)) }, onClick = {
                                        showHistoryMenu = false
                                        sharedMediaStart = SharedMediaCategory.MEDIA
                                        showSharedMedia = true
                                    })
                                    DropdownMenuItem(text = { Text(uiString(R.string.ui_conversation_notifications)) },
                                        leadingIcon = { Icon(if (muted) Icons.Default.NotificationsOff else Icons.Default.Notifications, null) },
                                        onClick = { showMuteOptions = true })
                                    DropdownMenuItem(text={Text(uiString(R.string.ui_pinned_messages))},onClick={showHistoryMenu=false;showPinnedMessages=true})
                                    DropdownMenuItem(
                                        text = { Text(uiString(R.string.ui_send_options)) },
                                        enabled = draftReady && !editing && !inlineVoice && !stickerBusy,
                                        onClick = {
                                            showHistoryMenu = false
                                            showStickers = false
                                            showAttachmentMenu = false
                                            sendOptionsDialog = false
                                        }
                                    )
                                    DropdownMenuItem(text = { Text(uiString(R.string.ui_sending_queue)) }, onClick = {
                                        showHistoryMenu = false
                                        showSendQueue = true
                                    })
                                    DropdownMenuItem(text={Text(uiString(R.string.ui_message_retention))},onClick={
                                        showHistoryMenu=false;showExpiration=true
                                    })
                                    DropdownMenuItem(text = { Text(uiString(R.string.ui_jump_to_date)) }, onClick = {
                                        showHistoryMenu = false
                                        openDatePicker(java.time.LocalDate.now(timelineZone))
                                    })
                                    DropdownMenuItem(text={Text(uiString(R.string.ui_first_unread_message))},onClick={
                                        showHistoryMenu=false
                                        coroutineScope.launch {
                                            val id = try {
                                                onFirstUnread()
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                android.widget.Toast.makeText(context, historyLoadError, android.widget.Toast.LENGTH_SHORT).show()
                                                return@launch
                                            }
                                            if (id != null) {
                                                unreadTimelineAnchor = id
                                                jumpToMessage(id, rememberOrigin = true)
                                            } else {
                                                android.widget.Toast.makeText(context, noUnreadMessages, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    })
                                }
                            }
                        }

                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().background(messagingColors.chrome).navigationBarsPadding()
                .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ChatColumnWidth).fillMaxWidth()) {
                HorizontalDivider(thickness = Dp.Hairline, color = messagingColors.divider)
                if (!editing) LinkPreviewComposer(previewEnabled, previewChoices, previewTarget, webPreview, previewAboveText,
                    previewCompactImage, previewLoading, onPreviewEnabled, onSelectPreview, onTogglePreviewPlacement,
                    onTogglePreviewImageSize, onRemovePreview, onRetryPreview)
                val accessory = editingMsg?.let { ComposerAccessory(it, editing = true) }
                    ?: replyingToMessage?.let { original ->
                        val current = liveReplySource?.takeIf { it.id == original.id }
                            ?: messages.itemSnapshotList.items.firstOrNull { it.id == original.id }
                            ?: original
                        if (current.isRevoked) null else ComposerAccessory(current, editing = false, quote = replyDraft?.preview)
                    }
                androidx.compose.animation.AnimatedContent(
                    targetState = accessory,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.BottomStart,
                    transitionSpec = {
                        val curve = CubicBezierEasing(0.1992f, 0.0106f, 0.2792f, 0.9103f)
                        when {
                            initialState == null -> androidx.compose.animation.slideInVertically(tween(250, easing = curve)) { it }
                                .togetherWith(androidx.compose.animation.ExitTransition.None)
                            targetState == null -> androidx.compose.animation.EnterTransition.None.togetherWith(
                                androidx.compose.animation.slideOutVertically(tween(250, easing = curve)) { it })
                            else -> androidx.compose.animation.fadeIn(tween(120))
                                .togetherWith(androidx.compose.animation.fadeOut(tween(120)))
                        }.using(androidx.compose.animation.SizeTransform(clip = true) { _, _ -> tween(250, easing = curve) })
                    },
                    contentKey = { item -> item?.let { it.message.id to it.editing } },
                    label = "Composer accessory"
                ) {
                    if (it != null) ComposerAccessoryBar(
                        accessory = it,
                        alias = alias,
                        active = it == accessory,
                        canDismiss = !it.editing || !savingEdit,
                        onJump = { jumpToMessage(it.message.id, rememberOrigin = true, quotePreview = it.quote, openingQuote = !it.editing) },
                        onDismiss = {
                            if (it.editing) requestEditExit { cancelEdit(restoreFocus = true) }
                            else clearComposerReply(restoreFocus = true)
                        }
                    ) else Spacer(Modifier.fillMaxWidth())
                }

                if (preparations.isNotEmpty() && !stickersCovering)
                    org.msgat.ui.components.PreparationRows(preparations, onCancelPreparation)
                if(!editing && sendOptions!=org.msgat.core.network.SendOptions())Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(org.msgat.ui.components.sendOptionsLabel(sendOptions),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    IconButton(onClick={onSetSendOptions(org.msgat.core.network.SendOptions())}){
                        Icon(Icons.Default.Close, contentDescription = uiString(R.string.ui_clear_send_options))
                    }
                }
                if (inlineVoice) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        org.msgat.ui.components.VoiceCaptureControls(voice, captureAllowed, finishVoice,
                            onDiscard = { voice.discard() },
                            onRecordAgain = { voice.discard {
                                showVoiceRecording = true
                                beginVoice(hold = false)
                            } })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    val textScale = LocalAppearanceSettings.current
                    val colors = MaterialTheme.colorScheme
                    val composerTextStyle = MaterialTheme.typography.bodyLarge.let { base ->
                        base.copy(color = colors.onSurface, fontSize = textScale.apply(base.fontSize),
                            lineHeight = textScale.apply(base.lineHeight))
                    }
                    val formatEnabled = draftReady && !savingEdit && !isSelecting && !inlineVoice && !stickerBusy
                    val hasStickerButton = !editing && !isSelecting && !inlineVoice
                    val composerShape = RoundedCornerShape(22.dp)
                    Row(
                        modifier = Modifier.weight(1f).clip(composerShape).background(messagingColors.timeline)
                            .border(1.dp, if (composerFocused) colors.primary else messagingColors.divider, composerShape),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (hasStickerButton) {
                            IconButton(enabled = draftReady && !stickerBusy && pickerSection == null,
                                onClick = {
                                    if (showStickers) {
                                        showStickers = false
                                        focusEditor.requestFocus()
                                        keyboard?.show()
                                    } else openStickers()
                                }
                            ) {
                                Icon(if (showStickers) Icons.Default.Keyboard else Icons.Default.EmojiEmotions,
                                    uiString(if (showStickers) R.string.ui_sticker_keyboard else R.string.ui_stickers))
                            }
                        }
                        BasicTextField(
                            state = editor,
                            enabled = draftReady && !isSelecting && !inlineVoice && !stickerBusy && !callCoversChat,
                            readOnly = savingEdit,
                            modifier = Modifier.weight(1f)
                                .focusRequester(focusEditor)
                                .onFocusChanged {
                                    composerFocused = it.isFocused
                                    if (it.isFocused && !stickerBusy) showStickers = false
                                }
                                .then(if (editing) Modifier else Modifier.contentReceiver(receiveImages))
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown || editor.composition != null) return@onPreviewKeyEvent false
                                    if (composerHistoryKey(event)) return@onPreviewKeyEvent true
                                    if ((event.key != Key.Enter && event.key != Key.NumPadEnter) ||
                                        event.isShiftPressed || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return@onPreviewKeyEvent false
                                    if (event.nativeKeyEvent.repeatCount == 0) submitComposer(fromKeyboard = true)
                                    true
                                }
                                .composerFormatKeys(editor, formatEnabled) {
                                    android.widget.Toast.makeText(context, formatError, android.widget.Toast.LENGTH_SHORT).show()
                                },
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                            textStyle = composerTextStyle,
                            cursorBrush = SolidColor(colors.primary),
                            decorator = { innerTextField ->
                                Box(
                                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                                        .padding(start = if (hasStickerButton) 0.dp else 14.dp,
                                            end = 2.dp, top = 12.dp, bottom = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (editor.text.isEmpty()) {
                                        Text(uiString(R.string.ui_message_placeholder), color = colors.onSurfaceVariant,
                                            style = composerTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        ComposerFormatButton(editor, formatEnabled)
                        if (!editing) {
                            IconButton(
                                enabled = draftReady && pickerSection == null && !isSelecting && !inlineVoice && !stickerBusy,
                                onClick = {
                                    if (!showAttachmentMenu) attachmentPanel = AttachmentPanelState()
                                    showStickers = false
                                    showAttachmentMenu = !showAttachmentMenu
                                    if (showAttachmentMenu) keyboard?.hide()
                                }
                            ) {
                                Icon(Icons.Default.Add, modifier = Modifier.graphicsLayer { rotationZ = attachmentButtonAngle },
                                    contentDescription = uiString(if (showAttachmentMenu) R.string.ui_close_attachments else R.string.ui_add_attachments))
                            }
                        }
                    }
                    val action = when {
                        editing && savingEdit -> ComposerAction.SAVING
                        editing -> ComposerAction.SAVE
                        inlineVoice -> ComposerAction.VOICE
                        textSendPending -> ComposerAction.SENDING
                        inputText.isNotBlank() -> ComposerAction.SEND
                        else -> ComposerAction.VOICE
                    }
                    val voiceEnabled = draftReady && !isSelecting && !stickerBusy
                    val sendEnabled = voiceEnabled && !inlineVoice && !textSendPending
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        ComposerActionGlyph(action, sendEnabled, voice.held)
                        when (action) {
                            ComposerAction.SEND -> {
                                val label = uiString(R.string.ui_send)
                                Box(
                                    Modifier.matchParentSize().clip(CircleShape)
                                        .semantics { contentDescription = label }
                                        .combinedClickable(
                                            enabled = sendEnabled, role = Role.Button,
                                            onClick = { submitComposer() },
                                            onLongClickLabel = uiString(R.string.ui_send_options),
                                            onLongClick = { showSendMenu = true }
                                        )
                                )
                                DropdownMenu(expanded = sendEnabled && showSendMenu, onDismissRequest = { showSendMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(uiString(R.string.ui_send_without_sound)) },
                                        onClick = { showSendMenu = false; submitComposer(options = sendOptions.copy(silent = true)) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(uiString(R.string.ui_schedule_next_message)) },
                                        onClick = { showSendMenu = false; sendOptionsDialog = true }
                                    )
                                }
                            }
                            ComposerAction.VOICE -> org.msgat.ui.components.VoiceCaptureButton(
                                enabled = voiceEnabled,
                                session = voice,
                                tap = {
                                    if (captureAllowed && !voice.active && inputText.isBlank()) {
                                        showAttachmentMenu = false
                                        showStickers = false
                                        showVoiceRecording = true
                                    }
                                },
                                hold = { !showAttachmentMenu && !showStickers && inputText.isBlank() && beginVoice(hold = true) },
                                modifier = Modifier.matchParentSize()
                            )
                            ComposerAction.SAVE -> {
                                val label = uiString(R.string.ui_save)
                                IconButton(enabled = editReady, onClick = ::saveEdit,
                                    modifier = Modifier.matchParentSize().semantics { contentDescription = label }) {}
                            }
                            ComposerAction.SENDING, ComposerAction.SAVING -> {
                                val status = uiString(if (action == ComposerAction.SAVING) R.string.ui_saving else R.string.ui_preparation_queuing)
                                Box(Modifier.matchParentSize().semantics { contentDescription = status })
                            }
                        }
                    }
                }
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val stickerDensity = androidx.compose.ui.platform.LocalDensity.current
                val stickerScreenHeight = configuration.screenHeightDp.dp
                val stickerImeHeight = with(stickerDensity) { WindowInsets.ime.getBottom(this).toDp() }
                val stickerHeightLimit = (stickerScreenHeight - stickerImeHeight - 140.dp).coerceAtLeast(0.dp)
                val stickerHeight by androidx.compose.animation.core.animateDpAsState(
                    targetValue = (stickerScreenHeight * (if (stickersExpanded) 0.7f else 0.52f))
                        .coerceAtMost(if (stickersExpanded) 640.dp else 360.dp),
                    animationSpec = tween(300), label = "Sticker panel height"
                )
                androidx.compose.animation.AnimatedVisibility(visibleState = stickerVisibility,
                    enter = androidx.compose.animation.expandVertically(tween(300), expandFrom = Alignment.Bottom),
                    exit = androidx.compose.animation.shrinkVertically(tween(300), shrinkTowards = Alignment.Bottom)) {
                    Box(Modifier.fillMaxWidth().height(stickerHeight.coerceAtMost(stickerHeightLimit))) {
                        org.msgat.ui.components.StickerLibraryPanel(
                            org.msgat.MsgATApp.instance.nettyClient,
                            onDismiss = { showStickers = false },
                            canExpand = true, expanded = stickersExpanded, onExpand = { stickersExpanded = it },
                            onBusyChange = { stickerBusy = it },
                            acceptingInput = stickersOpen,
                            preparations = preparations.filter { it.isSticker },
                            onCancelPreparation = onCancelPreparation,
                            onSend = { item ->
                                val reply = replyDraft
                                val result = onSendSticker(item.id, composerState.text.toString(), reply?.message?.id, reply?.selection)
                                if (result == org.msgat.core.media.PreparationSendResult.QUEUED && replyDraft == reply) replyDraft = null
                                result
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ChatColumnWidth)
            .fillMaxWidth().padding(horizontal = 8.dp)) {
            val textBubbleLimit = (maxWidth - if (isSelecting) 70.dp else 62.dp).coerceIn(1.dp, 430.dp)
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isSelecting && searchQuery != null && onReturnToSearch != null && onCloseSearch != null) {
                    val canNavigate = isChatVisible && !attachmentCovering && !stickersCovering
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onReturnToSearch,
                            modifier = Modifier.weight(1f),
                            enabled = canNavigate,
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = uiString(R.string.ui_return_to_search_results),
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(searchQuery, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (onMoveSearch != null) {
                            IconButton(enabled = canNavigate && !searchMoving, onClick = { onMoveSearch(true) }) {
                                Icon(Icons.Default.KeyboardArrowUp, uiString(R.string.ui_search_previous_result))
                            }
                            IconButton(enabled = canNavigate && !searchMoving, onClick = { onMoveSearch(false) }) {
                                Icon(Icons.Default.KeyboardArrowDown, uiString(R.string.ui_search_next_result))
                            }
                        }
                        IconButton(enabled = canNavigate, onClick = onCloseSearch) {
                            Icon(Icons.Default.Close, uiString(R.string.ui_close_search_navigation))
                        }
                    }
                }
                liveShares[peerUuid]?.let { share ->
                    val stopError = uiString(R.string.ui_live_location_stop_error)
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(uiString(R.string.ui_live_location_active), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { coroutineScope.launch {
                            if (!onStopLiveLocation(share.messageId)) {
                                android.widget.Toast.makeText(context, stopError, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } }) { Text(uiString(R.string.ui_live_location_stop)) }
                    }
                }
                pinnedPreview?.let { preview ->
                    val pin = preview.item
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
                        Row(Modifier.weight(1f).clickable { jumpToMessage(pin.id, rememberOrigin = true) },
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(30.dp)
                                .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(uiString(R.string.ui_pinned_message),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                MessageSummaryText(pin.summary,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                        if (preview.canCycle) {
                            IconButton(onClick = onCyclePinnedPreview) {
                                Icon(Icons.Default.KeyboardArrowDown, uiString(R.string.ui_next_pinned_message))
                            }
                        }
                        IconButton(onClick = { showPinnedMessages = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, uiString(R.string.ui_pinned_messages))
                        }
                    }
                }
                val drawBubble: @Composable (MessageUIModel, Boolean) -> Unit = { msg, albumTile ->
                    val liveStatus = if (msg.ext == "live_location" && !msg.isRevoked) {
                        val snapshot by org.msgat.ui.components.observeLiveLocation(peerUuid, msg.id, liveLocationChanges) {
                            onReadLiveLocation(msg.id)
                        }
                        val location = snapshot?.getOrNull()
                        val expired = org.msgat.ui.components.liveLocationExpired(location?.expiresAt)
                        when {
                            expired || location?.ended == true -> uiString(R.string.ui_live_location_ended)
                            msg.isMine && location != null && liveShares[peerUuid]?.messageId != msg.id -> uiString(R.string.ui_live_location_paused)
                            else -> uiString(R.string.ui_live_location)
                        }
                    } else null
                    DisposableEffect(msg.id) {
                        onDispose {
                            visibleMessageFrames.remove(msg.id)
                            if (quoteTarget?.id == msg.id) quoteTarget = null
                        }
                    }
                    Row(
                        Modifier.onGloballyPositioned { coordinates ->
                            val origin = coordinates.positionInRoot()
                            visibleMessageFrames[msg.id] = Rect(
                                origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height
                            )
                        },
                        verticalAlignment = Alignment.Top
                    ) {
                        if (isSelecting && !albumTile) {
                            Checkbox(msg.id in selectedIds, { toggleSelection(msg.id) },
                                enabled = !savingSelection && !msg.isRevoked && msg.ext != "live_location")
                        }
                        val highlight = if (msg.id in selectedIds) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            else Color.Transparent
                        Box(Modifier.weight(1f).background(highlight)) {
                            val status = outgoingReceipts[msg.id] ?: msg.status
                            ChatBubble(
                                msg = if (status == msg.status) msg else msg.copy(status = status),
                                peerUuid = peerUuid,
                                textBubbleLimit = textBubbleLimit,
                                pauseStickers = stickersCovering,
                                replyAuthor = when (msg.replyToSender) {
                                    myUuid -> uiString(R.string.ui_quoted_you)
                                    peerUuid -> alias
                                    else -> null
                                },
                                onMediaClick = { tapped, origin ->
                                    val openPreview = { item: MessageUIModel, photo: MediaPhotoOrigin? ->
                                        previewMove?.cancel()
                                        previewNeighbors = null
                                        previewAlbum = displayedAlbums[tapped.id]?.sortedBy { it.attachment?.album?.index } ?: listOf(tapped)
                                        previewOpenedAsAlbum = previewAlbum.size > 1
                                        previewOrigin = photo
                                        selectedMedia = item
                                    }
                                    when {
                                        isSelecting -> toggleSelection(tapped.id)
                                        tapped.attachment?.available == false -> {
                                            attachmentOpen?.cancel()
                                            openPreview(tapped, null)
                                        }
                                        else -> openReceivedAttachment(tapped.id, tapped.content, tapped.isMine,
                                            onPending = { openPreview(tapped.copy(attachment = it), null) }) {
                                            openPreview(tapped, origin)
                                        }
                                    }
                                },
                                onPhotoFrame = { frame ->
                                    if (frame == null) photoFrames.remove(msg.id) else photoFrames[msg.id] = frame
                                },
                                onDownloadAttachment = { requested ->
                                    coroutineScope.launch {
                                        org.msgat.MsgATApp.instance.nettyClient.secureMessaging
                                            .requestAttachmentDownload(peerUuid, msg.id, requested)
                                    }
                                },
                                onReply = { target ->
                                    if (canReply && !target.isRevoked) {
                                        replyDraft = ComposerReply(target)
                                        replyFocusRequest++
                                    }
                                },
                                onQuoteText = { target ->
                                    if (canReply) coroutineScope.launch {
                                        val available = try { canQuoteMessage(target.id, target.content) }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { false }
                                        if (canReplyNow && quoteTarget == null) {
                                            if (available) { quoteTarget = target; keyboard?.hide() }
                                            else android.widget.Toast.makeText(context, quoteUnavailable, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                quotePicker = if (quoteTarget?.id == msg.id && quoteTarget?.content == msg.content) ({
                                    ReplyQuotePicker(msg, canQuote = { canQuoteMessage(msg.id, msg.content) },
                                        onDismiss = { quoteTarget = null }, onQuote = { selection, preview ->
                                            if (quoteTarget?.id == msg.id && quoteTarget?.content == msg.content && canReplyNow) {
                                                replyDraft = ComposerReply(msg, selection, preview)
                                                replyFocusRequest++
                                            }
                                            quoteTarget = null
                                        })
                                }) else null,
                                swipeReplyEnabled = canReply && quoteTarget == null,
                                onQuotedClick = {
                                    if (!isSelecting) jumpToMessage(it, rememberOrigin = true,
                                        quotePreview = msg.replyToContent, openingQuote = true)
                                },
                                quoteHighlight = highlightedQuote.takeIf { msg.id == highlightedMessageId },
                                onContactClick = { if (!isSelecting && it.contactAccount != null) openedContactCard = it },
                                onLocationClick = { if (!isSelecting) openedLocation = it },
                                liveLocationStatus = liveStatus,
                                selectionMode = isSelecting,
                                albumTile = albumTile,
                                joinsOlder = !albumTile && timelineLayout[msg.id]?.joinsOlder == true,
                                joinsNewer = !albumTile && timelineLayout[msg.id]?.joinsNewer == true,
                                progress = attachmentProgress[msg.id],
                                onRevoke = { onRevokeMessage(it.id) },
                                onCancelQueuedSend = { requestSendCancellation(msg.id) },
                                onRetrySend = { retrySend(msg.id) },
                                retryingSend = msg.id in retryingSends,
                                onDeleteLocally = { requestLocalDeletion(listOf(msg.id)) },
                                audioPlayback = inlineAudio.takeIf { it.activeId == msg.id },
                                onToggleAudio = { media ->
                                    if (captureAllowed && !voice.active) {
                                        openReceivedAttachment(msg.id, media.reference, msg.isMine) {
                                            if (!voice.active && callManager.callState.value in listOf(
                                                    org.msgat.core.call.CallState.IDLE, org.msgat.core.call.CallState.ENDED
                                                )) inlineAudio.toggle(msg.id, media)
                                        }
                                    }
                                },
                                onEdit = { target -> beginEdit(target) },
                                onReEdit = { if (draftReady && !editing && !isSelecting) composerState.setTextAndPlaceCursorAtEnd(it) },
                                onSelect = { requestEditExit { toggleSelection(msg.id) } },
                                onForward = { forwardMessages(listOf(msg.id)) },
                                onSave = { saveMessages(listOf(msg.id)) },
                                onExportAttachment = saveAttachment,
                                onPinState = { onMessagePinState(msg.id) },
                                onSetPinned = { pinned, notify -> onSetMessagePinned(msg.id, pinned, notify) }
                            )
                            val target = msg.id == highlightedMessageId
                            val jumpOpacity by animateFloatAsState(
                                targetValue = if (target) 0.20f else 0f,
                                animationSpec = tween(if (target) 130 else 240),
                                label = "MessageJumpHighlight"
                            )
                            if (jumpOpacity > 0f) {
                                Box(Modifier.matchParentSize().background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = jumpOpacity), RoundedCornerShape(10.dp)))
                            }
                            if (isSelecting) {
                                val checked = msg.id in selectedIds
                                val action = uiString(if (checked) R.string.ui_deselect_chat_message else R.string.ui_select_chat_message)
                                Box(Modifier.matchParentSize().semantics { selected = checked }
                                    .clickable(enabled = !savingSelection && !msg.isRevoked, onClickLabel = action) {
                                        toggleSelection(msg.id)
                                    }) {
                                    if (albumTile) {
                                        Checkbox(
                                            checked, onCheckedChange = null,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                                .background(MaterialTheme.colorScheme.surface, CircleShape).padding(4.dp),
                                            enabled = !savingSelection && !msg.isRevoked
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                            val origin = coordinates.positionInRoot()
                            viewportFrame = Rect(origin.x, origin.y,
                                origin.x + coordinates.size.width, origin.y + coordinates.size.height)
                        },
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(messages.itemCount, key = { index ->
                            val msg = messages.peek(index)
                            if (msg != null) msg.id else index
                        }) { index ->
                            val msg = messages[index]
                            if (msg != null) {
                                val album = displayedAlbums[msg.id]
                                if (album != null && album.first().id != msg.id) return@items
                                Column(Modifier.fillMaxWidth()) {
                                    val layout = timelineLayout[msg.id]
                                    layout?.day?.let { day -> TimelineDayLabel(day) { openDatePicker(day) } }
                                    if (msg.id == unreadRowId) TimelineUnreadDivider()
                                    val arrival = recentArrivals[msg.id].takeIf { animateLiveArrivals }
                                    if (album == null) {
                                        Box(Modifier.fillMaxWidth().timelineArrival(arrival, msg.isMine)) { drawBubble(msg, false) }
                                    } else {
                                        val ordered = album.sortedBy { it.attachment!!.album!!.index }
                                        val rows = if (ordered.size % 2 == 0) ordered.chunked(2)
                                            else listOf(ordered.take(1)) + ordered.drop(1).chunked(2)
                                        Row(
                                            Modifier.fillMaxWidth().timelineArrival(arrival, msg.isMine).padding(horizontal = 8.dp),
                                            horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
                                        ) {
                                            Column(
                                                Modifier.widthIn(max = 320.dp).fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .padding(4.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                for (row in rows) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        row.forEach { member ->
                                                            key(member.id) {
                                                                Box(Modifier.weight(1f)) { drawBubble(member, true) }
                                                            }
                                                        }
                                                    }
                                                }
                                                ordered.firstOrNull { it.attachment?.album?.index == 0 }
                                                    ?.attachment?.caption?.takeIf { it.isNotBlank() }?.let {
                                                        RichMessageText(it, Modifier.padding(8.dp), linksEnabled = !isSelecting)
                                                    }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(if (layout?.joinsNewer == true) 2.dp else 7.dp))
                                }
                            }
                        }
                        if (messages.itemCount > 0 && messages.loadState.append !is androidx.paging.LoadState.NotLoading) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (messages.loadState.append is androidx.paging.LoadState.Error) {
                                        TextButton(onClick = messages::retry) { Text(uiString(R.string.ui_retry_loading_history)) }
                                    } else CircularProgressIndicator(Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                    if (checkingAttachment && !attachmentCovering) {
                        Surface(Modifier.align(Alignment.TopCenter).padding(8.dp), shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(uiString(R.string.ui_checking_attachment))
                                TextButton(onClick = { attachmentOpen?.cancel() }) { Text(uiString(R.string.ui_cancel)) }
                            }
                        }
                    }
                    if (messages.itemCount == 0) {
                        Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            when (messages.loadState.refresh) {
                                is androidx.paging.LoadState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                                is androidx.paging.LoadState.Error -> {
                                    Text(uiString(R.string.ui_history_load_failed), color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { if (retryLatestJump) jumpToLatest() else messages.retry() }) { Text(uiString(R.string.ui_retry)) }
                                }
                                is androidx.paging.LoadState.NotLoading -> if (retryLatestJump) {
                                    Text(uiString(R.string.ui_history_load_failed), color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { jumpToLatest() }) { Text(uiString(R.string.ui_retry)) }
                                } else {
                                    Text(uiString(R.string.ui_no_messages_in_conversation),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else if (messages.loadState.refresh is androidx.paging.LoadState.Error ||
                        messages.loadState.prepend is androidx.paging.LoadState.Error) {
                        TextButton(onClick = { if (retryLatestJump) jumpToLatest() else messages.retry() },
                            modifier = Modifier.align(Alignment.BottomCenter)) {
                            Text(uiString(R.string.ui_retry_loading_history))
                        }
                    }
                    if (retryLatestJump && messages.itemCount > 0 &&
                        messages.loadState.refresh !is androidx.paging.LoadState.Error &&
                        messages.loadState.prepend !is androidx.paging.LoadState.Error) {
                        TextButton(onClick = { jumpToLatest() }, modifier = Modifier.align(Alignment.BottomCenter)) {
                            Text(uiString(R.string.ui_retry_loading_history))
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showFloatingDay && !isAtBottom && isChatVisible && !attachmentCovering && !isSelecting,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = androidx.compose.animation.fadeIn(tween(130)),
                        exit = androidx.compose.animation.fadeOut(tween(200))
                    ) {
                        floatingDay?.let { day -> TimelineDayLabel(day) { openDatePicker(day) } }
                    }
                }
            }

            val showTimelineNavigation = isChatVisible && !attachmentCovering && !stickersCovering &&
                !isSelecting && !restoringHistory && !positioningHistory
            val showScrollToBottom = showTimelineNavigation && messages.itemCount > 0 && !isAtBottom
            val showQuoteReturn = showTimelineNavigation && quoteReturnAnchors.isNotEmpty()

            if (showQuoteReturn) {
                FloatingActionButton(
                    onClick = {
                        val anchor = quoteReturnAnchors.lastOrNull() ?: return@FloatingActionButton
                        jumpToMessage(anchor.messageId, returnOffset = anchor.offset, returningTo = anchor)
                    },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = if (showScrollToBottom) 56.dp else 8.dp).size(40.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiString(R.string.ui_return_to_position))
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 8.dp),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it })
            ) {
                Box(Modifier.padding(top = if (unreadCount > 0) 7.dp else 0.dp)) {
                    FloatingActionButton(
                    onClick = { jumpToLatest() },
                    modifier = Modifier.size(40.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (unreadCount > 0) uiString(R.string.ui_go_to_latest_with_unread, unreadCount)
                            else uiString(R.string.ui_go_to_latest)
                    )
                    }
                    if (unreadCount > 0) {
                        org.msgat.ui.components.UnreadBadge(
                            if (unreadCount > 99) "99+" else unreadCount.toString(),
                            Modifier.align(Alignment.TopCenter).offset(y = (-7).dp).clearAndSetSemantics { }
                        )
                    }
                }
            }
        }
    }

    ChatActionSheet(attachmentVisibility, uiString(R.string.ui_close), onDismiss = { showAttachmentMenu = false }) {
        ChatAttachmentPanel(
            state = attachmentPanel,
            foreground = isResumed && isChatVisible,
            acceptingInput = attachmentsOpen,
            captureAllowed = captureAllowed,
            onTakePhoto = {
                attachmentPanel.rememberReviewPosition()
                showAttachmentMenu = false
                showPhotoCapture = true
            },
            onRecordVideo = {
                attachmentPanel.rememberReviewPosition()
                showAttachmentMenu = false
                showVideoCapture = true
            },
            onBrowseGallery = {
                attachmentPanel.rememberReviewPosition()
                pickerSection = AttachmentSection.Gallery
                showAttachmentMenu = false
                photoPickerLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onPickFiles = {
                pickerSection = AttachmentSection.Files
                showAttachmentMenu = false
                filePickerLauncher.launch(arrayOf("*/*"))
            },
            onPickAudio = {
                attachmentPanel.rememberReviewPosition()
                pickerSection = AttachmentSection.Audio
                showAttachmentMenu = false
                filePickerLauncher.launch(arrayOf("audio/*"))
            },
            onRecordVoice = { showAttachmentMenu = false; showVoiceRecording = true },
            onStickers = ::openStickers,
            onContact = { showAttachmentMenu = false; showContactPicker = true },
            onLocation = { showAttachmentMenu = false; showLocationPicker = true },
            onReview = { uris -> reviewAttachments(uris, returnToPanel = true) }
        )
    }

    selectedMedia?.let { media ->
        val openSharedFromPreview: (MessageUIModel) -> Unit = { item ->
            if (selectedMedia?.id == item.id) {
                sharedMediaStart = if (item.attachment?.isAudio == true) SharedMediaCategory.AUDIO else SharedMediaCategory.MEDIA
                closeMedia()
                showSharedMedia = true
            }
        }
        val replyFromPreview: ((MessageUIModel) -> Unit)? = if (draftReady && !editing && !isSelecting &&
            !inlineVoice && !attachmentCovering && !stickersCovering && captureAllowed) {
            { item ->
                if (selectedMedia?.id == item.id && !item.isRevoked) {
                    closeMedia()
                    replyDraft = ComposerReply(item)
                    replyFocusRequest++
                }
            }
        } else null
        if (!previewOpenedAsAlbum || previewAlbum.isEmpty()) {
            ConversationMediaViewer(media, attachmentProgress[media.id],
                newer = previewNeighbors?.newer?.let { item -> { moveMedia(item) } },
                older = previewNeighbors?.older?.let { item -> { moveMedia(item) } },
                photoOrigin = previewOrigin,
                returnFrame = photoFrames[media.id],
                onCloseStarted = { previewMove?.cancel() },
                onShowMessage = { closeMedia(); jumpToMessage(media.id, rememberOrigin = true) },
                onForward = { closeMedia(); forwardMessages(listOf(media.id)) },
                onReply = replyFromPreview?.let { reply -> { reply(media) } },
                onOpenSharedMedia = { openSharedFromPreview(media) },
                conversationPeer = peerUuid,
                onExportAttachment = saveAttachment,
                onRetryRefresh = if (previewRefreshFailed) ({ previewReload++ }) else null,
                onDismiss = ::closeMedia)
        } else {
            AlbumMediaViewer(previewAlbum, media.id, attachmentProgress, peerUuid,
                photoOrigin = previewOrigin,
                photoFrames = photoFrames,
                initiallyVerified = media.attachment?.available == true,
                verifyAttachment = ::verifyPreviewAttachment,
                onVisibleItem = { item ->
                    if (selectedMedia?.id != item.id) previewOrigin = null
                    selectedMedia = item
                },
                onShowMessage = { id -> closeMedia(); jumpToMessage(id, rememberOrigin = true) },
                onForward = { id -> closeMedia(); forwardMessages(listOf(id)) },
                onReply = replyFromPreview,
                onOpenSharedMedia = openSharedFromPreview,
                onExportAttachment = saveAttachment,
                onDismiss = ::closeMedia)
        }
    }
}

@Composable
private fun MessageExpirationDialog(
    load: suspend () -> org.msgat.data.db.MessageRetentionSettings,
    save: suspend (org.msgat.data.db.MessageRetentionSettings) -> Boolean,
    dismiss: () -> Unit
) {
    val outgoing = org.msgat.data.db.MessageExpirationStore.choices.zip(listOf(
        R.string.ui_duration_off, R.string.ui_duration_minute, R.string.ui_duration_hour,
        R.string.ui_duration_day, R.string.ui_duration_week, R.string.ui_duration_30_days
    ))
    val local = org.msgat.data.db.MessageExpirationStore.retentionChoices.zip(listOf(
        R.string.ui_duration_forever, R.string.ui_duration_day, R.string.ui_duration_week,
        R.string.ui_duration_30_days, R.string.ui_duration_90_days
    ))
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<org.msgat.data.db.MessageRetentionSettings?>(null) }
    var draft by remember { mutableStateOf<org.msgat.data.db.MessageRetentionSettings?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        try { val current = load(); original = current; draft = current }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = R.string.ui_retention_load_error }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) dismiss() },
        properties = androidx.compose.ui.window.DialogProperties(
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn
        ),
        title = { Text(uiString(if (confirming) R.string.ui_shorten_history else R.string.ui_message_retention)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirming) {
                    Text(uiString(R.string.ui_shorten_history_warning))
                } else {
                    val selected = draft
                    DurationPicker(uiString(R.string.ui_auto_delete_both), outgoing, selected?.outgoing, !saving) {
                        draft = selected?.copy(outgoing = it)
                    }
                    Text(uiString(R.string.ui_new_outgoing_only), style = MaterialTheme.typography.bodySmall)
                    DurationPicker(uiString(R.string.ui_local_retention), local, selected?.local, !saving) {
                        draft = selected?.copy(local = it)
                    }
                }
                error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = draft != null && !saving, onClick = {
                val selected = draft ?: return@TextButton
                val current = original ?: return@TextButton
                if (!confirming && selected.shortensLocalHistory(current)) {
                    confirming = true
                    return@TextButton
                }
                saving = true
                scope.launch {
                    try {
                        if (save(selected)) dismiss()
                        else error = R.string.ui_retention_save_error
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = R.string.ui_retention_save_error }
                    finally { saving = false }
                }
            }) { Text(uiString(if (confirming) R.string.ui_apply_and_delete else R.string.ui_save)) }
        },
        dismissButton = {
            TextButton(onClick = { if (confirming) confirming = false else dismiss() }, enabled = !saving) { Text(uiString(R.string.ui_cancel)) }
        }
    )
}

@Composable
private fun DurationPicker(label: String, choices: List<Pair<Long, Int>>, selected: Long?, enabled: Boolean, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && selected != null) {
            Text(uiString(choices.firstOrNull { it.first == selected }?.second ?: R.string.ui_loading))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (duration, name) ->
                DropdownMenuItem(text = { Text(uiString(name)) }, onClick = { expanded = false; onSelect(duration) })
            }
        }
    }
}

private data class ComposerReply(val message: MessageUIModel, val selection: String? = null, val preview: String? = null)
private data class ComposerAccessory(val message: MessageUIModel, val editing: Boolean, val quote: String? = null)
private enum class ComposerAction { VOICE, SEND, SAVE, SENDING, SAVING }

@Composable
private fun ComposerActionGlyph(action: ComposerAction, sendEnabled: Boolean, recordingHeld: Boolean) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.animation.AnimatedContent(
        targetState = action,
        modifier = Modifier.clearAndSetSemantics { },
        transitionSpec = {
            val duration = if (targetState == ComposerAction.VOICE) 150 else 220
            val easing = if (targetState == ComposerAction.VOICE) androidx.compose.animation.core.FastOutSlowInEasing
                else androidx.compose.animation.core.Easing { fraction ->
                    val rest = 1f - fraction
                    1f - rest * rest * rest * rest * rest
                }
            (androidx.compose.animation.fadeIn(tween(duration, easing = easing)) +
                androidx.compose.animation.scaleIn(tween(duration, easing = easing), initialScale = 0.1f))
                .togetherWith(androidx.compose.animation.fadeOut(tween(duration, easing = easing)) +
                    androidx.compose.animation.scaleOut(tween(duration, easing = easing), targetScale = 0.1f))
        },
        label = "Composer action"
    ) { shown ->
        val background = when {
            shown == ComposerAction.VOICE -> colors.secondaryContainer
            shown == ComposerAction.SEND && !sendEnabled -> colors.surfaceVariant
            else -> colors.primary
        }
        Box(Modifier.size(48.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
            when (shown) {
                ComposerAction.VOICE -> Icon(Icons.Default.Mic, null,
                    tint = if (recordingHeld) colors.error else colors.onSecondaryContainer)
                ComposerAction.SEND -> Icon(Icons.AutoMirrored.Filled.Send, null,
                    tint = if (sendEnabled) colors.onPrimary else colors.onSurfaceVariant)
                ComposerAction.SAVE -> Icon(Icons.Default.Check, null, tint = colors.onPrimary)
                ComposerAction.SENDING, ComposerAction.SAVING ->
                    CircularProgressIndicator(Modifier.size(17.dp), color = colors.onPrimary, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ComposerAccessoryBar(
    accessory: ComposerAccessory,
    alias: String,
    active: Boolean,
    canDismiss: Boolean,
    onJump: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = accessory.message
    val editing = accessory.editing
    val colors = MaterialTheme.colorScheme
    val title = if (editing) {
        uiString(if (message.ext == "attachment") R.string.ui_editing_caption else R.string.ui_editing_message)
    } else {
        val author = if (message.isMine) uiString(R.string.ui_quoted_you) else alias
        uiString(R.string.ui_replying_to, author)
    }
    val preview = accessory.quote ?: when (message.ext) {
        "txt", "location", "live_location" -> message.content
        "contact" -> uiString(R.string.ui_contact_card)
        else -> message.attachment?.let { it.caption.ifBlank { it.filename } } ?: uiString(R.string.ui_quote_attachment)
    }

    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(LocalMessagingColors.current.timeline)
            .then(if (active) Modifier else Modifier.clearAndSetSemantics { }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f)
                .clickable(enabled = active, onClickLabel = uiString(R.string.ui_jump_to_message), onClick = onJump)
                .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.width(3.dp).height(30.dp).background(colors.primary, RoundedCornerShape(2.dp)))
            Icon(if (editing) Icons.Default.Edit else Icons.Default.Reply, contentDescription = null,
                modifier = Modifier.size(22.dp), tint = colors.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = colors.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                MessageSummaryText(preview, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onDismiss, enabled = active && canDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, uiString(if (editing) R.string.ui_cancel_edit else R.string.ui_cancel_reply),
                modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun MessageMenuItem(
    title: Int,
    icon: ImageVector,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val errorColor = if (destructive && enabled) MaterialTheme.colorScheme.error else Color.Unspecified
    DropdownMenuItem(
        text = { Text(uiString(title), color = errorColor) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = if (errorColor == Color.Unspecified) LocalContentColor.current else errorColor)
        },
        enabled = enabled,
        onClick = onClick
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
// Renders an individual chat message bubble with dynamic styling based on sender.
internal fun ChatBubble(
    msg: MessageUIModel,
    peerUuid: String,
    textBubbleLimit: Dp = 430.dp,
    replyAuthor: String? = null,
    progress: org.msgat.core.network.AttachmentProgress? = null,
    onMediaClick: (MessageUIModel, MediaPhotoOrigin?) -> Unit = { _, _ -> },
    onPhotoFrame: ((MediaPhotoFrame?) -> Unit)? = null,
    onDownloadAttachment: ((Boolean) -> Unit)? = null,
    onReply: (MessageUIModel) -> Unit = {},
    onQuoteText: ((MessageUIModel) -> Unit)? = null,
    quotePicker: (@Composable () -> Unit)? = null,
    onQuotedClick: (String) -> Unit = {},
    quoteHighlight: String? = null,
    onRevoke: (MessageUIModel) -> Unit = {},
    onCancelQueuedSend: (() -> Unit)? = null,
    onRetrySend: (() -> Unit)? = null,
    retryingSend: Boolean = false,
    onDeleteLocally: (() -> Unit)? = null,
    audioPlayback: InlineAudioPlayback? = null,
    onToggleAudio: ((org.msgat.core.media.AttachmentMetadata) -> Unit)? = null,
    onEdit: (MessageUIModel) -> Unit = {},
    onReEdit: (String) -> Unit = {},
    onSelect: () -> Unit = {},
    onForward: () -> Unit = {},
    onSave: () -> Unit = {},
    onExportAttachment: ((String, org.msgat.core.media.AttachmentMetadata) -> Unit)? = null,
    onPinState:suspend()->Boolean={false},
    onSetPinned:suspend(Boolean,Boolean)->Boolean={ _, _ -> false },
    selectionMode: Boolean = false,
    albumTile: Boolean = false,
    joinsOlder: Boolean = false,
    joinsNewer: Boolean = false,
    onContactClick: (MessageUIModel) -> Unit = {},
    onLocationClick: (MessageUIModel) -> Unit = {},
    liveLocationStatus: String? = null,
    swipeReplyEnabled: Boolean = false,
    pauseStickers: Boolean = false
) {
    val context = LocalContext.current
    val isMe = msg.isMine
    val bubbleShape = RoundedCornerShape(
        topStart = if (!isMe && joinsOlder) 4.dp else 16.dp,
        topEnd = if (isMe && joinsOlder) 4.dp else 16.dp,
        bottomEnd = if (isMe && joinsNewer) 4.dp else 16.dp,
        bottomStart = if (!isMe && joinsNewer) 4.dp else 16.dp
    )
    val contextConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    val timeString = remember(msg.timestamp, contextConfiguration) {
        android.text.format.DateFormat.getTimeFormat(context).format(Date(msg.timestamp))
    }
    val timeLabel = if (msg.isEdited) uiString(R.string.ui_message_time_edited, timeString) else timeString
    val expirationDate = remember(msg.expiresAt, contextConfiguration) {
        msg.expiresAt?.let {
            val date = Date(it)
            "${android.text.format.DateFormat.getDateFormat(context).format(date)} " +
                android.text.format.DateFormat.getTimeFormat(context).format(date)
        }
    }
    val expirationLabel = if (expirationDate == null || msg.isRevoked) null
        else uiString(R.string.ui_message_expiration_deadline, expirationDate)
    val hasLinkText = msg.content.contains("https://", ignoreCase = true) || msg.content.contains("http://", ignoreCase = true)
    val bubbleWidth = if (msg.ext == "txt") textBubbleLimit else 250.dp
    var showSendStatus by remember(msg.id, msg.status, selectionMode) { mutableStateOf(false) }
    val canRetrySend = isMe && !msg.isRevoked && msg.status in -1..0 && onRetrySend != null
    val sendStatusAction: (() -> Unit)? = if (!selectionMode && !retryingSend &&
        (canRetrySend || isMe && msg.status == -1)) {
        { showSendStatus = true }
    } else null
    val footer: @Composable (Color, Boolean) -> Unit = { color, overMedia ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (expirationLabel != null) {
                Icon(Icons.Default.AccessTime, contentDescription = expirationLabel,
                    tint = color, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(timeLabel, color = color, fontSize = 10.sp)
            if (isMe && !msg.isRevoked) {
                Spacer(Modifier.width(4.dp))
                MessageReceiptMark(msg.status, overMedia,
                    sendStatusAction?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            }
        }
    }

    val media by rememberAttachment(msg, progress)
    val showVisualFooter = !albumTile && media?.let {
        it.available && !it.isSticker && it.album == null && (it.isImage || it.isVideo) && it.caption.isEmpty()
    } == true
    val visualFooter: (@Composable () -> Unit)? = if (showVisualFooter) {
        { footer(Color.White, true) }
    } else null
    if (showSendStatus && isMe && !msg.isRevoked) {
        val failed = msg.status == -1
        AlertDialog(
            onDismissRequest = { showSendStatus = false },
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
            title = { Text(uiString(if (failed) R.string.ui_receipt_failed else R.string.ui_receipt_sending)) },
            confirmButton = {
                if (canRetrySend) TextButton(onClick = { showSendStatus = false; onRetrySend?.invoke() }) {
                    Text(uiString(R.string.ui_send_retry))
                }
            },
            dismissButton = { TextButton(onClick = { showSendStatus = false }) { Text(uiString(R.string.ui_close)) } }
        )
    }
    var showMenu by remember { mutableStateOf(false) }
    val quote: @Composable () -> Unit = {
        msg.replyToId?.takeIf { it.isNotEmpty() }?.let { id ->
            ReplyQuoteCard(replyAuthor, msg.replyToContent, enabled = !selectionMode,
                onOpen = { onQuotedClick(id) }, onLongClick = { showMenu = true })
        }
    }
    var pinned by remember(msg.id){mutableStateOf<Boolean?>(null)}
    LaunchedEffect(showMenu){if(showMenu){pinned=null;try{pinned=onPinState()}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){}}}
    val scope = rememberCoroutineScope()
    var requestedPin by remember(msg.id) { mutableStateOf<Boolean?>(null) }
    var revealedForCopy by remember(msg.id, msg.content) { mutableStateOf<Set<Int>>(emptySet()) }
    var visibleLinks by remember(msg.id, msg.content, media?.caption) { mutableStateOf<List<String>>(emptyList()) }
    var showLinks by remember(msg.id, msg.content, media?.caption) { mutableStateOf(false) }
    if (showLinks && !msg.isRevoked && !selectionMode && visibleLinks.isNotEmpty()) {
        MessageLinksDialog(visibleLinks) { showLinks = false }
    }
    val copySource by rememberUpdatedState(msg)
    val textToCopy = if (msg.ext == "txt") msg.content else media?.caption
    val currentTextToCopy by rememberUpdatedState(textToCopy)
    val hiddenText = uiString(R.string.ui_hidden_message_text)
    val copied = uiString(R.string.ui_message_copied)
    val copyFailed = uiString(R.string.ui_message_copy_failed)
    var notifyPin by remember(msg.id) { mutableStateOf(false) }
    val pinError = uiString(R.string.ui_pin_change_failed)
    val changePin: (Boolean, Boolean) -> Unit = { value, notify ->
        scope.launch {
            if (!onSetPinned(value, notify)) {
                android.widget.Toast.makeText(context, pinError, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pinAction = requestedPin
    if (pinAction != null && !msg.isRevoked && !selectionMode) {
        val title = if (pinAction) R.string.ui_pin_message else R.string.ui_unpin_message
        AlertDialog(
            onDismissRequest = { requestedPin = null },
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
            title = { Text(uiString(title)) },
            text = {
                if (pinAction) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = notifyPin, onCheckedChange = { notifyPin = it })
                    Text(uiString(R.string.ui_pin_notify_peer), Modifier.clickable { notifyPin = !notifyPin })
                } else Text(uiString(R.string.ui_unpin_for_both))
            },
            confirmButton = {
                TextButton(onClick = {
                    requestedPin = null
                    changePin(pinAction, pinAction && notifyPin)
                }) { Text(uiString(title)) }
            },
            dismissButton = {
                TextButton(onClick = { requestedPin = null }) { Text(uiString(R.string.ui_cancel)) }
            }
        )
    }
    var confirmDownloadAgain by remember(msg.id) { mutableStateOf(false) }
    val downloadAgainScope = rememberCoroutineScope()
    if (confirmDownloadAgain && !isMe && !msg.isRevoked && !selectionMode) {
        AlertDialog(
            onDismissRequest = { confirmDownloadAgain = false },
            title = { Text(uiString(R.string.ui_download_attachment_again)) },
            text = { Text(uiString(R.string.ui_download_attachment_again_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownloadAgain = false
                    downloadAgainScope.launch {
                        org.msgat.MsgATApp.instance.nettyClient.secureMessaging.replaceAttachmentCache(peerUuid, msg.id)
                    }
                }) { Text(uiString(R.string.ui_download_attachment_again)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownloadAgain = false }) { Text(uiString(R.string.ui_cancel)) }
            },
            properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)
        )
    }

    if (msg.isRevoked) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = uiString(if (isMe) R.string.ui_revoked_by_you else R.string.ui_revoked_by_peer),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                if (isMe && msg.ext == "txt" && msg.content.isNotEmpty()) {
                    Text(
                        text = uiString(R.string.ui_re_edit),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable { onReEdit(msg.content) }
                    )
                }
            }
        }
    } else {
        SwipeReplySurface(
            enabled = swipeReplyEnabled && quotePicker == null && !selectionMode && !showMenu,
            isMine = isMe,
            onReply = { onReply(msg) }
        ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (albumTile) 0.dp else 8.dp),
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
        if (!isMe && !albumTile && !selectionMode) {
            ChatMessageAvatar(msg.senderAvatar, visible = !joinsNewer, modifier = Modifier.align(Alignment.Bottom).padding(end = 6.dp))
        }

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = albumTile)
        ) {
            if (msg.ext != "txt") {
                ForwardAttribution(msg.forwardInfo, MaterialTheme.colorScheme.primary, bubbleWidth)
            }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (expirationLabel != null) DropdownMenuItem(
                        text = { Text(expirationLabel) }, enabled = false, onClick = {},
                        leadingIcon = { Icon(Icons.Default.AccessTime, contentDescription = null) }
                    )
                    MessageMenuItem(
                        R.string.ui_reply, Icons.Default.Reply,
                        onClick = {
                            showMenu = false
                            onReply(msg)
                        }
                    )
                    if (msg.ext in listOf("txt", "attachment", "contact", "location")) {
                        MessageMenuItem(R.string.ui_select, Icons.Default.CheckBoxOutlineBlank) { showMenu = false; onSelect() }
                        if (msg.expiresAt == null) {
                            MessageMenuItem(R.string.ui_forward, Icons.AutoMirrored.Filled.ArrowForward) { showMenu = false; onForward() }
                            MessageMenuItem(R.string.ui_save_message, Icons.Default.Bookmark) { showMenu = false; onSave() }
                        }
                        MessageMenuItem(if (pinned == true) R.string.ui_unpin_message else R.string.ui_pin_message,
                            Icons.Default.PushPin, enabled = pinned != null, onClick = {
                            showMenu = false
                            notifyPin = false
                            pinned?.let { requestedPin = !it }
                        })
                    }
                    if (msg.ext == "txt") {
                        if (msg.expiresAt == null && onQuoteText != null && swipeReplyEnabled) MessageMenuItem(
                            R.string.ui_quote_selected_text, Icons.Default.FormatQuote,
                            onClick = { showMenu = false; onQuoteText(msg) }
                        )
                    }
                    if (!textToCopy.isNullOrBlank()) {
                        MessageMenuItem(
                            if (msg.ext == "txt") R.string.ui_copy_message_text else R.string.ui_copy_attachment_caption,
                            Icons.Default.ContentCopy,
                            onClick = {
                                showMenu = false
                                val revealed = if (msg.ext == "txt") revealedForCopy else emptySet()
                                scope.launch {
                                    try {
                                        val visible = visibleMessageCopy(textToCopy, hiddenText, revealed)
                                        if (copySource.id == msg.id && currentTextToCopy == textToCopy &&
                                            !copySource.isRevoked && visible.isNotEmpty()) {
                                            putMessageOnClipboard(context, visible)
                                            android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        android.widget.Toast.makeText(context, copyFailed, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                    val canRevokeOrEdit = isMe
                    if (!selectionMode && quotePicker == null && visibleLinks.isNotEmpty()) {
                        MessageMenuItem(R.string.ui_message_links, Icons.Default.Link, onClick = {
                            showMenu = false
                            showLinks = true
                        })
                    }
                    if (canRevokeOrEdit && !msg.isRevoked) {
                        if (!selectionMode && canRetrySend && onRetrySend != null) {
                            MessageMenuItem(R.string.ui_send_retry, Icons.Default.Refresh, enabled = !retryingSend,
                                onClick = { showMenu = false; onRetrySend() })
                        }
                        val canEditCaption = msg.ext == "attachment" && media?.isSticker == false
                        if (msg.expiresAt == null && (msg.ext == "txt" || canEditCaption)) {
                            MessageMenuItem(
                                if (canEditCaption) R.string.ui_edit_caption else R.string.ui_edit_message, Icons.Default.Edit,
                                onClick = {
                                    showMenu = false
                                    onEdit(if (canEditCaption) msg.copy(attachment=media) else msg)
                                }
                            )
                        }
                    }
                    if (msg.ext == "attachment" && media?.available == true) {
                        if (!isMe) {
                            MessageMenuItem(R.string.ui_download_attachment_again, Icons.Default.Refresh,
                                onClick = { showMenu = false; confirmDownloadAgain = true })
                        }
                        if (msg.expiresAt == null && onExportAttachment != null) MessageMenuItem(
                            R.string.ui_save_attachment, Icons.Default.FileDownload,
                            onClick = {
                                showMenu = false
                                media?.takeIf { it.available }?.let { onExportAttachment(msg.id, it) }
                            }
                        )
                    }
                    val canDelete = !selectionMode && msg.ext != "live_location" && (!isMe || msg.status != 0)
                    if ((canRevokeOrEdit && !msg.isRevoked) || (canDelete && onDeleteLocally != null)) {
                        HorizontalDivider()
                    }
                    if (canRevokeOrEdit && !msg.isRevoked) {
                        if (!selectionMode && msg.status == 0 && onCancelQueuedSend != null) {
                            MessageMenuItem(R.string.ui_send_cancel_action, Icons.Default.Close, destructive = true) {
                                showMenu = false
                                onCancelQueuedSend()
                            }
                        }
                        MessageMenuItem(R.string.ui_revoke, Icons.Default.DeleteOutline, destructive = true) {
                            showMenu = false
                            onRevoke(msg)
                        }
                    }
                    if (canDelete && onDeleteLocally != null) {
                        MessageMenuItem(R.string.ui_local_delete_action, Icons.Default.DeleteOutline, destructive = true) {
                            showMenu = false
                            onDeleteLocally()
                        }
                    }
                }

                Column(
                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                ) {
                    if (msg.ext != "txt") quote()

                    Box(
                        modifier = Modifier
                            .widthIn(max = bubbleWidth)
                            .run {
                                if (msg.ext in listOf("txt", "contact", "location", "live_location")) {
                                    val colors = LocalMessagingColors.current
                                    val normalBg = if (isMe) colors.outgoingBubble else colors.incomingBubble
                                    background(
                                        color = normalBg,
                                        shape = bubbleShape
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            if (!selectionMode) {
                                                if (msg.ext == "contact") onContactClick(msg)
                                                if (msg.ext == "location" || msg.ext == "live_location") onLocationClick(msg)
                                            }
                                        },
                                        onLongClick = { showMenu = true }
                                    )
                                    .padding(horizontal = 11.dp, vertical = 7.dp)
                                } else {
                                    clip(RoundedCornerShape(12.dp))
                                }
                            }
                    ) {
                val colors = MaterialTheme.colorScheme
                val bubbleTextColor = if (isMe) colors.onPrimaryContainer else colors.onSecondaryContainer
                if (msg.ext == "txt") {
                    Column {
                        ForwardAttribution(msg.forwardInfo, bubbleTextColor, bubbleWidth)
                        quote()
                        if (quotePicker != null) {
                            CompositionLocalProvider(LocalContentColor provides bubbleTextColor) { quotePicker() }
                            Box(Modifier.align(Alignment.End)) { footer(bubbleTextColor.copy(alpha = 0.74f), false) }
                        } else if (!hasLinkText) {
                            val displayText = if (quoteHighlight == null) org.msgat.core.call.displayCallSummary(msg.content) else msg.content
                            TextBubbleLayout(body = {
                                RichMessageText(displayText, color = bubbleTextColor, linksEnabled = !selectionMode, alignFooter = true, quoteHighlight = quoteHighlight,
                                    onLongClick = { showMenu = true }, onReveal = { revealedForCopy = it },
                                    onVisibleLinks = { visibleLinks = it })
                            }, footer = { footer(bubbleTextColor.copy(alpha = 0.74f), false) })
                        } else {
                            LinkPreviewMessage(msg.id, msg.content, canOpen = !selectionMode,
                                downloadPeer = peerUuid.takeUnless { msg.isMine }) {
                                RichMessageText(msg.content, color = bubbleTextColor, linksEnabled = !selectionMode, quoteHighlight = quoteHighlight,
                                    onLongClick = { showMenu = true }, onReveal = { revealedForCopy = it },
                                    onVisibleLinks = { visibleLinks = it })
                            }
                            Box(Modifier.align(Alignment.End).padding(top = 3.dp)) {
                                footer(bubbleTextColor.copy(alpha = 0.74f), false)
                            }
                        }
                    }
                } else if (msg.ext in listOf("contact", "location", "live_location")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val location = msg.ext != "contact"
                        Icon(if (location) Icons.Default.LocationOn else Icons.Default.Person, contentDescription = null, tint = bubbleTextColor)
                        Column {
                            val title = if (msg.content == "Location") {
                                uiString(if (msg.ext == "live_location") R.string.ui_live_location else R.string.ui_location)
                            } else msg.content
                            Text(title, style = MaterialTheme.typography.titleSmall, color = bubbleTextColor, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (liveLocationStatus != null && liveLocationStatus != title) {
                                Text(liveLocationStatus, style = MaterialTheme.typography.bodySmall, color = bubbleTextColor)
                            } else if (msg.ext != "live_location" && (!location || msg.content != "Location")) {
                                Text(uiString(if (location) R.string.ui_location else R.string.ui_contact_card),
                                    style = MaterialTheme.typography.bodySmall, color = bubbleTextColor)
                            }
                        }
                    }
                } else if (msg.ext == "attachment") {
                    AttachmentCard(
                        media = media, progress = progress,
                        onOpen = { origin ->
                            if (media?.let { it.isImage || it.isVideo || it.isAudio } == true) {
                                onMediaClick(msg.copy(attachment = media), origin)
                            }
                            else if (!selectionMode && msg.expiresAt == null) {
                                media?.takeIf { it.available }?.let { onExportAttachment?.invoke(msg.id, it) }
                            }
                        },
                        onLongClick = { showMenu = true },
                        albumTile = albumTile,
                        onPhotoFrame = onPhotoFrame,
                        audioPlayback = audioPlayback,
                        onToggleAudio = onToggleAudio.takeUnless { selectionMode },
                        onRetryUpload = onRetrySend.takeIf { isMe && msg.status == 0 && !selectionMode },
                        retryingUpload = retryingSend,
                        onDownload = onDownloadAttachment.takeIf { !selectionMode && !isMe && !msg.isRevoked },
                        visualFooter = visualFooter, pauseStickerPlayback = pauseStickers,
                        captionLinksEnabled = !selectionMode, onCaptionLinks = { visibleLinks = it },
                        fileExportAllowed = if (selectionMode) null else msg.expiresAt == null && onExportAttachment != null
                    )
                } else {
                    Text(uiString(R.string.ui_unsupported_attachment), Modifier.padding(12.dp))
                }
            }
            }

            if (msg.ext != "txt" && !showVisualFooter) {
                Box(Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)) {
                    footer(MaterialTheme.colorScheme.onSurfaceVariant, false)
                }
            }
        }

        if (isMe && !albumTile && !selectionMode) {
            ChatMessageAvatar(msg.senderAvatar, visible = !joinsNewer, modifier = Modifier.align(Alignment.Bottom).padding(start = 6.dp))
        }
    }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyQuoteCard(author: String?, excerpt: String?, enabled: Boolean, onOpen: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = when (excerpt) {
        "[Message revoked]" -> uiString(R.string.ui_quote_revoked)
        "[Media]", "[Attachment]", "[ATTACHMENT]" -> uiString(R.string.ui_quote_attachment)
        else -> excerpt?.takeIf { it.isNotBlank() } ?: uiString(R.string.ui_quote_no_preview)
    }
    Column(Modifier.widthIn(max = 228.dp).padding(bottom = 4.dp)
        .clip(RoundedCornerShape(6.dp)).background(colors.primary.copy(alpha = 0.12f))
        .drawBehind {
            val thickness = 2.dp.toPx()
            val edge = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - thickness
            drawRect(colors.primary, topLeft = androidx.compose.ui.geometry.Offset(edge, 0f),
                size = androidx.compose.ui.geometry.Size(thickness, size.height))
        }
        .combinedClickable(enabled = enabled, onClickLabel = uiString(R.string.ui_quote_jump), onClick = onOpen, onLongClick = onLongClick)
        .padding(horizontal = 9.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(author ?: uiString(R.string.ui_quoted_message), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium, color = colors.primary, fontWeight = FontWeight.SemiBold)
        MessageSummaryText(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ForwardAttribution(info: org.msgat.data.db.ForwardedMessageInfo?, color: Color, width: Dp) {
    if (info == null) return
    val author = info.name?.takeIf { it.isNotBlank() }
    Text(
        text = if (author == null) uiString(R.string.ui_forwarded_message)
            else uiString(R.string.ui_forwarded_from, author),
        modifier = Modifier.widthIn(max = width).padding(bottom = 4.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    )
}

@Composable
private fun TextBubbleLayout(body: @Composable () -> Unit, footer: @Composable () -> Unit) {
    Layout(content = { Box { body() }; Box { footer() } }) { measurables, constraints ->
        val available = constraints.copy(minWidth = 0, minHeight = 0)
        val text = measurables[0].measure(available)
        val metadata = measurables[1].measure(available)
        val lineRight = text[MessageLastLineRight]
        val lineTop = text[MessageLastLineTop]
        val lineBottom = text[MessageLastLineBottom]
        val hasLine = lineRight != AlignmentLine.Unspecified && lineTop != AlignmentLine.Unspecified &&
            lineBottom != AlignmentLine.Unspecified
        val inlineWidth = if (hasLine) maxOf(text.width, lineRight + 8.dp.roundToPx() + metadata.width) else 0
        val inline = hasLine && layoutDirection == LayoutDirection.Ltr &&
            metadata.height <= lineBottom - lineTop && inlineWidth <= constraints.maxWidth
        val width = (if (inline) inlineWidth else maxOf(text.width, metadata.width)).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (if (inline) text.height else text.height + 3.dp.roundToPx() + metadata.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            text.placeRelative(0, 0)
            metadata.placeRelative(width - metadata.width, if (inline) lineBottom - metadata.height else height - metadata.height)
        }
    }
}

@Composable
internal fun MessageReceiptMark(status: Int, overMedia: Boolean = false, modifier: Modifier = Modifier) {
    val label = when (status) {
        -1 -> uiString(R.string.ui_receipt_failed)
        0 -> uiString(R.string.ui_receipt_sending)
        1 -> uiString(R.string.ui_receipt_sent)
        2 -> uiString(R.string.ui_receipt_delivered)
        3 -> uiString(R.string.ui_receipt_read)
        else -> return
    }
    val accent = if (overMedia) Color(0xFF69DAB1) else MaterialTheme.colorScheme.primary
    val neutral = if (overMedia) Color(0xFFC4C9CD) else Color.Gray
    val error = if (overMedia) Color(0xFFFF9DA4) else MaterialTheme.colorScheme.error
    val size = Modifier.size(12.dp)
    Crossfade(status, modifier = modifier.size(16.dp, 12.dp).semantics { contentDescription = label },
        animationSpec = tween(160), label = "Delivery receipt") { shown ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (shown) {
                -1 -> Icon(Icons.Default.ErrorOutline, null, size, tint = error)
                0 -> Icon(Icons.Default.AccessTime, null, size, tint = neutral)
                1 -> Icon(Icons.Default.Check, null, size, tint = neutral)
                2 -> Icon(Icons.Default.Check, null, size, tint = accent)
                3 -> {
                    Icon(Icons.Default.Check, null, size.align(Alignment.CenterStart), tint = accent)
                    Icon(Icons.Default.Check, null, size.align(Alignment.CenterEnd), tint = accent)
                }
            }
        }
    }
}

@Composable
private fun SwipeReplySurface(enabled: Boolean, isMine: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1f else 1f
    val haptics = LocalHapticFeedback.current
    val replyLabel = uiString(R.string.ui_reply)
    val reply by rememberUpdatedState(onReply)
    var distance by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val triggerDistance = 50.dp
    val indicatorStart = 20.dp
    val shift by animateFloatAsState(
        if (enabled) distance else 0f,
        animationSpec = if (dragging && enabled) snap() else tween(180, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)),
        label = "Reply swipe"
    )
    val swipeModifier = if (enabled) Modifier.semantics {
        customActions = listOf(CustomAccessibilityAction(replyLabel) { reply(); true })
    }.pointerInput(direction, haptics) {
        val threshold = triggerDistance.toPx()
        val maxShift = 80.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var travel = 0f

            fun updateTravel(delta: Float) {
                val wasArmed = travel >= threshold
                travel = (travel + delta).coerceIn(0f, maxShift)
                distance = travel
                if (travel >= threshold && !wasArmed) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }

            try {
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    if (overSlop * direction > 0f) {
                        change.consume()
                        dragging = true
                        updateTravel(overSlop * direction)
                    }
                } ?: return@awaitEachGesture
                val released = horizontalDrag(drag.id) { change ->
                    updateTravel((change.position.x - change.previousPosition.x) * direction)
                    change.consume()
                }
                if (released && travel >= threshold) reply()
            } finally {
                dragging = false
                distance = 0f
            }
        }
    } else Modifier

    Box(swipeModifier) {
        Box(
            Modifier.graphicsLayer {
                translationX = direction * shift.coerceAtMost(size.width)
            }
        ) { content() }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val indicatorStartPx = with(density) { indicatorStart.toPx() }
        val threshold = with(density) { triggerDistance.toPx() }
        if (shift > indicatorStartPx) {
            val colors = MaterialTheme.colorScheme
            val progress = ((shift - indicatorStartPx) / (threshold - indicatorStartPx)).coerceIn(0f, 1f)
            val armed = (if (dragging && enabled) distance else shift) >= threshold
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).size(32.dp)
                    .graphicsLayer {
                        translationX = direction * (shift * (if (isMine) 0.5f else 1f) - size.width / 2)
                        alpha = (progress * 2).coerceAtMost(1f)
                        scaleX = 0.65f + 0.35f * progress
                        scaleY = scaleX
                    }
                    .background(if (armed) colors.primary else colors.surfaceVariant, CircleShape)
                    .drawBehind {
                        if (!armed) {
                            val inset = 1.dp.toPx()
                            drawArc(
                                colors.primary, -90f, 360f * progress, useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                                size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                                style = Stroke(width = 2 * inset)
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Reply,
                    contentDescription = null,
                    tint = if (armed) colors.onPrimary else colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).graphicsLayer { scaleX = -direction }
                )
            }
        }
    }
}

@Composable
private fun ChatMessageAvatar(avatarPath: String, visible: Boolean, modifier: Modifier) {
    if (!visible) {
        Spacer(modifier.width(34.dp))
        return
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.size(34.dp)) {
        if (avatarPath.isNotEmpty()) {
            androidx.compose.foundation.Image(
                painter = org.msgat.ui.components.rememberAvatarPainter(avatarPath),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Default.Person, contentDescription = null,
                modifier = Modifier.padding(7.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
internal fun ConversationMediaViewer(
    msg: MessageUIModel,
    progress: org.msgat.core.network.AttachmentProgress?,
    newer: (() -> Unit)?,
    older: (() -> Unit)?,
    photoOrigin: MediaPhotoOrigin? = null,
    returnFrame: MediaPhotoFrame? = null,
    onCloseStarted: () -> Unit = {},
    registerBackAction: (((() -> Unit)?) -> Unit)? = null,
    onShowMessage: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onOpenSharedMedia: (() -> Unit)? = null,
    conversationPeer: String? = null,
    onSaveAttachment: (() -> Unit)? = null,
    onExportAttachment: ((String, org.msgat.core.media.AttachmentMetadata) -> Unit)? = null,
    onRetryRefresh: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val moveNewer by rememberUpdatedState(newer)
    val moveOlder by rememberUpdatedState(older)
    var zoomed by remember(msg.id) { mutableStateOf(false) }
    val dragEnabled = !zoomed && msg.attachment?.let { it.isImage || it.isVideo } == true
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val swipe = if (dragEnabled && (newer != null || older != null)) {
        Modifier.mediaPagingGesture(msg.id, rtl, newer != null, older != null,
            newer = { moveNewer?.invoke() }, older = { moveOlder?.invoke() })
    } else Modifier
    MediaViewerFrame(msg.id, dragEnabled, onDismiss,
        photoOrigin = photoOrigin?.takeIf { it.reference == msg.content },
        returnBounds = returnFrame?.takeIf { it.reference == msg.content }?.bounds,
        onCloseStarted = onCloseStarted,
        registerBackAction = registerBackAction,
        media = { modifier, presentation, controlsVisible, toggleControls ->
        Box(modifier) {
            key(msg.id) { MediaViewerPage(msg, progress, active = true,
                presentation = presentation, pagingGesture = swipe, downloadPeer = conversationPeer,
                controlsVisible = controlsVisible, onToggleControls = toggleControls,
                onZoomChanged = { zoomed = it }) }
        }
    }) { chrome, close, fullScreen, toggleFullScreen ->
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .then(chrome),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth()) {
                if (newer != null || older != null) {
                    IconButton(enabled = newer != null, onClick = { newer?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, uiString(R.string.ui_media_newer), tint = if (newer != null) Color.White else Color.Gray)
                    }
                    IconButton(enabled = older != null, onClick = { older?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, uiString(R.string.ui_media_older), tint = if (older != null) Color.White else Color.Gray)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (conversationPeer != null) MediaPreviewActions(conversationPeer, msg, progress,
                    onShowMessage, onForward, onReply, onOpenSharedMedia, onExportAttachment)
                else onShowMessage?.let { ShowMediaMessageButton(it) }
                onSaveAttachment?.let { save ->
                    IconButton(onClick = save) { Icon(Icons.Default.FileDownload, uiString(R.string.ui_save_attachment), tint = Color.White) }
                }
                toggleFullScreen?.let { MediaFullScreenButton(fullScreen, it) }
                IconButton(onClick = close) { Icon(Icons.Default.Close, uiString(R.string.ui_close_media_preview), tint = Color.White) }
            }
            if (onRetryRefresh != null) {
                Surface(Modifier.padding(horizontal = 12.dp), shape = MaterialTheme.shapes.medium,
                    color = Color.Black.copy(alpha = 0.88f), contentColor = Color.White) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(uiString(R.string.ui_shared_media_load_failed), modifier = Modifier.weight(1f, fill = false))
                        TextButton(onClick = onRetryRefresh, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                            Text(uiString(R.string.ui_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumMediaViewer(
    items: List<MessageUIModel>,
    selectedId: String,
    progress: Map<String, org.msgat.core.network.AttachmentProgress>,
    peerUuid: String,
    photoOrigin: MediaPhotoOrigin?,
    photoFrames: Map<String, MediaPhotoFrame>,
    initiallyVerified: Boolean,
    verifyAttachment: suspend (String, String) -> Boolean,
    onVisibleItem: (MessageUIModel) -> Unit,
    onShowMessage: (String) -> Unit,
    onForward: (String) -> Unit,
    onReply: ((MessageUIModel) -> Unit)?,
    onOpenSharedMedia: (MessageUIModel) -> Unit,
    onExportAttachment: (String, org.msgat.core.media.AttachmentMetadata) -> Unit,
    onDismiss: () -> Unit
) {
    val membership = remember(items) { items.map { it.id } }
    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)) { items.size }
    val latestItems by rememberUpdatedState(items)
    val latestSelection by rememberUpdatedState(selectedId)
    val notifyVisible by rememberUpdatedState(onVisibleItem)
    val scope = rememberCoroutineScope()
    var appliedMembership by remember { mutableStateOf(membership) }
    val updatingPager = appliedMembership != membership
    val index = if (updatingPager) membership.indexOf(selectedId).coerceAtLeast(0)
        else pager.settledPage.coerceIn(items.indices)
    val currentItem = items[index]
    val thumbnails = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(currentItem.id, index) {
        val layout = thumbnails.layoutInfo
        val visible = layout.visibleItemsInfo.any { item ->
            item.index == index && item.offset >= layout.viewportStartOffset &&
                item.offset + item.size <= layout.viewportEndOffset
        }
        if (!visible) thumbnails.scrollToItem(index)
    }
    var zoomedPage by remember { mutableStateOf<String?>(null) }
    var pageMove by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    val initialItem = remember { items.firstOrNull { initiallyVerified && it.id == selectedId } }
    var initialVerification by remember { mutableStateOf(initialItem?.id) }
    val dragEnabled = !updatingPager && !pager.isScrollInProgress && zoomedPage != currentItem.id &&
        currentItem.attachment?.let { it.isImage || it.isVideo } == true
    fun moveTo(page: Int) {
        if (closing || updatingPager || page !in items.indices) return
        pageMove?.cancel()
        pageMove = scope.launch { pager.animateScrollToPage(page) }
    }
    LaunchedEffect(membership, closing) {
        if (closing || appliedMembership == membership) return@LaunchedEffect
        pageMove?.cancel()
        pager.scrollToPage(membership.indexOf(latestSelection).coerceAtLeast(0))
        appliedMembership = membership
    }
    LaunchedEffect(pager, membership, closing) {
        if (closing) return@LaunchedEffect
        snapshotFlow { latestItems.getOrNull(pager.settledPage).takeIf { appliedMembership == membership } }.collectLatest { current ->
            current?.let { item ->
                if (item.id != initialItem?.id) initialVerification = null
                notifyVisible(item)
            }
        }
    }
    MediaViewerFrame(currentItem.id, dragEnabled, onDismiss,
        photoOrigin = photoOrigin?.takeIf { it.reference == currentItem.content },
        returnBounds = photoFrames[currentItem.id]
            ?.takeIf { it.reference == currentItem.content && !pager.isScrollInProgress }?.bounds,
        onCloseStarted = {
            closing = true
            pageMove?.cancel()
        },
        media = { modifier, presentation, controlsVisible, toggleControls ->
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            modifier = modifier,
            userScrollEnabled = !closing && !updatingPager && zoomedPage != currentItem.id
        ) { page ->
            val message = items[page]
            val active = message.id == currentItem.id
            MediaViewerPage(message, progress[message.id], active = active,
                presentation = presentation.takeIf { active },
                playbackBottomPadding = if (items.size > 1) 58.dp else 0.dp, downloadPeer = peerUuid,
                controlsVisible = controlsVisible, onToggleControls = toggleControls,
                verifyAttachment = verifyAttachment.takeUnless {
                    initialVerification == message.id && initialItem?.content == message.content
                }, canVerifyAttachment = !closing) { zoomed ->
                if (zoomed) zoomedPage = message.id
                else if (zoomedPage == message.id) zoomedPage = null
            }
        }
    }) { chrome, close, fullScreen, toggleFullScreen ->
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().then(chrome).background(Color.Black.copy(alpha = 0.65f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (items.size > 1) {
                IconButton(enabled = index > 0 && !updatingPager, onClick = { moveTo(index - 1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, uiString(R.string.ui_media_previous_attachment), tint = if (index > 0) Color.White else Color.Gray)
                }
                Text("${index + 1} / ${items.size}", modifier = Modifier.weight(1f), color = Color.White)
                IconButton(enabled = index < items.lastIndex && !updatingPager, onClick = { moveTo(index + 1) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, uiString(R.string.ui_media_next_attachment), tint = if (index < items.lastIndex) Color.White else Color.Gray)
                }
            } else Spacer(Modifier.weight(1f))
            MediaPreviewActions(peerUuid, currentItem, progress[currentItem.id],
                onShowMessage = { onShowMessage(currentItem.id) },
                onForward = { onForward(currentItem.id) },
                onReply = onReply?.let { reply -> { reply(currentItem) } },
                onOpenSharedMedia = { onOpenSharedMedia(currentItem) },
                onExportAttachment = onExportAttachment)
            toggleFullScreen?.let { MediaFullScreenButton(fullScreen, it) }
            IconButton(onClick = close) { Icon(Icons.Default.Close, uiString(R.string.ui_close_media_preview), tint = Color.White) }
        }
        if (items.size > 1) androidx.compose.foundation.lazy.LazyRow(
            state = thumbnails,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(chrome)
                .background(Color.Black.copy(alpha = 0.75f))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            items(items.size, key = { items[it].id }) { page ->
                val item = items[page]
                val media by rememberAttachment(item, progress[item.id])
                val image by rememberAlbumThumbnail(media)
                val current = index == page
                val shape = RoundedCornerShape(5.dp)
                val label = uiString(R.string.ui_media_attachment_number, page + 1)
                val download = if (item.isMine) null else albumDownloadState(media, progress[item.id])
                Box(Modifier.size(48.dp).clip(shape).background(Color.DarkGray)
                    .then(if (current) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .semantics {
                        contentDescription = label
                        selected = current
                        if (download != null) stateDescription = download.first
                    }
                    .clickable { if (!current) moveTo(page) }, contentAlignment = Alignment.Center) {
                    image?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (download != null || image == null || media?.isVideo == true)
                        Icon(download?.second ?: if (media?.isVideo == true) Icons.Default.PlayArrow else Icons.Default.Image, null,
                            tint = Color.White, modifier = Modifier.size(19.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun albumDownloadState(
    media: org.msgat.core.media.AttachmentMetadata?,
    progress: org.msgat.core.network.AttachmentProgress?
): Pair<String, androidx.compose.ui.graphics.vector.ImageVector>? {
    if (media == null || media.available) return null
    return when {
        media.remoteUnavailable -> uiString(R.string.ui_download_relay_unavailable) to Icons.Default.Refresh
        media.downloadIntent == org.msgat.core.media.AttachmentDownloadIntent.PAUSED ->
            uiString(R.string.ui_download_paused) to Icons.Default.Pause
        progress?.phase == org.msgat.core.network.AttachmentPhase.DOWNLOADING && progress.total > 0 ->
            uiString(R.string.ui_album_download_progress,
                ((progress.bytes.toDouble() / progress.total).coerceIn(0.0, 1.0) * 100).toInt()) to Icons.Default.FileDownload
        progress?.phase == org.msgat.core.network.AttachmentPhase.RETRY ->
            uiString(R.string.ui_retry) to Icons.Default.Refresh
        media.downloadIntent == org.msgat.core.media.AttachmentDownloadIntent.REQUESTED ->
            uiString(R.string.ui_download_queued) to Icons.Default.AccessTime
        else -> uiString(R.string.ui_download_attachment) to Icons.Default.FileDownload
    }
}

@Composable
private fun ShowMediaMessageButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.ChatBubbleOutline, contentDescription = uiString(R.string.ui_show_in_chat), tint = Color.White)
    }
}

@Composable
private fun MediaPreviewActions(
    peer: String, item: MessageUIModel, progress: org.msgat.core.network.AttachmentProgress?,
    onShowMessage: (() -> Unit)?, onForward: (() -> Unit)?, onReply: (() -> Unit)?,
    onOpenSharedMedia: (() -> Unit)?,
    onExportAttachment: ((String, org.msgat.core.media.AttachmentMetadata) -> Unit)?
) {
    val media by rememberAttachment(item, progress)
    val store = org.msgat.MsgATApp.instance.nettyClient.objects
    val available = media?.takeIf { it.available }
    val allowed by produceState(false, peer, item.id, available?.reference) {
        value = false
        if (available != null) value = try { store.canExportMessage(peer, item.id, available.reference) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    val attachment = available?.takeIf { allowed && !item.isRevoked }
    val reply = onReply?.takeUnless { item.isRevoked }
    if (attachment == null && reply == null && onOpenSharedMedia == null) {
        onShowMessage?.let { ShowMediaMessageButton(it) }
        return
    }
    var expanded by remember(item.id, attachment?.reference) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, uiString(R.string.ui_media_actions), tint = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            onShowMessage?.let { show ->
                DropdownMenuItem(text = { Text(uiString(R.string.ui_show_in_chat)) },
                    onClick = { expanded = false; show() })
            }
            onOpenSharedMedia?.let { open ->
                DropdownMenuItem(text = { Text(uiString(R.string.ui_shared_media)) },
                    onClick = { expanded = false; open() })
            }
            reply?.let {
                DropdownMenuItem(text = { Text(uiString(R.string.ui_reply)) },
                    onClick = { expanded = false; it() })
            }
            if (attachment != null) {
                onForward?.let { forward ->
                    DropdownMenuItem(text = { Text(uiString(R.string.ui_forward)) },
                        onClick = { expanded = false; forward() })
                }
                if (onExportAttachment != null) DropdownMenuItem(text = { Text(uiString(R.string.ui_save_attachment)) },
                    onClick = { expanded = false; onExportAttachment(item.id, attachment) })
            }
        }
    }
}

@Composable
private fun MediaFullScreenButton(fullScreen: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(if (fullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            uiString(if (fullScreen) R.string.ui_media_exit_full_screen else R.string.ui_media_full_screen), tint = Color.White)
    }
}

@Composable
private fun MediaViewerPage(
    msg: MessageUIModel,
    progress: org.msgat.core.network.AttachmentProgress?,
    active: Boolean,
    presentation: MediaPhotoPresentation? = null,
    pagingGesture: Modifier = Modifier,
    playbackBottomPadding: Dp = 0.dp,
    downloadPeer: String? = null,
    verifyAttachment: (suspend (String, String) -> Boolean)? = null,
    canVerifyAttachment: Boolean = true,
    controlsVisible: Boolean = true,
    onToggleControls: () -> Unit = {},
    onZoomChanged: (Boolean) -> Unit = {}
) {
    var metadataRetry by remember(msg.id, msg.content) { mutableIntStateOf(0) }
    val lookup by rememberAttachmentRead(msg, progress, metadataRetry)
    val media = lookup.metadata
    val toggleControls by rememberUpdatedState(onToggleControls)
    val tap = Modifier.pointerInput(msg.id) { detectTapGestures { toggleControls() } }
    var zoomed by remember(msg.id) { mutableStateOf(false) }
    val needsVerification = verifyAttachment != null && !msg.isMine && media?.available == true
    var verification by remember(msg.id, msg.content, active, needsVerification) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(msg.id, msg.content, active, needsVerification, canVerifyAttachment) {
        if (active && needsVerification && canVerifyAttachment) {
            verification = verifyAttachment?.invoke(msg.id, msg.content)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val available = media?.takeIf { it.available && (!needsVerification || verification == true) }
        val caption = media?.caption?.takeIf { active && !zoomed && !msg.isRevoked && it.isNotBlank() }
        val captionHeight = (maxHeight * 0.28f).coerceAtMost(160.dp)
        val playable = available?.let { it.isVideo || it.isAudio } == true
        if (active && available == null) Box(Modifier.matchParentSize().then(tap))
        if (available?.isImage == true) {
            Box(Modifier.fillMaxSize().then(pagingGesture)) {
                ZoomableMediaImage(available, active, { value ->
                    zoomed = value
                    onZoomChanged(value)
                }, presentation, onToggleControls = { toggleControls() })
            }
        } else if (available?.let { it.isVideo || it.isAudio } == true) {
            if (active) key(msg.id) {
                VideoPlayer(media = available, surfaceGesture = tap.then(pagingGesture),
                    controlsBottomPadding = playbackBottomPadding, showControls = controlsVisible) {
                    if (caption != null) MediaPreviewCaption(caption, captionHeight)
                }
            }
            else Icon(Icons.Default.PlayArrow, "Video or audio", tint = Color.White, modifier = Modifier.size(42.dp))
        } else if (needsVerification && verification == null) {
            val thumbnail by rememberAlbumThumbnail(media)
            thumbnail?.let {
                androidx.compose.foundation.Image(it.asImageBitmap(), null,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (active && canVerifyAttachment) MediaPreviewLoading(uiString(R.string.ui_checking_attachment))
        } else if (active && downloadPeer != null && !msg.isMine && !msg.isRevoked && media?.available == false) {
            PreviewDownloadControls(downloadPeer, msg.id, media, progress)
        } else if (active && lookup.failed && media == null) {
            MediaPreviewRetry(message = uiString(R.string.ui_file_details_unavailable)) { metadataRetry++ }
        } else if (active && lookup.loading && media == null) {
            MediaPreviewLoading(uiString(R.string.ui_loading))
        } else Text(uiString(R.string.ui_media_playback_unavailable), color = Color.White)
        if (caption != null && !playable) {
            androidx.compose.animation.AnimatedVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.fadeIn(tween(180)), exit = androidx.compose.animation.fadeOut(tween(220))) {
                MediaPreviewCaption(caption, captionHeight, Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(bottom = playbackBottomPadding))
            }
        }
    }
}

@Composable
private fun MediaPreviewCaption(text: String, maxHeight: Dp, modifier: Modifier = Modifier) {
    key(text) {
        val shape = RoundedCornerShape(8.dp)
        Box(modifier.padding(horizontal = 12.dp, vertical = 4.dp).widthIn(max = 600.dp)
            .heightIn(max = maxHeight).clip(shape).background(Color.Black.copy(alpha = 0.88f))
            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            RichMessageText(text, color = Color.White, linkColor = Color(0xFF8CCAFF))
        }
    }
}

@Composable
private fun PreviewDownloadControls(peer: String, id: String, media: org.msgat.core.media.AttachmentMetadata,
                                    progress: org.msgat.core.network.AttachmentProgress?) {
    val paused = media.downloadIntent == org.msgat.core.media.AttachmentDownloadIntent.PAUSED
    val queued = media.downloadIntent == org.msgat.core.media.AttachmentDownloadIntent.REQUESTED
    val downloading = progress?.phase == org.msgat.core.network.AttachmentPhase.DOWNLOADING
    val retrying = progress?.phase == org.msgat.core.network.AttachmentPhase.RETRY
    val unavailable = media.remoteUnavailable || progress?.phase == org.msgat.core.network.AttachmentPhase.UNAVAILABLE
    val pause = !paused && !unavailable && !retrying && (queued || downloading)
    val scope = rememberCoroutineScope()
    var busy by remember(id) { mutableStateOf(false) }
    var failed by remember(id) { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(20.dp)) {
        if (downloading && !paused && progress != null && progress.total > 0) {
            val fraction = (progress.bytes.toDouble() / progress.total).coerceIn(0.0, 1.0).toFloat()
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.width(140.dp))
            Text(uiString(R.string.ui_album_download_progress, (fraction * 100).toInt()), color = Color.White)
        } else if (paused || unavailable || (queued && !retrying)) {
            Text(uiString(when {
                paused -> R.string.ui_download_paused
                unavailable -> R.string.ui_download_relay_unavailable
                else -> R.string.ui_download_queued
            }), color = Color.White)
        }
        if (failed) Text(uiString(R.string.ui_media_playback_unavailable), color = Color.White)
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { failed = !org.msgat.MsgATApp.instance.nettyClient.secureMessaging.requestAttachmentDownload(peer, id, !pause) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(uiString(when {
            pause -> R.string.ui_pause_download
            paused -> R.string.ui_resume_download
            unavailable || retrying -> R.string.ui_retry
            else -> R.string.ui_download_attachment
        })) }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(media: org.msgat.core.media.AttachmentMetadata, surfaceGesture: Modifier = Modifier,
                controlsBottomPadding: Dp = 0.dp, showControls: Boolean = true, caption: @Composable () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val calls = remember { org.msgat.core.call.CallManager.getInstance() }
    val callState by calls.callState.collectAsState()
    fun canPlayDuring(state: org.msgat.core.call.CallState): Boolean =
        state == org.msgat.core.call.CallState.IDLE || state == org.msgat.core.call.CallState.ENDED
    val playbackAllowed = canPlayDuring(callState)
    val exoPlayer = remember(media.reference) {
        val source = EncryptedPlaybackSource.Factory(org.msgat.MsgATApp.instance.nettyClient.objects, media)
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(source)).build().apply {
            val mediaItem = androidx.media3.common.MediaItem.Builder().setUri(media.reference).setMimeType(media.mime).build()
            setMediaItem(mediaItem)
            prepare()
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
        }
    }

    var isPlaying by remember(exoPlayer) { mutableStateOf(exoPlayer.isPlaying) }
    var currentPosition by remember(exoPlayer) { mutableStateOf(0L) }
    var totalDuration by remember(exoPlayer) { mutableStateOf(exoPlayer.duration.coerceAtLeast(0)) }
    var playbackFailed by remember(exoPlayer) { mutableStateOf(exoPlayer.playerError != null) }
    var retryingPlayback by remember(exoPlayer) { mutableStateOf(false) }
    var seekPosition by remember(exoPlayer) { mutableStateOf<Long?>(null) }
    var speed by remember(exoPlayer) { mutableFloatStateOf(1f) }
    var muted by remember(exoPlayer) { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumeWhenVisible by remember(exoPlayer) { mutableStateOf(true) }
    fun canPlayNow() = canPlayDuring(calls.callState.value) &&
        lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    DisposableEffect(exoPlayer, lifecycleOwner, calls) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                resumeWhenVisible = exoPlayer.playWhenReady && canPlayDuring(calls.callState.value)
                exoPlayer.pause()
            }

            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                if (resumeWhenVisible && canPlayNow()) exoPlayer.play()
                resumeWhenVisible = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(exoPlayer, calls) {
        calls.callState.collect { state ->
            if (!canPlayDuring(state)) {
                resumeWhenVisible = false
                exoPlayer.pause()
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                seekPosition = null
                playbackFailed = true
                retryingPlayback = false
            }
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                if (!playbackFailed && exoPlayer.playerError == null && !retryingPlayback) {
                    currentPosition = if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED)
                        totalDuration else exoPlayer.currentPosition
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0)
                    retryingPlayback = false
                } else if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    currentPosition = totalDuration
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, isPlaying, playbackFailed, retryingPlayback) {
        while (isActive && isPlaying && !playbackFailed && !retryingPlayback) {
            currentPosition = exoPlayer.currentPosition
            delay(100)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                androidx.media3.ui.PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.player = exoPlayer }
        )
        if (surfaceGesture != Modifier) Box(Modifier.matchParentSize().then(surfaceGesture))

        if (playbackFailed) {
            MediaPreviewRetry(Modifier.align(Alignment.Center), enabled = playbackAllowed) {
                if (!canPlayNow()) return@MediaPreviewRetry
                val position = currentPosition.coerceAtLeast(0)
                playbackFailed = false
                retryingPlayback = true
                exoPlayer.seekTo(position)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        } else if (retryingPlayback) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls, modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn(tween(180)), exit = androidx.compose.animation.fadeOut(tween(220))
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(bottom = controlsBottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            caption()
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(enabled = playbackAllowed && !playbackFailed && !retryingPlayback, onClick = {
                        if (!canPlayNow()) return@IconButton
                        if (isPlaying) {
                            exoPlayer.pause()
                        } else {
                            if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                                exoPlayer.seekTo(0)
                                currentPosition = 0
                            }
                            exoPlayer.play()
                        }
                    }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = uiString(if (isPlaying) R.string.ui_pause else R.string.ui_media_play),
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val position = seekPosition ?: currentPosition
                    Text("${mediaTime(position)} / ${mediaTime(totalDuration)}", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(enabled = !playbackFailed, onClick = {
                        muted = !muted
                        exoPlayer.volume = if (muted) 0f else 1f
                    }) {
                        Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            uiString(if (muted) R.string.ui_unmute else R.string.ui_mute), tint = Color.White)
                    }
                    MediaSpeedButton(speed, enabled = !playbackFailed, color = Color.White) { rate ->
                        speed = rate
                        exoPlayer.setPlaybackSpeed(rate)
                    }
                }
                val seekLabel = uiString(R.string.ui_audio_playback_position)
                Slider(
                    value = if (totalDuration > 0) ((seekPosition ?: currentPosition).toFloat() / totalDuration).coerceIn(0f, 1f) else 0f,
                    enabled = totalDuration > 0 && !playbackFailed && !retryingPlayback,
                    onValueChange = { seekPosition = (it * totalDuration).toLong().coerceIn(0, totalDuration) },
                    onValueChangeFinished = {
                        seekPosition?.let {
                            exoPlayer.seekTo(it)
                            currentPosition = it
                        }
                        seekPosition = null
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekLabel },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.Gray
                    )
                )
            }
        }
        }
    }
}

@Composable
internal fun MediaPreviewRetry(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    message: String? = null,
    retry: () -> Unit
) {
    Surface(modifier.padding(horizontal = 16.dp).widthIn(max = 360.dp),
        shape = MaterialTheme.shapes.medium, color = Color(0xFF222B27), contentColor = Color.White) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(message ?: uiString(R.string.ui_media_playback_unavailable),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(enabled = enabled, onClick = retry) { Text(uiString(R.string.ui_retry)) }
        }
    }
}

@Composable
internal fun MediaPreviewLoading(label: String, modifier: Modifier = Modifier) {
    Surface(modifier.padding(horizontal = 16.dp).widthIn(max = 360.dp),
        shape = MaterialTheme.shapes.medium, color = Color(0xEE222B27), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF8DDAB1), strokeWidth = 2.dp)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal val mediaPlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun mediaSpeedLabel(speed: Float): String = speed.toString().removeSuffix(".0") + "×"

@Composable
internal fun MediaSpeedButton(
    speed: Float, enabled: Boolean, color: Color, changeSpeed: (Float) -> Unit
) {
    var expanded by remember(enabled) { mutableStateOf(false) }
    val label = uiString(R.string.ui_media_playback_speed)
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label }) {
            Text(mediaSpeedLabel(speed), color = if (enabled) color else color.copy(alpha = 0.45f))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            for (rate in mediaPlaybackSpeeds) {
                DropdownMenuItem(
                    text = { Text(mediaSpeedLabel(rate)) },
                    modifier = Modifier.semantics { selected = rate == speed },
                    leadingIcon = { if (rate == speed) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        expanded = false
                        changeSpeed(rate)
                    }
                )
            }
        }
    }
}

private fun mediaTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@OptIn(ExperimentalFoundationApi::class)
private class ImagePasteLease(private var content: TransferableContent?) {
    fun release() { content = null }
}
