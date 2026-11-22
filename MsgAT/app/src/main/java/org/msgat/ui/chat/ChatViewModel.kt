package org.msgat.ui.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import org.msgat.core.media.ObjectPreparation
import org.msgat.core.media.PendingPreparation
import org.msgat.core.media.PreparationProgress
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.paging.cachedIn
import androidx.paging.map
import org.msgat.core.network.NettyClient
import org.msgat.core.network.PeerActivityStatus
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.MsgDao
import org.msgat.data.db.ChatMessage
import org.msgat.data.db.Conversation
import org.msgat.data.db.ConversationDraft

data class DraftEditorState(val value: ConversationDraft, val reply: MessageUIModel?, val quotePreview: String?)

class ChatViewModel(
    private val targetUuid: String,
    private val msgDao: MsgDao,
    private val nettyClient: NettyClient,
    private val securePrefs: SecurePrefs,
    initialMessageId: String? = null
) : ViewModel() {

    private data class Preparing(val id:String,val owner:ObjectPreparation,val poll:kotlinx.coroutines.Job)
    private val preparations=linkedMapOf<String,Preparing>()
    private val _pendingPreparations=MutableStateFlow<List<PendingPreparation>>(emptyList())
    val pendingPreparations=_pendingPreparations.asStateFlow()
    private fun beginPreparation(
        fileCount: Int = 1,
        isSticker: Boolean = false,
        savingMessages: Boolean = false,
        isLinkPreview: Boolean = false
    ): Preparing {
        check(preparations.size<4){"Wait until an attachment has finished preparing"}
        val id=java.util.UUID.randomUUID().toString();val owner=ObjectPreparation()
        _pendingPreparations.update { it + PendingPreparation(id, owner.snapshot(), fileCount, isSticker, savingMessages, isLinkPreview) }
        val poll=viewModelScope.launch(Dispatchers.IO) {
            while(isActive){
                val next=owner.snapshot()
                _pendingPreparations.update{rows->rows.map{if(it.id==id && (next.version>it.progress.version || next.version==it.progress.version && next.completed>=it.progress.completed))it.copy(progress=next)else it}}
                kotlinx.coroutines.delay(200)
            }
        }
        return Preparing(id,owner,poll).also{preparations[id]=it}
    }
    private fun endPreparation(value:Preparing?){
        if(value==null)return
        value.poll.cancel();value.owner.close();preparations.remove(value.id)
        _pendingPreparations.update{rows->rows.filter{it.id!=value.id}}
    }
    fun cancelPreparation(id:String){preparations[id]?.let{it.owner.cancel();val next=it.owner.snapshot();_pendingPreparations.update{rows->rows.map{row->if(row.id==id)row.copy(progress=next)else row}}}}

    private val _sendOptions=MutableStateFlow(org.msgat.core.network.SendOptions())
    val sendOptions=_sendOptions.asStateFlow()
    private var sendOptionsRevision=0L
    fun setSendOptions(value:org.msgat.core.network.SendOptions){sendOptionsRevision++;_sendOptions.value=value}
    private fun captureSendOptions(value:org.msgat.core.network.SendOptions?=null):Pair<org.msgat.core.network.SendOptions,Long>{
        val options=value?:_sendOptions.value
        require(options.sendAt==null || options.sendAt>System.currentTimeMillis()){"Choose a future scheduled time"}
        return options to sendOptionsRevision
    }
    private fun completeSendOptions(revision:Long){if(sendOptionsRevision==revision)setSendOptions(org.msgat.core.network.SendOptions())}
    val syncState = nettyClient.connectionState
    val attachmentProgress = nettyClient.secureMessaging.attachmentProgress
    internal val peerActivity = nettyClient.secureMessaging.observePeerActivity(targetUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), PeerActivityStatus.NONE)
    fun chatActivity(active:Boolean,typing:Boolean){if(active)nettyClient.secureMessaging.setActiveChat(targetUuid,typing)else nettyClient.secureMessaging.clearActiveChat(targetUuid)}
    private var currentDraft: ConversationDraft? = null
    private val sendingDrafts = mutableSetOf<Long>()
    private val _textSendPending = MutableStateFlow(false)
    val textSendPending = _textSendPending.asStateFlow()
    private val _initialDraft = MutableStateFlow<DraftEditorState?>(null)
    val initialDraft = _initialDraft.asStateFlow()

    private data class HistoryRequest(val messageId: String?, val revision: Long = 0)
    private val historyTarget = MutableStateFlow(HistoryRequest(initialMessageId))
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messagesPagingData: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<MessageUIModel>> = historyTarget.flatMapLatest { target -> flow {
        val at = System.currentTimeMillis()
        val offset = target.messageId?.let { maxOf(0, msgDao.getMessageOffset(targetUuid, it, at) - 20) } ?: 0
        emitAll(androidx.paging.Pager(
            config = androidx.paging.PagingConfig(pageSize = 50, enablePlaceholders = false),
            initialKey=offset,
            pagingSourceFactory = { msgDao.getPagingMessages(targetUuid, System.currentTimeMillis()) }
        ).flow.map { pagingData ->
            val conversation = msgDao.getConversationByUuid(targetUuid)
            pagingData.map { row -> messageUi(row.message, conversation, row) }
        })
    }}.cachedIn(viewModelScope)

    fun revealMessage(id: String?) {
        historyTarget.update { HistoryRequest(id, it.revision + 1) }
    }
    suspend fun hasHistoryMessage(id: String, includeRevoked: Boolean): Boolean =
        msgDao.hasHistoryAnchor(targetUuid, id, includeRevoked)
    suspend fun newestMessageId(): String? = msgDao.getNewestMessageId(targetUuid)
    suspend fun latestEditableMessage(): MessageUIModel? =
        msgDao.latestEditableMessage(targetUuid)?.let { messageUi(it, conversation.value) }

    suspend fun adjacentReplyMessage(anchor: MessageUIModel?, newer: Boolean): MessageUIModel? =
        msgDao.adjacentReplyMessage(targetUuid, anchor?.timestamp, anchor?.id, newer)?.let { messageUi(it, conversation.value) }
    fun observeMessageReceipts(ids: List<String>) = msgDao.observeMessageReceipts(targetUuid, ids)
        .map { rows -> rows.associate { it.messageId to it.status } }
        .distinctUntilChanged()

    fun observeReplySource(id: String): kotlinx.coroutines.flow.Flow<MessageUIModel?> =
        msgDao.observeMessage(targetUuid, id).combine(nettyClient.objects.availability) { row, _ ->
            row?.let { messageUi(it, conversation.value) }
        }.distinctUntilChanged()

    internal data class PinnedPreview(
        val item: org.msgat.data.db.PinnedMessageItem,
        val anchor: org.msgat.data.db.PinnedMessageItem?,
        val next: org.msgat.data.db.PinnedMessageItem?,
        val canCycle: Boolean
    )

    private val pinnedPreviewAnchor = MutableStateFlow<org.msgat.data.db.PinnedMessageItem?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val pinnedPreview = pinnedPreviewAnchor.flatMapLatest { anchor ->
        val query = org.msgat.data.db.MessagePinsStore.query(targetUuid, securePrefs.myUuid, limit = 2, through = anchor)
        msgDao.observePinnedMessages(query).transform { rows ->
            val current = rows.firstOrNull()
            if (anchor != null && current?.id != anchor.id) {
                pinnedPreviewAnchor.compareAndSet(anchor, null)
            } else {
                emit(current?.let { item ->
                    val next = rows.getOrNull(1)?.takeIf {
                        it.timestamp < item.timestamp || (it.timestamp == item.timestamp && it.id < item.id)
                    }
                    PinnedPreview(item, anchor, next, rows.size > 1)
                })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    internal fun cyclePinnedPreview() {
        val current = pinnedPreview.value?.takeIf { it.canCycle } ?: return
        pinnedPreviewAnchor.compareAndSet(current.anchor, current.next)
    }
    suspend fun messagePinState(id:String):Boolean=msgDao.isMessagePinned(targetUuid,securePrefs.myUuid,id)
    suspend fun setMessagePinned(id:String,pinned:Boolean,notify:Boolean):Boolean =
        nettyClient.secureMessaging.setMessagePinned(targetUuid,id,pinned,notify)

    suspend fun messageAtDate(dayStart: Long, nextDayStart: Long): String? =
        msgDao.getMessageAtDate(targetUuid, dayStart, nextDayStart, System.currentTimeMillis())
    suspend fun firstUnreadMessage():String? = msgDao.getFirstUnreadId(targetUuid)

    private suspend fun messageUi(message: ChatMessage, conversation: Conversation?, history: MsgDao.HistoryRow? = null): MessageUIModel {
        val originalMine = if (history != null) history.originalMine else
            message.replyToId?.let { msgDao.getMessageById(it) }?.takeIf { it.conversationUuid == targetUuid }?.isMine
        val author = originalMine?.let { if (it) securePrefs.myUuid else targetUuid }
            ?: message.replyToSender?.takeIf { it == securePrefs.myUuid || it == targetUuid }
        val attachment = if (message.ext == "attachment" && message.isRevoked == 0) kotlinx.coroutines.withContext(Dispatchers.IO) {
            nettyClient.objects.metadata(message.content, message.messageId)
        } else null
        val presentation = when {
            message.isRevoked != 0 -> null
            history != null -> history.presentation
            else -> msgDao.getMessagePresentation(targetUuid, message.messageId)
        }
        val account = when {
            history != null -> history.contactAccount
            message.ext == "contact" && message.isRevoked == 0 -> msgDao.getContactCardAccount(targetUuid, message.messageId)
            else -> null
        }
        return MessageUIModel(message.messageId, message.isMine, message.content, message.ext, message.timestamp,
            if (message.isMine) securePrefs.myAvatarPath ?: "" else conversation?.avatarPath ?: "", message.isRead,
            message.replyToId, author, message.replyToContent, message.status,
            isRevoked = message.isRevoked == 1, isEdited = message.isEdited == 1, attachment = attachment,
            forwardInfo = presentation?.forwardInfo(), contactAccount = account, expiresAt = presentation?.expiresAt)
    }

    val conversation = msgDao.observeConversation(targetUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _webPreview=MutableStateFlow<org.msgat.core.network.WebPreviewDraft?>(null)
    val webPreview=_webPreview.asStateFlow()
    private val _previewEnabled=MutableStateFlow(false)
    val previewEnabled=_previewEnabled.asStateFlow()
    private var previewJob: kotlinx.coroutines.Job?=null
    private val _previewChoices = MutableStateFlow<List<String>>(emptyList())
    val previewChoices = _previewChoices.asStateFlow()
    private val _previewTarget = MutableStateFlow<String?>(null)
    val previewTarget = _previewTarget.asStateFlow()
    private val _previewAboveText = MutableStateFlow(false)
    val previewAboveText = _previewAboveText.asStateFlow()
    private val _previewCompactImage = MutableStateFlow(false)
    val previewCompactImage = _previewCompactImage.asStateFlow()
    private val _previewLoading = MutableStateFlow(false)
    val previewLoading = _previewLoading.asStateFlow()
    private var chosenPreview: String? = null
    private var removedPreview: String? = null
    fun setPreviewEnabled(value: Boolean) {viewModelScope.launch {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {org.msgat.data.db.WebPreviewStore.setEnabled(org.msgat.MsgATApp.instance.db.openHelper.writableDatabase,value)}
            _previewEnabled.value=value;updatePreview(currentDraft?.text?:"")
        } catch(_: Exception) {nettyClient.drafts.reportSaveFailure()}
    }}
    private fun clearPreview() {
        previewJob?.cancel()
        previewJob = null
        _previewLoading.value = false
        _webPreview.value?.clearImage()
        _webPreview.value = null
    }
    fun selectWebPreview(url: String) {
        if (!_previewEnabled.value || url !in _previewChoices.value) return
        chosenPreview = url
        removedPreview = null
        updatePreview(currentDraft?.text.orEmpty())
    }

    fun togglePreviewPlacement() {
        if (_previewTarget.value != null) _previewAboveText.value = !_previewAboveText.value
    }

    fun togglePreviewImageSize() {
        if (_webPreview.value?.image != null) _previewCompactImage.value = !_previewCompactImage.value
    }

    fun removeWebPreview() {
        removedPreview = _previewTarget.value ?: return
        updatePreview(currentDraft?.text.orEmpty())
    }

    fun retryWebPreview() {
        val url = _previewTarget.value ?: return
        if (_previewEnabled.value && !_previewLoading.value && _webPreview.value == null) {
            loadWebPreview(url)
        }
    }

    private fun updatePreview(text: String) {
        val choices = runCatching { nettyClient.webPreviews.candidates(text) }.getOrDefault(emptyList())
        _previewChoices.value = choices
        if (choices.isEmpty()) {
            _previewAboveText.value = false
            _previewCompactImage.value = false
        }
        chosenPreview = chosenPreview?.takeIf { it in choices }
        removedPreview = removedPreview?.takeIf { it in choices }
        val url = (chosenPreview ?: choices.firstOrNull()).takeIf { _previewEnabled.value && it != removedPreview }
        if (url == _previewTarget.value) return
        clearPreview()
        _previewTarget.value = url
        if (url == null) return
        loadWebPreview(url)
    }

    private fun loadWebPreview(url: String) {
        _previewLoading.value = true
        previewJob=viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            val result=nettyClient.webPreviews.fetchUrl(url)
            if(_previewTarget.value!=url || !kotlinx.coroutines.currentCoroutineContext().isActive){result?.clearImage();return@launch}
            _webPreview.value=result
            _previewLoading.value = false
        }
    }
    override fun onCleared() {nettyClient.secureMessaging.clearActiveChat(targetUuid);clearPreview();super.onCleared()}

    init {
        viewModelScope.launch {
            _previewEnabled.value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {org.msgat.data.db.WebPreviewStore.enabled(org.msgat.MsgATApp.instance.db.openHelper.readableDatabase)}
            val convo = msgDao.getConversationByUuid(targetUuid)
            try {
                val value = nettyClient.drafts.open(targetUuid)
                var reply = value.replyId?.let { msgDao.getMessageById(it) }
                    ?.takeIf { it.conversationUuid == targetUuid && it.isRevoked == 0 }
                val quotePreview = value.replyQuote?.let { selection ->
                    reply?.takeIf { it.ext == "txt" }?.let { source ->
                        if (canQuoteMessage(source.messageId, source.content))
                            withContext(Dispatchers.Default) { resolveReplyQuote(source.content, selection) }
                        else null
                    }
                }
                if (value.replyQuote != null && quotePreview == null) reply = null
                val normalized = nettyClient.drafts.update(value, value.text, reply?.messageId,
                    value.replyQuote?.takeIf { quotePreview != null })
                currentDraft = normalized
                _initialDraft.value = DraftEditorState(normalized, reply?.let { messageUi(it, convo) }, quotePreview)
                updatePreview(normalized.text)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { nettyClient.drafts.reportOpenFailure() }
        }
    }

    fun updateDraft(text: String, replyId: String?, replyQuote: String?) {
        val value = currentDraft ?: return
        try { currentDraft = nettyClient.drafts.update(value, text, replyId, replyQuote);updatePreview(text) }
        catch (_: Exception) { nettyClient.drafts.reportSaveFailure() }
    }

    internal suspend fun canQuoteMessage(id: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val db = org.msgat.MsgATApp.instance.db.openHelper.readableDatabase
        db.query(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT 1 FROM messages m WHERE m.messageId=? AND m.conversationUuid=? AND m.ext='txt' AND m.isRevoked=0
              AND m.content=? AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId)
            """.trimIndent(), arrayOf(id, targetUuid, text))).use { it.moveToFirst() }
    }

    private val pendingReadIds = mutableSetOf<String>()
    private val readCommitRevision = MutableStateFlow(0L)
    private val committedReadIds = LinkedHashSet<String>()
    private val _recentReadIds = MutableStateFlow<Set<String>>(emptySet())
    val recentReadIds = _recentReadIds.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val unreadCount = readCommitRevision.flatMapLatest { msgDao.observeUnreadCount(targetUuid) }
        .distinctUntilChanged()

    suspend fun markAsRead(visibleIds: List<String>): Boolean {
        val ids = visibleIds.take(100).filter { it !in committedReadIds && pendingReadIds.add(it) }
        if (ids.isEmpty()) return true
        return viewModelScope.async {
            try {
                val read = nettyClient.secureMessaging.markRead(targetUuid, ids) ?: return@async false
                if (read.isNotEmpty()) {
                    committedReadIds.addAll(read)
                    while (committedReadIds.size > 400) committedReadIds.remove(committedReadIds.first())
                    _recentReadIds.value = committedReadIds.toSet()
                    readCommitRevision.update { it + 1 }
                }
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ChatViewModel", "Could not update read status: ${error.javaClass.simpleName}")
                false
            }
            finally { ids.forEach { pendingReadIds.remove(it) } }
        }.await()
    }

    fun sendMessage(
        text: String,
        replyToId: String? = null,
        replyQuote: String? = null,
        options: org.msgat.core.network.SendOptions? = null,
        onSuccess: () -> Unit = {},
        onFail: () -> Unit
    ) {
        if (text.isBlank() || _textSendPending.value) return
        val selection=try{captureSendOptions(options)}catch(_:Exception){onFail();return}
        val before = currentDraft ?: return onFail()
        val value = try { nettyClient.drafts.update(before, text, replyToId, replyQuote).also { currentDraft = it } }
            catch (_: Exception) { onFail(); return }
        if (!sendingDrafts.add(value.revision)) return
        val preview = _webPreview.value
            ?.takeIf { draft -> draft.url == _previewTarget.value &&
                runCatching { draft.url in nettyClient.webPreviews.candidates(text) }.getOrDefault(false) }
            ?.let { it.copy(image = it.image?.copyOf(), aboveText = _previewAboveText.value, compactImage = _previewCompactImage.value) }
        _textSendPending.value = true
        viewModelScope.launch {
            var preparation: Preparing? = null
            try {
                if (preview?.image != null) preparation = beginPreparation(isLinkPreview = true)
                val commit = nettyClient.drafts.prepareSend(value)
                if (nettyClient.secureMessaging.sendText(
                        targetUuid, text, replyToId, commit, preview, selection.first, preparation = preparation?.owner
                    )) {
                    completeSendOptions(selection.second)
                    nettyClient.drafts.observeCommittedSend(commit)
                    if (commit.updatesDraft && currentDraft?.token == value.token && currentDraft?.revision == value.revision) {
                        currentDraft = value.copy(text = "", replyId = null, replyQuote = null, revision = commit.clearRevision)
                        updatePreview("")
                        onSuccess()
                    }
                } else if (preparation?.owner?.wasCancelled != true) onFail()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (preparation?.owner?.wasCancelled != true) onFail() }
            finally {
                preview?.clearImage()
                endPreparation(preparation)
                sendingDrafts.remove(value.revision)
                _textSendPending.value = false
            }
        }
    }

    // Import ciphertext once; the durable V2 bulk job runs independently of chat traffic.
    fun sendMedia(
        uris: List<Uri>,
        text: String,
        replyToId: String? = null,
        replyQuote: String? = null,
        onSuccess: () -> Unit,
        onFail: () -> Unit,
        onComplete: () -> Unit = {},
        choice:org.msgat.core.network.MediaSendChoice?=null,
        onResult: (org.msgat.core.media.PreparationSendResult) -> Unit = {},
        onPreparationStarted: (String) -> Unit = {}
    ) {
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var sendingRevision: Long? = null
            var preparation:Preparing?=null
            var result = org.msgat.core.media.PreparationSendResult.FAILED
            try {
                val selection=captureSendOptions(choice?.options)
                val before = currentDraft ?: error("The draft is unavailable")
                val value = nettyClient.drafts.update(before, text, replyToId, replyQuote).also { currentDraft = it }
                if (!sendingDrafts.add(value.revision)) { onFail(); return@launch }
                sendingRevision = value.revision
                val active = beginPreparation(fileCount = uris.size)
                preparation = active
                onPreparationStarted(active.id)
                val (saved, commit) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val commit = nettyClient.drafts.prepareSend(value, clearsText = false)
                    nettyClient.secureMessaging.sendMedia(
                        targetUuid, uris, replyToId, commit, selection.first, active.owner,
                        caption = choice?.caption.orEmpty(),
                        asFile = choice?.asFile == true,
                        asAlbum = choice?.asAlbum == true,
                        compressPhotos = choice?.compressPhotos == true,
                        compressVideos = choice?.compressVideos == true,
                        videoTrims = choice?.videoTrims.orEmpty(),
                        itemCaptions = choice?.itemCaptions.orEmpty()
                    ) to commit
                }
                if (saved) {
                    result = org.msgat.core.media.PreparationSendResult.QUEUED
                    completeSendOptions(selection.second)
                }
                if (!saved && !active.owner.wasCancelled) onFail()
                else if (saved && commit.updatesDraft && currentDraft?.token == value.token && currentDraft?.revision == value.revision) {
                    currentDraft = value.copy(replyId = null, replyQuote = null, revision = commit.clearRevision)
                    onSuccess()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if(preparation?.owner?.wasCancelled!=true){Log.w("ChatViewModel", "Media import failed: ${error.javaClass.simpleName}");onFail()}
            } finally {
                sendingRevision?.let { sendingDrafts.remove(it) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (preparation?.owner?.wasCancelled == true) result = org.msgat.core.media.PreparationSendResult.CANCELLED
                    endPreparation(preparation)
                    onComplete()
                    onResult(result)
                }
            }
        }
    }

    internal suspend fun sendSticker(id: String, text: String, replyId: String?, replyQuote: String?): org.msgat.core.media.PreparationSendResult {
        var revision: Long?=null
        var preparation:Preparing?=null
        try {
            val selection=captureSendOptions()
            val before=currentDraft?:return org.msgat.core.media.PreparationSendResult.FAILED
            val value = nettyClient.drafts.update(before, text, replyId, replyQuote).also { currentDraft = it }
            if(!sendingDrafts.add(value.revision))return org.msgat.core.media.PreparationSendResult.FAILED
            revision=value.revision
            val active=beginPreparation(isSticker = true);preparation=active
            val (saved,commit)=kotlinx.coroutines.withContext(Dispatchers.IO){
                val commit=nettyClient.drafts.prepareSend(value,clearsText=false)
                nettyClient.secureMessaging.sendSticker(targetUuid,id,replyId,commit,selection.first,active.owner) to commit
            }
            if(saved)completeSendOptions(selection.second)
            if (saved && commit.updatesDraft && currentDraft?.token == value.token && currentDraft?.revision == value.revision)
                currentDraft = value.copy(replyId = null, replyQuote = null, revision = commit.clearRevision)
            return if(saved)org.msgat.core.media.PreparationSendResult.QUEUED else if(active.owner.wasCancelled)org.msgat.core.media.PreparationSendResult.CANCELLED else org.msgat.core.media.PreparationSendResult.FAILED
        } catch(cancelled: kotlinx.coroutines.CancellationException){throw cancelled}
        catch(_: Exception){return if(preparation?.owner?.wasCancelled==true)org.msgat.core.media.PreparationSendResult.CANCELLED else org.msgat.core.media.PreparationSendResult.FAILED}
        finally {revision?.let {sendingDrafts.remove(it)};endPreparation(preparation)}
    }

    suspend fun sendContactCard(
        account: String, name: String, text: String, replyId: String?, replyQuote: String?, options: org.msgat.core.network.SendOptions
    ): Boolean = sendStructuredMessage(text, replyId, replyQuote, options) { commit, selected ->
        nettyClient.secureMessaging.sendContactCard(targetUuid, account, name, replyId, commit, selected)
    }

    suspend fun sendLocation(
        point: org.msgat.core.network.LocationPoint, label: String?, text: String, replyId: String?, replyQuote: String?,
        options: org.msgat.core.network.SendOptions
    ): Boolean = sendStructuredMessage(text, replyId, replyQuote, options) { commit, selected ->
        nettyClient.secureMessaging.sendLocation(targetUuid, point, label, replyId, commit, selected)
    }

    internal val liveLocationChanges = nettyClient.secureMessaging.liveLocationChanges
    internal val activeLiveLocations = nettyClient.liveLocations.active

    internal suspend fun readLiveLocation(id: String) = nettyClient.secureMessaging.liveLocation(targetUuid, id)

    internal suspend fun stopLiveLocation(id: String) = nettyClient.liveLocations.stop(targetUuid, id)

    internal suspend fun startLiveLocation(
        point: org.msgat.core.network.LocationPoint, duration: Long, label: String?, text: String,
        replyId: String?, replyQuote: String?, silent: Boolean
    ): Boolean = sendStructuredMessage(text, replyId, replyQuote, org.msgat.core.network.SendOptions(silent = silent)) { commit, options ->
        var started = false
        try {
            started = nettyClient.liveLocations.start(targetUuid, point, duration, label, replyId, commit, options.silent)
            started
        } finally {
            if (!started) commit.owner.observeFailedSend(commit)
        }
    }

    private suspend fun sendStructuredMessage(
        text: String, replyId: String?, replyQuote: String?, options: org.msgat.core.network.SendOptions,
        queue: suspend (org.msgat.data.db.DraftSendCommit, org.msgat.core.network.SendOptions) -> Boolean
    ): Boolean {
        var revision: Long? = null
        try {
            val selection = captureSendOptions(options)
            val before = currentDraft ?: return false
            val value = nettyClient.drafts.update(before, text, replyId, replyQuote).also { currentDraft = it }
            if (!sendingDrafts.add(value.revision)) return false
            revision = value.revision
            val commit = nettyClient.drafts.prepareSend(value, clearsText = false)
            if (!queue(commit, selection.first)) return false
            completeSendOptions(selection.second)
            if (commit.updatesDraft && currentDraft?.token == value.token && currentDraft?.revision == value.revision) {
                currentDraft = value.copy(replyId = null, replyQuote = null, revision = commit.clearRevision)
            }
            return true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } finally {
            revision?.let { sendingDrafts.remove(it) }
        }
    }

    fun revokeMessage(messageId: String) {
        viewModelScope.launch { nettyClient.secureMessaging.revokeMessage(targetUuid, messageId) }
    }

    fun saveMessages(ids: List<String>, onSuccess: () -> Unit, onFail: () -> Unit, onCancel: () -> Unit,
                     onStarted: (String) -> Unit) {
        val selected=ids.toList()
        viewModelScope.launch {
            var preparation: Preparing? = null
            try {
                val active = beginPreparation(savingMessages = true)
                preparation = active
                onStarted(active.id)
                withContext(NonCancellable) { nettyClient.actions.save(targetUuid, selected, active.owner) }
                if (isActive) onSuccess()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (isActive) onCancel()
                throw cancelled
            } catch (_: Exception) {
                if (isActive) {
                    if (preparation?.owner?.wasCancelled == true) onCancel() else onFail()
                }
            } finally {
                endPreparation(preparation)
            }
        }
    }
    suspend fun editMessage(messageId: String, newContent: String): Boolean =
        newContent.isNotBlank() && nettyClient.secureMessaging.editMessage(targetUuid, messageId, newContent)
}
