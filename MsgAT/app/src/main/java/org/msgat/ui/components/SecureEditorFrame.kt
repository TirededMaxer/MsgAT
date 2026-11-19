// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import org.msgat.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SecureEditorFrame(
    title: String,
    busy: Boolean,
    dismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = { if (!busy) dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn,
            dismissOnClickOutside = false,
            dismissOnBackPress = !busy
        )
    ) {
        val insets = WindowInsets.safeDrawing
        val colors = LocalMessagingColors.current
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            ) {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = dismiss, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiString(R.string.ui_cancel))
                        }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.chrome),
                    windowInsets = insets.only(WindowInsetsSides.Top)
                )
                HorizontalDivider(color = colors.divider)
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun EditorPreviewLayout(
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
    controls: @Composable () -> Unit
) {
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 480.dp && maxHeight < 360.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1.2f).fillMaxHeight(), contentAlignment = Alignment.Center) { preview() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)) { controls() }
            }
        } else {
            val controlsHeight = maxHeight * 0.55f
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { preview() }
                Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(scroll)) { controls() }
            }
        }
    }
}
