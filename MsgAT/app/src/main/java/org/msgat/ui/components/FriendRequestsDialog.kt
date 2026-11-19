// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.data.db.FriendRequest
import org.msgat.ui.theme.uiString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendRequestsDialog(
    requests: List<FriendRequest>,
    onDismiss: () -> Unit,
    onAccept: suspend (String) -> Boolean,
    onDecline: suspend (String) -> Boolean
) {
    val scope = rememberCoroutineScope()
    val pending = remember { mutableStateMapOf<String, Unit>() }
    var failed by remember { mutableStateOf(false) }

    fun decide(peer: String, accept: Boolean) {
        if (pending.containsKey(peer)) return
        pending[peer] = Unit
        failed = false
        scope.launch {
            try {
                if (!(if (accept) onAccept(peer) else onDecline(peer))) failed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                pending.remove(peer)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                TopAppBar(
                    title = { Text(uiString(R.string.ui_friend_requests)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, uiString(R.string.ui_close))
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
                if (failed) Text(uiString(R.string.ui_contact_action_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                HorizontalDivider()
                if (requests.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(uiString(R.string.ui_contact_no_requests), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(requests, key = { it.senderUuid }) { request ->
                            val busy = pending.containsKey(request.senderUuid)
                            ListItem(
                                headlineContent = { Text(request.nickname, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(request.senderUuid, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { ContactAvatar(request.avatarPath, request.nickname, 46) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (busy) {
                                            Box(Modifier.size(width = 96.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                            }
                                        } else {
                                            IconButton(onClick = { decide(request.senderUuid, true) }) {
                                                Icon(Icons.Default.Check, uiString(R.string.ui_contact_accept), tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(onClick = { decide(request.senderUuid, false) }) {
                                                Icon(Icons.Default.Close, uiString(R.string.ui_contact_decline), tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            )
                            HorizontalDivider(Modifier.padding(start = 76.dp))
                        }
                    }
                }
            }
        }
    }
}
