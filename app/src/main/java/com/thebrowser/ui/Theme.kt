package com.thebrowser.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel

/** Space colour choices (muted). Saved Spaces keep whatever colour they already have. */
val SpaceColors = listOf(0xFF0F7B76, 0xFF3F6FD1, 0xFF2E8B57, 0xFFB7791F, 0xFFC2570C, 0xFFB3261E, 0xFF7A4F9E, 0xFF5B6666)
val SpaceEmojis = listOf("🏠", "💼", "🎓", "🛒", "🎮", "🔬", "✈️", "💡")

private fun lightScheme(accent: Color): ColorScheme = lightColorScheme(
    primary = accent, onPrimary = Color.White,
    primaryContainer = lerp(Color.White, accent, 0.14f), onPrimaryContainer = lerp(accent, Color.Black, 0.5f),
    secondary = Color(0xFF5B6666), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EBEB), onSecondaryContainer = Color(0xFF121616),
    tertiary = accent, background = Color(0xFFF7F8F8), onBackground = Color(0xFF121616),
    surface = Color.White, onSurface = Color(0xFF121616), surfaceVariant = Color(0xFFEEF1F1), onSurfaceVariant = Color(0xFF5B6666),
    outline = Color(0xFFC9D1D1), outlineVariant = Color(0xFFE3E7E7),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFF7F8F8),
    surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFEEF1F1),
)

private fun darkScheme(accent: Color): ColorScheme {
    val a = lerp(accent, Color.White, 0.35f)
    return darkColorScheme(
        primary = a, onPrimary = Color(0xFF0B1514),
        primaryContainer = lerp(Color.Black, accent, 0.4f), onPrimaryContainer = Color(0xFFDDF2F0),
        secondary = Color(0xFFA3AFAF), onSecondary = Color(0xFF0F1212),
        secondaryContainer = Color(0xFF2A3030), onSecondaryContainer = Color(0xFFE6EAEA),
        tertiary = a, background = Color(0xFF0F1212), onBackground = Color(0xFFE6EAEA),
        surface = Color(0xFF161A1A), onSurface = Color(0xFFE6EAEA), surfaceVariant = Color(0xFF222828), onSurfaceVariant = Color(0xFFA3AFAF),
        outline = Color(0xFF465050), outlineVariant = Color(0xFF2B3232),
        surfaceContainerLowest = Color(0xFF0F1212), surfaceContainerLow = Color(0xFF161A1A), surfaceContainer = Color(0xFF1B2020),
        surfaceContainerHigh = Color(0xFF222828), surfaceContainerHighest = Color(0xFF2A3030),
    )
}

/**
 * Hydrogen theme: neutral surfaces, one accent, Roboto type scale, 12/16/20dp corners, standard (not bouncy) motion.
 * Accent order: the colour you pick in Appearance, then the Space colour if "tint by Space" is on, then Hydrogen teal.
 * Material You wallpaper colours are used only when you switch them on in Appearance.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TheBrowserTheme(vm: BrowserViewModel, content: @Composable () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dark = when (state.settings.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val t = state.settings.theme
    val accent = when {
        t.accentArgb != 0L -> Color(t.accentArgb)
        t.useSpaceColour -> Color(state.activeSpace.colorArgb)
        else -> HydrogenAccent
    }
    val scheme = when {
        state.settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkScheme(accent)
        else -> lightScheme(accent)
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.standard(), shapes = HydrogenShapes, typography = HydrogenTypography, content = content)
}
