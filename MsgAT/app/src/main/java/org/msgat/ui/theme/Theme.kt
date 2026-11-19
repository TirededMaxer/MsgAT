package org.msgat.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import java.util.Locale
import org.msgat.R

internal enum class AppThemeMode(@StringRes val label: Int) {
    SYSTEM(R.string.ui_follow_system), LIGHT(R.string.ui_light), DARK(R.string.ui_dark)
}

internal enum class AppLanguage(val tag: String?, @StringRes val label: Int) {
    SYSTEM(null, R.string.ui_follow_system),
    ENGLISH("en", R.string.ui_english),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.ui_simplified_chinese);

    fun resources(context: Context, configuration: Configuration = context.resources.configuration): Resources {
        val locale = tag?.let(Locale::forLanguageTag) ?: return context.resources
        return context.createConfigurationContext(Configuration(configuration).apply { setLocale(locale) }).resources
    }

    companion object {
        fun restore(context: Context): AppLanguage {
            val saved = appearancePreferences(context).getString("language", null)
            return entries.firstOrNull { it.name == saved } ?: SYSTEM
        }
    }
}

private fun appearancePreferences(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

internal class AppearanceSettings(context: Context) {
    private val preferences = appearancePreferences(context)
    var percentage by mutableIntStateOf(preferences.getInt("chat_text_percent", 100).takeIf { it in choices } ?: 100)
        private set
    var themeMode by mutableStateOf(preferences.getString("theme_mode", null)?.let { saved ->
        AppThemeMode.entries.firstOrNull { it.name == saved }
    } ?: AppThemeMode.SYSTEM)
        private set
    var language by mutableStateOf(AppLanguage.restore(context))
        private set
    var swipeToArchive by mutableStateOf(preferences.getBoolean("swipe_to_archive", false))
        private set

    fun apply(size: TextUnit): TextUnit = size * (percentage / 100f)

    fun update(value: Int) {
        if (value !in choices || value == percentage) return
        preferences.edit().putInt("chat_text_percent", value).apply()
        percentage = value
    }

    fun updateTheme(value: AppThemeMode) {
        if (value == themeMode) return
        preferences.edit().putString("theme_mode", value.name).apply()
        themeMode = value
    }

    fun updateLanguage(value: AppLanguage) {
        if (value == language) return
        preferences.edit().putString("language", value.name).apply()
        language = value
    }

    fun updateSwipeToArchive(enabled: Boolean) {
        preferences.edit().putBoolean("swipe_to_archive", enabled).apply()
        swipeToArchive = enabled
    }

    companion object {
        val choices = listOf(85, 100, 115, 130)
    }
}

internal val LocalAppearanceSettings = staticCompositionLocalOf<AppearanceSettings> { error("MsgAtTheme is required") }
private val LocalInterfaceResources = staticCompositionLocalOf<Resources?> { null }

internal data class MessagingColors(
    val timeline: Color,
    val chrome: Color,
    val sidebar: Color,
    val selection: Color,
    val divider: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color
)

private val LightMessagingColors = MessagingColors(
    timeline = Color(0xFFF6F8F7),
    chrome = Color.White,
    sidebar = Color(0xFFEDF2EF),
    selection = Color(0xFFE4EFE9),
    divider = Color(0xFFD5DFDA),
    incomingBubble = Color.White,
    outgoingBubble = Color(0xFFDCEFE4)
)

private val DarkMessagingColors = MessagingColors(
    timeline = Color(0xFF171E1B),
    chrome = Color(0xFF222B27),
    sidebar = Color(0xFF1C2521),
    selection = Color(0xFF2D4238),
    divider = Color(0xFF3E4E47),
    incomingBubble = Color(0xFF28332E),
    outgoingBubble = Color(0xFF204638)
)

internal val LocalMessagingColors = staticCompositionLocalOf { LightMessagingColors }

@Composable
internal fun interfaceLocale(): Locale =
    (LocalInterfaceResources.current ?: LocalContext.current.resources).configuration.locales[0]

@Composable
internal fun uiString(@StringRes id: Int, vararg arguments: Any): String {
    val resources = LocalInterfaceResources.current ?: LocalContext.current.resources
    return if (arguments.isEmpty()) resources.getString(id) else resources.getString(id, *arguments)
}

private val MsgAtDarkColors = darkColorScheme(
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E24),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFE2E8F0),
    primary = Color(0xFF53C792),
    onPrimary = Color(0xFF06271A),
    secondary = Color(0xFF3B82F6), 
    error = Color(0xFFEF4444),   
    secondaryContainer = Color(0xFF27272A),
    onSecondaryContainer = Color(0xFFF1F5F4),
    primaryContainer = Color(0xFF174C3B),
    onPrimaryContainer = Color(0xFFE3FAEE),
    surfaceVariant = Color(0xFF2C3432),
    onSurfaceVariant = Color(0xFFBCCAC3)
)

private val MsgAtLightColors = lightColorScheme(
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF172620),
    surface = Color.White,
    onSurface = Color(0xFF172620),
    surfaceVariant = Color(0xFFE8EFEB),
    onSurfaceVariant = Color(0xFF475951),
    primary = Color(0xFF127045),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEDD),
    onPrimaryContainer = Color(0xFF132F24),
    secondary = Color(0xFF385F53),
    secondaryContainer = Color(0xFFE8EEEB),
    onSecondaryContainer = Color(0xFF1D3028),
    outline = Color(0xFF758880),
    outlineVariant = Color(0xFFC8D4CE)
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
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val settings = remember(context) { AppearanceSettings(context.applicationContext) }
    val language = settings.language
    val localizedResources = remember(context, configuration, language) { language.resources(context, configuration) }
    val dark = when (settings.themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    CompositionLocalProvider(
        LocalAppearanceSettings provides settings,
        LocalInterfaceResources provides localizedResources,
        LocalMessagingColors provides if (dark) DarkMessagingColors else LightMessagingColors
    ) {
        val colors = if (dark) MsgAtDarkColors else MsgAtLightColors
        MaterialTheme(colorScheme = colors, typography = AppTypography) {
            Surface(Modifier.fillMaxSize(), color = colors.background, content = content)
        }
    }
}
