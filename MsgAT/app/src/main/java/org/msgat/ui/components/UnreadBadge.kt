// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun UnreadBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color(0xFFF22626), CircleShape)
            .layout { measurable, constraints ->
                val content = measurable.measure(constraints)
                val diameter = maxOf(content.width, content.height)
                layout(diameter, diameter) {
                    content.placeRelative((diameter - content.width) / 2, (diameter - content.height) / 2)
                }
            }
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
