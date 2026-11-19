// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.msgat.MsgATApp
import org.msgat.R
import org.msgat.core.media.AttachmentMetadata
import org.msgat.ui.theme.uiString

private data class ExportRequest(
    val media: AttachmentMetadata,
    val allowed: suspend () -> Boolean
)

internal class AttachmentExport(
    val busy: Boolean,
    val save: (AttachmentMetadata, suspend () -> Boolean) -> Unit
)

@Composable
internal fun rememberAttachmentExport(screen: String? = null, onFailure: (() -> Unit)? = null): AttachmentExport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = MsgATApp.instance.nettyClient.objects
    val saved = uiString(R.string.ui_attachment_exported)
    val failed = uiString(R.string.ui_shared_media_save_failed)
    val failureHandler by rememberUpdatedState(onFailure)
    var pending by remember(screen) { mutableStateOf<ExportRequest?>(null) }
    var writing by remember(screen) { mutableStateOf<ExportRequest?>(null) }
    var job by remember(screen) { mutableStateOf<Job?>(null) }
    var canceling by remember(screen) { mutableStateOf(false) }
    var busy by remember(screen) { mutableStateOf(false) }
    val bytesWritten = remember(screen) { MutableStateFlow(0L) }

    fun showFailure() {
        failureHandler?.invoke() ?: Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
    }

    val createDocument = remember {
        object : ActivityResultContracts.CreateDocument("application/octet-stream") {
            override fun createIntent(context: Context, input: String): Intent {
                val intent = super.createIntent(context, input)
                MsgATApp.instance.securePrefs.attachmentExportFolder?.let { folder ->
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(folder.uri))
                }
                return intent
            }
        }
    }
    val picker = rememberLauncherForActivityResult(createDocument) { destination ->
        val request = pending
        pending = null
        if (destination == null || request == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        bytesWritten.value = 0
        canceling = false
        writing = request
        job = scope.launch {
            try {
                store.export(request.media.reference, destination, onProgress = { bytesWritten.value = it }, allowed = request.allowed)
                Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure()
            } finally {
                writing = null
                canceling = false
                busy = false
                job = null
            }
        }
    }
    writing?.let { request ->
        val written by bytesWritten.collectAsState()
        val cancel: () -> Unit = {
            if (!canceling) {
                canceling = true
                job?.cancel()
            }
        }
        AlertDialog(
            onDismissRequest = cancel,
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = false),
            title = { Text(uiString(if (canceling) R.string.ui_preparation_canceling else R.string.ui_saving)) },
            text = {
                Column {
                    if (request.media.size > 0) {
                        val fraction = (written.toDouble() / request.media.size).coerceIn(0.0, 1.0).toFloat()
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !canceling, onClick = cancel) { Text(uiString(R.string.ui_cancel)) }
            }
        )
    }
    return AttachmentExport(busy) { media, allowed ->
        if (!busy && media.available) {
            busy = true
            scope.launch {
                try {
                    check(allowed())
                    pending = ExportRequest(media, allowed)
                    picker.launch(media.filename)
                } catch (cancelled: CancellationException) {
                    pending = null
                    busy = false
                    throw cancelled
                } catch (_: Exception) {
                    pending = null
                    busy = false
                    showFailure()
                }
            }
        }
    }
}
