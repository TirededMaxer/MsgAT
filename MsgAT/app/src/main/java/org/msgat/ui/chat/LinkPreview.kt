// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.msgat.core.media.AttachmentDownloadIntent
import org.msgat.R
import org.msgat.core.media.AttachmentMetadata
import org.msgat.core.network.AttachmentPhase
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.msgat.core.network.WebPreviewDraft
import org.msgat.data.db.WebPreviewSnapshot
import org.msgat.data.db.WebPreviewStore
import org.msgat.ui.theme.uiString

@Composable
internal fun LinkPreviewComposer(
    enabled: Boolean,
    choices: List<String>,
    target: String?,
    preview: WebPreviewDraft?,
    aboveText: Boolean,
    compactImage: Boolean,
    loading: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onTogglePlacement: () -> Unit,
    onToggleImageSize: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit
) {
    if (choices.isEmpty()) return
    val canChoose = enabled && (choices.size > 1 || target == null)
    val canEdit = enabled && target != null
    var choosing by remember(enabled, target) { mutableStateOf(false) }
    var actionsOpen by remember(enabled, target) { mutableStateOf(false) }
    val draft = preview?.takeIf { it.url == target }
    val imageSizeLabel = if (compactImage) R.string.link_preview_large_image else R.string.link_preview_small_image
    val placementLabel = if (aboveText) R.string.move_link_preview_below else R.string.move_link_preview_above
    val thumbnail by key(draft) {
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(null) {
            value = withContext(Dispatchers.Default) {
                draft?.image?.takeIf { it.size in 1..512 * 1024 }?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val compact = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.2f
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .toggleable(enabled, role = Role.Checkbox, onValueChange = onEnabledChange)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = enabled, onCheckedChange = null)
                    Text(uiString(R.string.link_previews), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (compact) {
                    if (canChoose || canEdit) Box {
                        IconButton(onClick = { actionsOpen = true }) {
                            Icon(Icons.Default.MoreVert, uiString(R.string.ui_link_preview_actions))
                        }
                        DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                            if (canChoose) MessageMenuItem(R.string.choose_link_preview, Icons.Default.Link) {
                                actionsOpen = false
                                choosing = true
                            }
                            if (canEdit) {
                                if (draft?.image != null) MessageMenuItem(imageSizeLabel,
                                    if (compactImage) Icons.Default.Fullscreen else Icons.Default.FullscreenExit) {
                                    actionsOpen = false
                                    onToggleImageSize()
                                }
                                MessageMenuItem(placementLabel,
                                    if (aboveText) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward) {
                                    actionsOpen = false
                                    onTogglePlacement()
                                }
                                MessageMenuItem(R.string.remove_link_preview, Icons.Default.Close) {
                                    actionsOpen = false
                                    onRemove()
                                }
                            }
                        }
                    }
                } else {
                    if (canChoose) IconButton(onClick = { choosing = true }) {
                        Icon(Icons.Default.Link, uiString(R.string.choose_link_preview))
                    }
                    if (canEdit) {
                        if (draft?.image != null) IconButton(onClick = onToggleImageSize) {
                            Icon(if (compactImage) Icons.Default.Fullscreen else Icons.Default.FullscreenExit,
                                uiString(imageSizeLabel))
                        }
                        IconButton(onClick = onTogglePlacement) {
                            Icon(if (aboveText) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                uiString(placementLabel))
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Default.Close, uiString(R.string.remove_link_preview))
                        }
                    }
                }
            }
        }
        val url = target
        if (enabled && url != null) {
            Column(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                PreviewSurface(modifier = Modifier.fillMaxWidth()) {
                    PreviewContents(android.net.Uri.parse(url).host ?: url,
                        draft?.title.orEmpty(), draft?.summary.orEmpty(), compactImage,
                        hasImage = thumbnail != null) { modifier ->
                        thumbnail?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) }
                    }
                    Text(url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 8.dp, bottom = 6.dp))
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                if (!loading && draft == null) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(uiString(R.string.link_preview_unavailable), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRetry) { Text(uiString(R.string.ui_retry)) }
                    }
                }
            }
        }
    }
    if (choosing && canChoose) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(uiString(R.string.choose_link_preview)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(choices, key = { it }) { url ->
                        ListItem(
                            headlineContent = { Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { RadioButton(selected = url == target, onClick = null) },
                            modifier = Modifier.clickable {
                                onSelect(url)
                                choosing = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text(uiString(R.string.ui_cancel)) }
            },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.Inherit)
        )
    }
}

/** Reads only the encrypted event's metadata and object. Never contacts the page on receive. */
@Composable
internal fun LinkPreviewMessage(
    id: String, text: String, saved: Boolean=false, canOpen: Boolean=true, downloadPeer: String? = null,
    content: @Composable () -> Unit
) {
    val app=org.msgat.MsgATApp.instance
    val row by key(id, text, saved) {
        produceState<WebPreviewSnapshot?>(null) {
            value = withContext(Dispatchers.IO) {
                try { WebPreviewStore.read(app.db.openHelper.readableDatabase, id, saved) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }
    }
    Column {
        val current = row
        if (current?.aboveText == true) LinkPreviewCard(id, current, canOpen, downloadPeer.takeUnless { saved })
        content()
        if (current?.aboveText == false) LinkPreviewCard(id, current, canOpen, downloadPeer.takeUnless { saved })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkPreviewCard(id: String, row: WebPreviewSnapshot, canOpen: Boolean, downloadPeer: String?) {
    val app=org.msgat.MsgATApp.instance
    val availability by app.nettyClient.objects.availability.collectAsState()
    val media by key(row.imageReference) {
        produceState<AttachmentMetadata?>(null, availability) {
            value = withContext(Dispatchers.IO) {
                try {
                    row.imageReference?.let { app.nettyClient.objects.metadata(it, id) }
                        ?.takeIf { it.size in 1..512L * 1024 && it.mime == "image/jpeg" }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = media?.takeIf { it.available }
    val reference = row.imageReference
    var coverReadFailed by remember(id, reference) { mutableStateOf(false) }
    LaunchedEffect(media?.available) {
        if (media?.available != true) coverReadFailed = false
    }
    val transfer by remember(id, reference, downloadPeer) {
        app.nettyClient.secureMessaging.previewProgress.map { progress ->
            progress[id]?.takeIf { downloadPeer != null && it.reference == reference }
        }.distinctUntilChanged()
    }.collectAsState(initial = null)
    var requesting by remember(id, reference) { mutableStateOf(false) }
    val unavailable = uiString(R.string.ui_preview_download_unavailable)
    val uriHandler = LocalUriHandler.current
    val openLabel = uiString(R.string.link_preview_open)
    val openFailed = uiString(R.string.link_preview_open_failed)
    val target = httpLink(row.url)
    var actions by remember(row.url, canOpen) { mutableStateOf(false) }
    val spacing = if (row.aboveText) Modifier.padding(bottom = 5.dp) else Modifier.padding(top = 5.dp)
    Column(spacing.widthIn(max = 276.dp)) {
        PreviewSurface(modifier = if (canOpen && target != null) Modifier.combinedClickable(role = Role.Button, onClickLabel = openLabel,
            onLongClick = { actions = true }, onClick = { openMessageLink(context, uriHandler, target, openFailed) }) else Modifier) {
            PreviewContents(android.net.Uri.parse(row.url).host ?: row.url, row.title, row.summary,
                row.compactImage, hasImage = image != null && !coverReadFailed) { modifier ->
                image?.let { EncryptedImage(it, modifier, thumbnailEdge = 512, onImageFailure = { coverReadFailed = true }) }
            }
        }
        val replacingCache = coverReadFailed && media?.available == true
        if (canOpen && downloadPeer != null && reference != null &&
            (replacingCache || media?.available == false && media.hasPendingDownload)) {
            val paused = media?.downloadIntent == AttachmentDownloadIntent.PAUSED
            val needsRetry = !paused && (replacingCache || media?.remoteUnavailable == true || transfer?.phase == AttachmentPhase.RETRY)
            val isQueued = !paused && !needsRetry && media?.downloadIntent == AttachmentDownloadIntent.REQUESTED
            val downloading = transfer?.takeIf { !paused && !needsRetry && it.phase == AttachmentPhase.DOWNLOADING }
            val canPause = isQueued || downloading != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canPause) {
                    Text(if (downloading != null) uiString(R.string.ui_album_download_progress,
                        (100 * downloading.bytes / downloading.total.coerceAtLeast(1)).coerceIn(0, 100).toInt())
                        else uiString(R.string.ui_download_queued),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !requesting, onClick = {
                    requesting = true
                    scope.launch {
                        try {
                            val changed = app.nettyClient.secureMessaging.setPreviewDownloadRequested(
                                downloadPeer, id, reference, requested = !canPause, replacingCache = replacingCache)
                            if (!changed) Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                        } finally { requesting = false }
                    }
                }) {
                    Text(uiString(when {
                        canPause -> R.string.ui_pause_download
                        paused -> R.string.ui_resume_download
                        needsRetry -> R.string.ui_retry
                        else -> R.string.ui_preview_download
                    }))
                }
            }
        }
        if (actions && canOpen && target != null) MessageLinksDialog(listOf(target)) { actions = false }
    }
}

@Composable
private fun PreviewSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.clip(RoundedCornerShape(8.dp)).then(modifier).background(colors.surfaceVariant.copy(alpha = 0.67f))) {
        Box(Modifier.matchParentSize()) {
            Spacer(Modifier.width(3.dp).fillMaxHeight().background(colors.primary))
        }
        Column(content = content)
    }
}

@Composable
private fun PreviewContents(host: String, title: String, summary: String, compact: Boolean, hasImage: Boolean,
                            image: @Composable (Modifier) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(host, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.primary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = LocalTextStyle.current.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                if (title.isNotEmpty()) Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (summary.isNotEmpty()) Text(summary, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (compact && hasImage) image(Modifier.size(54.dp).clip(RoundedCornerShape(5.dp)))
        }
        if (!compact && hasImage) image(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(5.dp)))
    }
}
