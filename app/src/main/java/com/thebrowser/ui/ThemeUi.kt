package com.thebrowser.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.core.model.ThemePresets
import com.thebrowser.core.model.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The browser's backdrop: shows behind the sidebar, toolbar and start page (web pages keep their own background). */
@Composable
fun ThemedBackground(vm: BrowserViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val t = state.settings.theme
    val ctx = LocalContext.current
    val base = MaterialTheme.colorScheme.background
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, t.imageUri, t.mode) {
        value = if (t.mode == "image" && t.imageUri != null) withContext(Dispatchers.IO) {
            runCatching {
                val u = Uri.parse(t.imageUri)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                var sample = 1; while (o.outWidth / sample > 1800 || o.outHeight / sample > 1800) sample *= 2
                ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }?.asImageBitmap()
            }.getOrNull()
        } else null
    }
    Box(modifier.background(base)) {
        when {
            t.useSpaceColour -> Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(state.activeSpace.colorArgb).copy(alpha = 0.35f), base))))
            t.mode == "color" -> Box(Modifier.matchParentSize().background(Color(t.colorArgb)))
            t.mode == "gradient" -> Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(t.gradientA), Color(t.gradientB)))))
            t.mode == "image" -> image?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop); Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = t.imageDim))) }
        }
    }
}

/** Translucent panel colour so the backdrop shows through when a custom look is active. */
@Composable
fun panelColor(vm: BrowserViewModel): Color {
    val state by vm.state.collectAsStateWithLifecycle()
    val t = state.settings.theme
    val s = MaterialTheme.colorScheme.surface
    return if (t.applyToSidebar && (t.mode != "default" || t.useSpaceColour)) s.copy(alpha = 0.72f) else s
}

@Composable
private fun Swatch(c: Long, selected: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c)).clickable(onClick = onClick), contentAlignment = Alignment.Center) { if (selected) Text("✓", color = if (ThemePresets.isDark(c)) Color.White else Color.Black) }

@Composable
fun AppearanceSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val t = state.settings.theme
    val context = LocalContext.current
    fun set(f: (ThemeSettings) -> ThemeSettings) = vm.updateSettings { it.copy(theme = f(it.theme)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }; set { s -> s.copy(mode = "image", imageUri = it.toString()) } }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Light, dark or system", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("system", "light", "dark").forEach { m -> FilterChip(state.settings.themeMode == m, { vm.updateSettings { it.copy(themeMode = m) } }, { Text(m.replaceFirstChar(Char::uppercase)) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Wallpaper colours (Material You)", Modifier.weight(1f)); Switch(state.settings.dynamicColor, { v -> vm.updateSettings { it.copy(dynamicColor = v) } }) }
            Text("Wallpaper colours replace the accent colour below on Android 12+. Turn them off to use your own accent.", fontSize = 11.sp)
        }
        item {
            Text("Background", fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("default" to "Default", "color" to "Colour", "gradient" to "Gradient", "image" to "Picture").forEach { (id, l) -> FilterChip(t.mode == id, { set { s -> s.copy(mode = id) } }, { Text(l) }) }
            }
        }
        if (t.mode == "color") item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { ThemePresets.colours.forEach { c -> Swatch(c, t.colorArgb == c) { set { s -> s.copy(colorArgb = c) } } } } }
        if (t.mode == "gradient") item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePresets.gradients.forEach { g -> Box(Modifier.size(56.dp, 34.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(g.a), Color(g.b)))).clickable { set { s -> s.copy(gradientA = g.a, gradientB = g.b) } }, contentAlignment = Alignment.Center) { if (t.gradientA == g.a && t.gradientB == g.b) Text("✓", color = Color.White) } }
            }
            Text("Gradients: " + ThemePresets.gradients.joinToString { it.name }, fontSize = 11.sp)
        }
        if (t.mode == "image") item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ picker.launch(arrayOf("image/*")) }) { Text(if (t.imageUri == null) "Choose picture" else "Change picture") }; if (t.imageUri != null) OutlinedButton({ set { s -> s.copy(mode = "default", imageUri = null) } }) { Text("Remove") } }
            Text("Darken: ${(t.imageDim * 100).toInt()}%", fontSize = 12.sp); Slider(t.imageDim, { v -> set { s -> s.copy(imageDim = v) } }, valueRange = 0f..0.8f)
            Text("The picture stays on this device and is only read by Hydrogen.", fontSize = 11.sp)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Tint the background with the active Space's colour", Modifier.weight(1f)); Switch(t.useSpaceColour, { v -> set { s -> s.copy(useSpaceColour = v) } }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Let the background show through the sidebar", Modifier.weight(1f)); Switch(t.applyToSidebar, { v -> set { s -> s.copy(applyToSidebar = v) } }) }
        }
        item {
            Text("Accent colour", fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(t.accentArgb == 0L, { set { s -> s.copy(accentArgb = 0L) } }, { Text("Follow Space") })
                ThemePresets.accents.forEach { c -> Swatch(c, t.accentArgb == c) { set { s -> s.copy(accentArgb = c) } } }
            }
            OutlinedButton({ vm.updateSettings { it.copy(theme = ThemeSettings()) } }) { Text("Reset appearance") }
        }
        item { TabsAndPageSettings(vm) }
    }
}
