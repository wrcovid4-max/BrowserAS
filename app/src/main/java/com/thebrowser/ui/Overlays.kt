package com.thebrowser.ui

import android.annotation.SuppressLint
import android.speech.tts.TextToSpeech
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.BackHandler
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.thebrowser.PermissionPrompt
import com.thebrowser.core.model.InstalledExtension
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.ManageSection
import com.thebrowser.PendingSend
import com.thebrowser.RoutePrompt
import com.thebrowser.core.atc.AirTrafficControl
import com.thebrowser.core.reader.Reader
import com.thebrowser.core.reader.ReaderArticle
import java.util.Locale

private class Cmd(val label: String, val sub: String = "", val run: () -> Unit)

@Composable
fun CommandBar(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(q) { vm.suggest(q) }
    DisposableEffect(Unit) { onDispose { vm.suggestions = emptyList() } }
    fun close() { vm.commandBarOpen = false }

    val sugg = vm.suggestions
    val results = remember(q, state, sugg) {
        val l = q.trim().lowercase()
        val out = mutableListOf<Cmd>()
        if (l.isNotEmpty()) {
            out += Cmd("Go / search: $q", "Open in this tab") { vm.navigate(q); close() }
            out += Cmd("Open in new tab: $q", "Air Traffic Control applies") { vm.navigate(q, inNewTab = true); close() }
        }
        sugg.forEach { sg -> out += Cmd(sg, "Search suggestion") { vm.navigate(sg); close() } }
        val commands = listOf(
            Cmd("New tab") { vm.newTab(openCommandBar = false); close() },
            Cmd("Reopen closed tab") { vm.reopenClosed(); close() },
            Cmd("New private tab", "🕶") { vm.newTab(private = true); close() },
            Cmd("Reader view") { vm.openReader(); close() },
            Cmd("Tab switcher") { vm.captureThumbs(); vm.tabSwitcherOpen = true; close() },
            Cmd("Find in page") { vm.findOpen = true; close() },
            Cmd("Toggle sidebar") { vm.sidebarVisible = !vm.sidebarVisible; close() },
            Cmd("Open Gemini", "✦") { vm.geminiOpen = true; close() },
            Cmd("Air Traffic Control rules") { vm.manage = ManageSection.Routing; close() },
            Cmd("Bookmarks") { vm.manage = ManageSection.Bookmarks; close() },
            Cmd("History") { vm.manage = ManageSection.History; close() },
            Cmd("Settings") { vm.manage = ManageSection.Spaces; close() },
        )
        if (l.isNotEmpty()) out += commands.filter { it.label.lowercase().contains(l) }
        state.spaces.filter { l.isEmpty() || it.name.lowercase().contains(l) }.forEach { sp ->
            if (sp.id != state.activeSpaceId) out += Cmd("Switch to ${sp.emoji} ${sp.name}", "Space") { vm.switchSpace(sp.id); close() }
        }
        // Tab search
        state.tabs.filter { l.isNotEmpty() && (it.displayTitle.lowercase().contains(l) || it.url.lowercase().contains(l)) }.take(6).forEach { t ->
            out += Cmd(t.displayTitle, "Tab · ${state.spaces.firstOrNull { it.id == t.spaceId }?.name.orEmpty()}") { vm.selectTab(t.id); close() }
        }
        val pid = state.activeSpace.profileId
        state.bookmarks.filter { it.profileId == pid && l.isNotEmpty() && (it.title.lowercase().contains(l) || it.url.lowercase().contains(l)) }.take(4).forEach { b ->
            out += Cmd(b.title.ifBlank { b.url }, "Bookmark") { vm.navigate(b.url); close() }
        }
        state.history.filter { it.profileId == pid && l.isNotEmpty() && (it.title.lowercase().contains(l) || it.url.lowercase().contains(l)) }.take(4).forEach { h ->
            out += Cmd(h.title.ifBlank { h.url }, "History") { vm.navigate(h.url); close() }
        }
        if (l.isEmpty()) out += commands.take(6)
        out
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    placeholder = { Text("Search, enter address, or type a command") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.run?.invoke() }),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(results) { r ->
                        Column(Modifier.fillMaxWidth().clickable { r.run() }.padding(horizontal = 8.dp, vertical = 10.dp)) {
                            Text(r.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r.sub.isNotEmpty()) Text(r.sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RoutePromptDialog(vm: BrowserViewModel, p: RoutePrompt) {
    AlertDialog(
        onDismissRequest = { vm.resolveRoute("cancel") },
        title = { Text("Air Traffic Control") },
        text = {
            Text("${AirTrafficControl.hostOf(p.url) ?: p.url} is set to open in ${p.target.emoji} ${p.target.name}, which uses a different Profile " +
                "(separate logins and cookies). Switch there, or open it here in ${p.current.name} instead?")
        },
        confirmButton = { TextButton({ vm.resolveRoute("target") }) { Text("Switch to ${p.target.name}") } },
        dismissButton = {
            Row {
                TextButton({ vm.resolveRoute("here") }) { Text("Open here") }
                TextButton({ vm.resolveRoute("cancel") }) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun ConsentDialog(vm: BrowserViewModel, p: PendingSend) {
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = vm::cancelSend,
        title = { Text("Send to Gemini?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("This will be sent to Google's Gemini API:")
                p.items.forEach { Text("• ${it.label} (${it.text.length} characters)", fontSize = 13.sp) }
                Text("Nothing else from your browser is sent. Page content is attached to this message only.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dontAsk, { dontAsk = it })
                    Text("Don't ask again until I close the app", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton({ vm.confirmSend(dontAsk) }) { Text("Send") } },
        dismissButton = { TextButton(vm::cancelSend) { Text("Cancel") } },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderDialog(vm: BrowserViewModel, article: ReaderArticle) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val context = LocalContext.current
    var speaking by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val t = TextToSpeech(context) { status -> if (status != TextToSpeech.SUCCESS) vm.message = "Text-to-speech isn't available." }
        t.language = Locale.getDefault()
        tts = t
        onDispose { t.stop(); t.shutdown() }
    }
    fun toggleSpeak() {
        val t = tts ?: return
        if (speaking) { t.stop(); speaking = false; return }
        val max = TextToSpeech.getMaxSpeechInputLength() - 100
        article.text.chunked(max.coerceAtLeast(500)).forEachIndexed { i, chunk ->
            t.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "tb-$i")
        }
        speaking = true
    }

    Dialog(onDismissRequest = { vm.reader = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ vm.reader = null }) { Icon(Icons.Default.Close, "Close reader") }
                    Text("${article.minutes} min read", Modifier.weight(1f), fontSize = 13.sp)
                    TextButton({ vm.updateSettings { it.copy(readerFontSp = (it.readerFontSp - 2).coerceAtLeast(12)) } }) { Text("A−") }
                    TextButton({ vm.updateSettings { it.copy(readerFontSp = (it.readerFontSp + 2).coerceAtMost(32)) } }) { Text("A+") }
                    TextButton({ vm.updateSettings { it.copy(readerLineHeight = if (it.readerLineHeight >= 2f) 1.4f else it.readerLineHeight + 0.2f) } }) { Text("↕") }
                    TextButton({ vm.updateSettings { it.copy(readerMarginDp = if (it.readerMarginDp >= 48) 8 else it.readerMarginDp + 16) } }) { Text("↔") }
                    TextButton({ vm.updateSettings { it.copy(readerTheme = when (it.readerTheme) { "light" -> "sepia"; "sepia" -> "dark"; else -> "light" }) } }) {
                        Text(when (s.readerTheme) { "light" -> "☀"; "sepia" -> "📜"; else -> "🌙" })
                    }
                    TextButton(::toggleSpeak) { Text(if (speaking) "⏹" else "🔊") }
                }
                // Scripts are off in the reader: extracted HTML can never run code.
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                    }
                }, update = { w ->
                    w.loadDataWithBaseURL(article.baseUrl.ifBlank { null }, Reader.html(article, s), "text/html", "utf-8", null)
                }, onRelease = { it.destroy() })
            }
        }
    }
}


@Composable
fun FullscreenVideo(vm: BrowserViewModel, view: android.view.View) {
    BackHandler { vm.exitFullscreenVideo() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view
        }, modifier = Modifier.fillMaxSize(), onRelease = { (it.parent as? android.view.ViewGroup)?.removeView(it) })
        if (!vm.inPip) IconButton({ vm.exitFullscreenVideo() }, Modifier.align(Alignment.TopEnd).safeDrawingPadding()) {
            Icon(Icons.Default.Close, "Exit fullscreen", tint = Color.White)
        }
    }
}

/** Visual tab switcher with page thumbnails (captured when you leave a tab or the app). */
@Composable
fun TabSwitcher(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tabs = state.tabs.filter { it.spaceId == state.activeSpaceId || it.isPrivate }
    Dialog(onDismissRequest = { vm.tabSwitcherOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ vm.tabSwitcherOpen = false }) { Icon(Icons.Default.Close, "Close") }
                    Text("${state.activeSpace.emoji} ${state.activeSpace.name} · ${tabs.size} tabs", Modifier.weight(1f), fontSize = 18.sp)
                    TextButton({ vm.tabSwitcherOpen = false; vm.newTab() }) { Text("+ Tab") }
                    TextButton({ vm.tabSwitcherOpen = false; vm.newTab(private = true) }) { Text("+ Private") }
                }
                LazyVerticalGrid(GridCells.Adaptive(160.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    gridItems(tabs, key = { it.id }) { t ->
                        Card(Modifier.clickable { vm.tabSwitcherOpen = false; vm.selectTab(t.id) }) {
                            Column {
                                val hide = t.isPrivate && vm.privateLocked
                                val bmp = if (hide) null else vm.thumbs[t.id]
                                Box(Modifier.fillMaxWidth().height(110.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    else LetterChip(t.displayTitle, 40)
                                }
                                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (t.isPrivate) Text("🕶 ", fontSize = 12.sp)
                                    Text(if (hide) "Private tab (locked)" else t.displayTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                    IconButton({ vm.closeTab(t.id) }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Close tab", Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionDialog(p: PermissionPrompt) {
    val reasons = p.kinds.mapNotNull { com.thebrowser.core.model.PermissionKinds.of(it) }
    val host = AirTrafficControl.hostOf(p.origin) ?: p.origin
    AlertDialog(
        onDismissRequest = { p.decide("block") },
        title = { Text("Allow $host?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reasons.forEach { Text("• ${it.label}: this site ${it.reason}", fontSize = 13.sp) }
                Text(if (p.isPrivate) "Private tab: your choice lasts only until private sessions are cleared." else "Choose how long to allow it. You can change this later in Settings → Permissions.", fontSize = 12.sp)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton({ p.decide("once") }) { Text("Allow this time") }
                if (!p.isPrivate) TextButton({ p.decide("day") }) { Text("Allow for 24 hours") }
                TextButton({ p.decide("always") }) { Text("Always allow on this site") }
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton({ p.decide("block") }) { Text("Block") }
                TextButton({ p.decide("block_always") }) { Text("Always block on this site") }
            }
        },
    )
}

@Composable
fun ExtensionApprovalDialog(vm: BrowserViewModel, e: InstalledExtension) {
    // Specific sites start ON only if they are not "every website"; broad access must be chosen deliberately.
    var chosen by remember(e.id) { mutableStateOf(e.hostPatterns.filter { !com.thebrowser.core.ext.ExtensionManifest.isBroad(it) && (e.allowedPatterns.isEmpty() || it in e.allowedPatterns) }.toSet()) }
    var broadAck by remember(e.id) { mutableStateOf(false) }
    val broad = e.hostPatterns.filter(com.thebrowser.core.ext.ExtensionManifest::isBroad)
    AlertDialog(
        onDismissRequest = { vm.declineExtension() },
        title = { Text("Enable ${e.name}?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text("Version ${e.version} · Publisher: ${e.publisher.ifBlank { "not stated (self-declared, unverified)" }}", fontSize = 12.sp) }
                item { Text("Choose the sites it may read and change:", fontSize = 13.sp) }
                items(e.hostPatterns) { pat ->
                    val isBroad = pat in broad
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(pat in chosen, { on -> if (!isBroad || broadAck || !on) chosen = if (on) chosen + pat else chosen - pat else vm.message = "Tick the warning box first." })
                        Text(pat + if (isBroad) "  ⚠" else "", Modifier.padding(start = 8.dp), fontSize = 13.sp, color = if (isBroad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (broad.isNotEmpty()) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(broadAck, { broadAck = it })
                        Text("I understand “all websites” lets it read everything I see and type on every page, including banking and email.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (e.permissions.isNotEmpty()) item { Text("Requested permissions: ${e.permissions.joinToString()}", fontSize = 12.sp) }
                if (e.findings.isNotEmpty()) { item { Text("Security checks (heuristic, not a malware scan):", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }; items(e.findings.take(10)) { Text("• $it", fontSize = 12.sp) } }
                if (e.unsupported.isNotEmpty()) { item { Text("Not supported on Android WebView:", fontSize = 13.sp) }; items(e.unsupported) { Text("• $it", fontSize = 12.sp) } }
                item { Text("Only content scripts run, in the page's own context. It never runs in private tabs.", fontSize = 11.sp) }
            }
        },
        confirmButton = { TextButton({ vm.approveExtension(e.id, chosen.toList()) }, enabled = e.contentScriptCount > 0 && chosen.isNotEmpty()) { Text("Enable for chosen sites") } },
        dismissButton = { TextButton({ vm.declineExtension() }) { Text(if (e.granted) "Cancel" else "Remove") } },
    )
}
