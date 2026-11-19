// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.msgat.R
import org.msgat.data.db.ConversationIndexStore
import org.msgat.ui.theme.uiString
import java.util.Date

private const val HOUR = 3_600_000L
private val muteDurations = listOf(
    R.string.ui_conversation_mute_one_hour to HOUR,
    R.string.ui_conversation_mute_eight_hours to 8 * HOUR,
    R.string.ui_conversation_mute_two_days to 48 * HOUR
)

@Composable
internal fun ConversationMuteMenuItems(muteUntil: Long, onChange: (String, Long?) -> Unit) {
    if (muteUntil > System.currentTimeMillis()) {
        val context = LocalContext.current
        val status = if (muteUntil == ConversationIndexStore.PERMANENT_MUTE) uiString(R.string.ui_conversation_muted)
        else {
            val date = Date(muteUntil)
            uiString(R.string.ui_conversation_muted_until,
                android.text.format.DateFormat.getMediumDateFormat(context).format(date),
                android.text.format.DateFormat.getTimeFormat(context).format(date))
        }
        DropdownMenuItem(text = { Text(status) }, onClick = {}, enabled = false)
        DropdownMenuItem(text = { Text(uiString(R.string.ui_unmute)) }, onClick = { onChange("unmute", null) })
        HorizontalDivider()
    }
    for ((label, duration) in muteDurations) {
        DropdownMenuItem(text = { Text(uiString(label)) }, onClick = { onChange("mute", System.currentTimeMillis() + duration) })
    }
    DropdownMenuItem(text = { Text(uiString(R.string.ui_conversation_mute_until_enabled)) }, onClick = { onChange("mute", null) })
}
