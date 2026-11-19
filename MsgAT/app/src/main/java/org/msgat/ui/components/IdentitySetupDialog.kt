// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.msgat.R
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentitySetupDialog(
    onDismiss: () -> Unit,
    initialNickname: String = "",
    initialAvatar: String? = null,
    editing: Boolean = false,
    onConfirm: (nickname: String, avatarBytes: ByteArray?, removeAvatar: Boolean, completion: (Boolean) -> Unit) -> Unit
) {
    var nickname by remember { mutableStateOf(initialNickname) }
    var removeAvatar by remember { mutableStateOf(false) }
    var avatarBytes by remember { mutableStateOf<ByteArray?>(null) }
    var cropSource by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) cropSource = it
    }
    val preview = remember(avatarBytes) {
        avatarBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    DisposableEffect(avatarBytes, preview) {
        val bytes = avatarBytes
        onDispose {
            bytes?.fill(0)
            preview?.recycle()
        }
    }

    val activeAvatar = initialAvatar.takeUnless { removeAvatar }
    val hasPhoto = preview != null || !activeAvatar.isNullOrBlank()
    val cleanNickname = nickname.trim()
    val nicknameTooLong = cleanNickname.toByteArray(Charsets.UTF_8).size > 256
    val canSave = cleanNickname.isNotEmpty() && !nicknameTooLong
    val colors = LocalMessagingColors.current
    fun save() {
        if (!canSave || saving) return
        saving = true
        failed = false
        onConfirm(cleanNickname, avatarBytes, removeAvatar) { saved ->
            saving = false
            failed = !saved
        }
    }
    val choosePhoto = uiString(if (hasPhoto) R.string.ui_profile_change_avatar else R.string.ui_profile_choose_avatar)

    Dialog(
        onDismissRequest = { if (editing && !saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn,
            dismissOnBackPress = editing && !saving, dismissOnClickOutside = editing && !saving)
    ) {
        Surface(
            Modifier.safeDrawingPadding().imePadding().widthIn(max = 430.dp).fillMaxWidth(0.94f),
            shape = MaterialTheme.shapes.large,
            color = colors.chrome
        ) {
            Column {
                Text(uiString(if (editing) R.string.ui_edit_profile else R.string.ui_profile_setup_title),
                    Modifier.padding(18.dp), style = MaterialTheme.typography.titleLarge)
                HorizontalDivider(color = colors.divider)
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        Modifier.size(96.dp).clip(CircleShape)
                            .semantics { contentDescription = choosePhoto }
                            .clickable(enabled = !saving, role = Role.Button) { picker.launch("image/*") },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        when {
                            preview != null -> Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            !activeAvatar.isNullOrBlank() -> Image(rememberAvatarPainter(activeAvatar), null,
                                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else -> Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PhotoCamera, null, Modifier.size(28.dp))
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { picker.launch("image/*") }, enabled = !saving) { Text(choosePhoto) }
                        if (hasPhoto) TextButton(onClick = {
                            avatarBytes = null
                            removeAvatar = true
                            failed = false
                        }, enabled = !saving) {
                            Text(uiString(R.string.ui_profile_remove_avatar), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(256); failed = false },
                        label = { Text(uiString(R.string.ui_profile_nickname)) },
                        singleLine = true,
                        enabled = !saving,
                        isError = nicknameTooLong,
                        supportingText = if (nicknameTooLong) {
                            { Text(uiString(R.string.ui_profile_nickname_too_long),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        } else null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (failed) Text(uiString(R.string.ui_profile_save_failed), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Start).semantics { liveRegion = LiveRegionMode.Polite })
                }
                HorizontalDivider(color = colors.divider)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End) {
                    if (editing) TextButton(onClick = onDismiss, enabled = !saving) { Text(uiString(R.string.ui_cancel)) }
                    Button(onClick = ::save, enabled = canSave && !saving) {
                        Text(uiString(if (saving) R.string.ui_saving else R.string.ui_save))
                    }
                }
            }
        }
    }
    cropSource?.let { source ->
        AvatarCropDialog(source, onDismiss = { cropSource = null }, onConfirm = { bytes ->
            avatarBytes = bytes
            removeAvatar = false
            failed = false
            cropSource = null
        })
    }
}
