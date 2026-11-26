package com.thebrowser.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel

val SpaceColors = listOf(0xFF6C5CE7, 0xFF0984E3, 0xFF00B894, 0xFFFDCB6E, 0xFFE17055, 0xFFD63031, 0xFFE84393, 0xFF636E72)
val SpaceEmojis = listOf("🏠", "💼", "🎓", "🛒", "🎮", "🔬", "✈️", "💡")

/**
 * Material 3 Expressive theme (expressive motion scheme). Colours come from the wallpaper
 * (Material You dynamic colour, Android 12+) when enabled, otherwise from the active Space accent.
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
    val accent = Color(state.settings.theme.accentArgb.takeIf { it != 0L } ?: state.activeSpace.colorArgb)
    val scheme = when {
        state.settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = accent, onPrimary = Color.White, background = Color(0xFF121214), surface = Color(0xFF1B1B1F), surfaceVariant = Color(0xFF26262B))
        else -> lightColorScheme(primary = accent, onPrimary = Color.White, background = Color(0xFFF5F5F8), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEAEAF0))
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
