package org.msgat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val MsgAtColorScheme = darkColorScheme(
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E24),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFE2E8F0),
    primary = Color(0xFF10B981), 
    onPrimary = Color.White,
    secondary = Color(0xFF3B82F6), 
    error = Color(0xFFEF4444),   
    secondaryContainer = Color(0xFF27272A), 
    primaryContainer = Color(0xFF059669)    
)

val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )
)

@Composable
// Applies the custom material design color palette and typography to the app.
fun MsgAtTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MsgAtColorScheme,
        typography = AppTypography,
        content = content
    )
}