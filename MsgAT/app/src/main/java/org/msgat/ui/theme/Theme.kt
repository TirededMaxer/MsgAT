package org.msgat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 纯黑极客配色
private val MsgAtColorScheme = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    primary = Color(0xFF00FF00), // 状态指示器的绿色
    error = Color(0xFFFF0000),   // 状态指示器的红色
    secondaryContainer = Color(0xFF2A2A2A), // 对方的气泡灰色
    primaryContainer = Color(0xFF00C853)    // 你的气泡绿色
)

@Composable
fun MsgAtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MsgAtColorScheme,
        content = content
    )
}