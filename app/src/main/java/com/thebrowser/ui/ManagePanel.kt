package com.thebrowser.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent as AndroidIntent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.thebrowser.BrowserViewModel
import com.thebrowser.ManageSection
import com.thebrowser.core.ai.SecretStore
import com.thebrowser.core.util.SearchEngines
import com.thebrowser.core.sync.SyncScope
import kotlinx.coroutines.launch

@Composable
fun ManagePanel(vm: BrowserViewModel, section: ManageSection) {
    val sections = ManageSection.values()
    Dialog(onDismissRequest = { vm.manage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ vm.manage = null }) { Icon(Icons.Default.Close, "Close") }
                    Text(section.label, style = MaterialTheme.typography.headlineMedium)
                }
                ScrollableTabRow(sections.indexOf(section), edgePadding = 8.dp) {
                    sections.forEach { s -> Tab(s == section, { vm.manage = s }, text = { Text(s.label) }) }
                }
                Column(Modifier.fillMaxSize().padding(horizontal = HSpace.x4, vertical = HSpace.x3)) {
                    when (section) {
                        ManageSection.Security -> SecuritySection(vm)
                        ManageSection.Permissions -> PermissionsSection(vm)
                        ManageSection.Passwords -> PasswordsSection(vm)
                        ManageSection.Spaces -> SpacesSection(vm)
                        ManageSection.Profiles -> ProfilesSection(vm)
                        ManageSection.Routing -> RoutingSection(vm)
                        ManageSection.Bookmarks -> BookmarksSection(vm)
                        ManageSection.History -> HistorySection(vm)
                        ManageSection.Downloads -> DownloadsSection(vm)
                        ManageSection.Notes -> NotesSection(vm)
                        ManageSection.Reading -> ReadingSection(vm)
                        ManageSection.Extensions -> ExtensionsSection(vm)
                        ManageSection.Sync -> SyncSection(vm)
                        ManageSection.Privacy -> PrivacySection(vm)
                        ManageSection.Gemini -> GeminiSection(vm)
                        ManageSection.Ai -> AiSection(vm)
                        ManageSection.Agents -> AgentsSection(vm)
                        ManageSection.AgentCentre -> AgentCentreScreen(vm)
                        ManageSection.Appearance -> AppearanceSection(vm)
                        ManageSection.About -> AboutSection(vm)
                    }
                }
            }
        }
    }
}

@Composable private fun Label(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = HSpace.x6, bottom = HSpace.x2))

@Composable
private fun SpacesSection(vm: BrowserViewModel) = SpacesList(vm)

@Composable
private fun ProfilesSection(vm: BrowserViewModel) = ProfilesList(vm)

@Composable
private fun RoutingSection(vm: BrowserViewModel) = RulesList(vm)

@Composable
private fun BookmarksSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    var folderFor by remember { mutableStateOf<String?>(null) }
    val pid = state.activeSpace.profileId
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { o -> o.write(vm.exportBookmarksHtml().toByteArray()) }; vm.message = "Bookmarks exported." }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val html = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText().orEmpty()
            vm.message = "Imported ${vm.importBookmarksHtml(html)} new bookmarks."
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ importer.launch(arrayOf("text/html", "*/*")) }) { Text("Import (HTML)") }
        OutlinedButton({ exporter.launch("thebrowser-bookmarks.html") }) { Text("Export (HTML)") }
    }
    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Search bookmarks") }, singleLine = true)
    val list = state.bookmarks.filter { it.profileId == pid && (q.isBlank() || it.title.contains(q, true) || it.url.contains(q, true)) }
    LazyColumn(Modifier.padding(top = 8.dp)) {
        if (list.isEmpty()) item { Text("No bookmarks in this Profile.") }
        items(list, key = { it.id }) { b ->
            Row(Modifier.fillMaxWidth().clickable { vm.manage = null; vm.navigate(b.url, inNewTab = true) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.title.ifBlank { b.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(b.url, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton({ folderFor = b.id }) { Text(b.folder.ifBlank { "No folder" }, fontSize = 11.sp) }
                IconButton({ vm.removeBookmark(b.id) }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
    folderFor?.let { id ->
        NameDialog("Folder name (blank = none)", state.bookmarks.firstOrNull { it.id == id }?.folder.orEmpty(), "Save", { folderFor = null }) { vm.moveBookmarkToFolder(id, it); folderFor = null }
    }
}

@Composable
private fun HistorySection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    val pid = state.activeSpace.profileId
    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Search history") }, singleLine = true)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val now = System.currentTimeMillis()
        AssistChip({ vm.clearHistory(pid, now - 3_600_000L) }, { Text("Clear last hour") })
        AssistChip({ vm.clearHistory(pid, now - 86_400_000L) }, { Text("Clear 24 hours") })
        AssistChip({ vm.clearHistory(pid, now - 7 * 86_400_000L) }, { Text("Clear 7 days") })
        AssistChip({ vm.clearHistory(pid, null) }, { Text("Clear all") })
    }
    val list = state.history.filter { it.profileId == pid && (q.isBlank() || it.title.contains(q, true) || it.url.contains(q, true)) }.take(300)
    LazyColumn(Modifier.padding(top = 8.dp)) {
        items(list, key = { it.id }) { h ->
            Row(Modifier.fillMaxWidth().clickable { vm.manage = null; vm.navigate(h.url, inNewTab = true) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(h.url, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ vm.deleteHistory(h.id) }) { Icon(Icons.Default.Close, "Delete entry") }
            }
        }
    }
}

@Composable
private fun PrivacySection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val context = LocalContext.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Tracker blocking, fingerprinting, HTTPS and filter lists live in the Security tab. Site permissions have their own tab.", fontSize = 12.sp) }
        item { Label("Search engine") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchEngines.all.forEach { (id, v) -> FilterChip(s.searchEngine == id, { vm.updateSettings { it.copy(searchEngine = id) } }, { Text(v.first) }) }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Search suggestions (sends typing to your search engine)", Modifier.weight(1f)); Switch(s.searchSuggestions, { v -> vm.updateSettings { it.copy(searchSuggestions = v) } }) } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Open links from other apps in a Little window", Modifier.weight(1f)); Switch(s.littleWindowForExternal, { v -> vm.updateSettings { it.copy(littleWindowForExternal = v) } }) } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Wallpaper colours (Material You)", Modifier.weight(1f)); Switch(s.dynamicColor, { v -> vm.updateSettings { it.copy(dynamicColor = v) } }) } }
        item { Label("Private browsing") }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Lock private tabs when I leave the app (fingerprint, face or device PIN)", Modifier.weight(1f)); Switch(s.privateAutoLock, { v -> vm.updateSettings { it.copy(privateAutoLock = v) } }) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.lockPrivate() }) { Text("Lock now") }
                OutlinedButton({ vm.clearAllPrivate() }) { Text("Clear all private sessions") }
            }
            Text("Private tabs use a throw-away Profile that is deleted when the last one closes: no history, no saved cookies or site data, no saved permissions, no extensions, no sync, no password saving. It does NOT make you anonymous: websites, your network and your internet provider can still see what you do.", fontSize = 11.sp)
        }
        item { Label("Network speed in the toolbar") }
        item {
            val n = s.netSpeed
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("off" to "Off", "link" to "Wi-Fi link rate", "measured" to "Measured speed").forEach { (id, l) -> FilterChip(n.mode == id, { vm.updateSettings { it.copy(netSpeed = it.netSpeed.copy(mode = id)) } }, { Text(l) }) }
            }
            Text(when (n.mode) { "link" -> "Shows the rate your Wi-Fi radio negotiated with the router. It is a ceiling, not your real internet speed. No network requests."; "measured" -> "Downloads about 2 MB from ${n.testUrl.substringAfter("://").substringBefore('/')} every ${n.intervalSeconds} s to measure throughput. That server sees your IP address and uses data."; else -> "Off: nothing is measured." }, fontSize = 11.sp)
            if (n.mode != "off") Row(verticalAlignment = Alignment.CenterVertically) { Text("Only on Wi-Fi", Modifier.weight(1f)); Switch(n.wifiOnly, { v -> vm.updateSettings { it.copy(netSpeed = it.netSpeed.copy(wifiOnly = v)) } }) }
            if (n.mode == "measured") OutlinedTextField(n.testUrl, { v -> vm.updateSettings { it.copy(netSpeed = it.netSpeed.copy(testUrl = v.trim())) } }, Modifier.fillMaxWidth(), label = { Text("HTTPS test file URL") }, singleLine = true)
            if (vm.netSpeed.detail.isNotBlank() && n.mode != "off") Text(vm.netSpeed.detail, fontSize = 11.sp)
        }
        item { Label("Secure DNS") }
        item {
            Text("WebView uses the system resolver, so encrypted DNS (DNS-over-TLS/HTTPS) is set for the whole phone as Private DNS. The Security Check reports whether it is active on the current network.", fontSize = 12.sp)
            OutlinedButton({ runCatching { context.startActivity(AndroidIntent(AndroidSettings.ACTION_WIRELESS_SETTINGS)) } }) { Text("Open network settings") }
        }
        item { Label("Cookies (current Profile)") }
        item { OutlinedButton({ vm.clearCookies(state.activeSpace.profileId) }) { Text("Clear cookies") } }
        item { Label("Always on") }
        item { Text("Safe Browsing, mixed-content blocking, no file:// access, pop-up protection, and per-site prompts for camera, microphone and location.", fontSize = 12.sp) }
    }
}

@Composable
private fun GeminiSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(vm.secrets.get(SecretStore.GEMINI_KEY) != null) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var modelText by remember(s.geminiModel) { mutableStateOf(s.geminiModel) }
    Text("Your key is stored encrypted on this device (Android Keystore). It is never part of the app's source or APK.", fontSize = 12.sp)
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
        label = { Text(if (hasKey) "API key saved — paste to replace" else "Gemini API key") }, visualTransformation = PasswordVisualTransformation())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ vm.secrets.put(SecretStore.GEMINI_KEY, key.trim()); key = ""; hasKey = true; vm.message = "Key saved." }, enabled = key.isNotBlank()) { Text("Save key") }
        OutlinedButton({ vm.secrets.remove(SecretStore.GEMINI_KEY); hasKey = false }, enabled = hasKey) { Text("Remove key") }
    }
    Label("Model")
    OutlinedTextField(modelText, { modelText = it }, Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ vm.updateSettings { it.copy(geminiModel = modelText.trim().ifBlank { it.geminiModel }) } }) { Text("Use this model") }
        OutlinedButton({
            loading = true
            vm.viewModelScope.launch {
                runCatching { vm.geminiClient.listModels() }.onSuccess { models = it }.onFailure { vm.message = it.message }
                loading = false
            }
        }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh models") }
    }
    Text("Default: gemini-3.8-flash (latest Flash per Google's docs).", fontSize = 11.sp)
    LazyColumn(Modifier.heightIn(max = 220.dp)) {
        items(models) { m -> Text(m, Modifier.fillMaxWidth().clickable { modelText = m; vm.updateSettings { it.copy(geminiModel = m) } }.padding(vertical = 8.dp), fontSize = 14.sp) }
    }
    Label("Translate target language")
    OutlinedTextField(s.translateTarget, { v -> vm.updateSettings { it.copy(translateTarget = v) } }, Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
private fun AboutSection(vm: BrowserViewModel) {
    Text("TheBrowser 0.2.0", fontSize = 18.sp)
    Text("Active engine: ${vm.engine.displayName}", Modifier.padding(top = 6.dp))
    EngineSwitch(vm)
    Text(if (vm.engine.supportsIsolatedProfiles) "Profile isolation: supported" else "Profile isolation: NOT supported on this WebView", fontSize = 13.sp)
    Label("Not built yet")
    Text("Chrome extensions beyond content scripts (background workers, popups, tabs/webRequest APIs), account-based sync, encrypted DNS inside the browser (not possible in WebView), offline images. See ASSESSMENT.md.", fontSize = 13.sp)
}


@Composable
private fun DownloadsSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, AndroidIntent.FLAG_GRANT_READ_URI_PERMISSION or AndroidIntent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.updateSettings { s -> s.copy(downloadTreeUri = it.toString()) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Open PDFs in Hydrogen's viewer (view, mark up, fill forms, sign, save a copy)", Modifier.weight(1f), fontSize = 13.sp); Switch(state.settings.openPdfInViewer, { v -> vm.updateSettings { it.copy(openPdfInViewer = v) } }) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (state.settings.downloadTreeUri == null) "Saved to: Downloads" else "Saved to: your chosen folder", Modifier.weight(1f), fontSize = 13.sp)
        TextButton({ picker.launch(null) }) { Text("Choose folder") }
        if (state.settings.downloadTreeUri != null) TextButton({ vm.updateSettings { it.copy(downloadTreeUri = null) } }) { Text("Reset") }
    }
    LazyColumn {
        if (vm.downloads.items.isEmpty()) item { Text("No downloads yet.") }
        items(vm.downloads.items.toList(), key = { it.id }) { d ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(d.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d.status == "running" || d.status == "paused") {
                    if (d.total > 0) LinearProgressIndicator(progress = { (d.received.toFloat() / d.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("${d.status}${d.error?.let { " — $it" }.orEmpty()} · ${d.received / 1024} KB${if (d.total > 0) " of ${d.total / 1024} KB" else ""}", fontSize = 11.sp)
                Row {
                    when (d.status) {
                        "running" -> TextButton({ vm.downloads.pause(d.id) }) { Text("Pause") }
                        "paused", "failed" -> TextButton({ vm.downloads.resume(d.id) }) { Text("Resume") }
                        "done" -> d.savedUri?.let { u ->
                            TextButton({
                                runCatching {
                                    context.startActivity(AndroidIntent(AndroidIntent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(u), d.mime ?: "*/*").addFlags(AndroidIntent.FLAG_GRANT_READ_URI_PERMISSION))
                                }.onFailure { vm.message = "No app can open this file." }
                            }) { Text("Open") }
                        }
                    }
                    if (d.status == "running" || d.status == "paused") TextButton({ vm.downloads.cancel(d.id) }) { Text("Cancel") }
                    TextButton({ vm.downloads.remove(d.id) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun NotesSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pid = state.activeSpace.profileId
    var editing by remember { mutableStateOf<String?>(null) }
    var newNote by remember { mutableStateOf(false) }
    val note = state.notes.firstOrNull { it.id == editing }
    if (note != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ editing = null }) { Text("‹ Notes") }
            Spacer(Modifier.weight(1f))
            TextButton({ vm.clipToNote(note.id) }) { Text("Clip current page") }
            TextButton({ vm.deleteNote(note.id); editing = null }) { Text("Delete") }
        }
        OutlinedTextField(note.title, { v -> vm.updateNote(note.id) { it.copy(title = v) } }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
        OutlinedTextField(note.body, { v -> vm.updateNote(note.id) { it.copy(body = v) } }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp), label = { Text("Notes") })
        Label("Research board — ${note.clips.size} clip(s)")
        LazyColumn {
            items(note.clips) { cl ->
                Column(Modifier.fillMaxWidth().clickable { vm.manage = null; vm.navigate(cl.url, inNewTab = true) }.padding(vertical = 6.dp)) {
                    Text(cl.title.ifBlank { cl.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cl.text.isNotBlank()) Text("“${cl.text.take(160)}”", fontSize = 12.sp)
                    Text(cl.url, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    } else {
        Button({ newNote = true }) { Text("New note") }
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(state.notes.filter { it.profileId == pid }, key = { it.id }) { n ->
                Column(Modifier.fillMaxWidth().clickable { editing = n.id }.padding(vertical = 8.dp)) {
                    Text(n.title, fontSize = 16.sp)
                    Text("${n.clips.size} clips · ${n.body.take(60)}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (newNote) NameDialog("New note", "", "Create", { newNote = false }) { editing = vm.addNote(it); newNote = false }
}

@Composable
private fun ReadingSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pid = state.activeSpace.profileId
    Text("Articles saved with “Save for offline reading”. Text opens offline; images need a connection.", fontSize = 12.sp)
    LazyColumn(Modifier.padding(top = 8.dp)) {
        val list = state.articles.filter { it.profileId == pid }
        if (list.isEmpty()) item { Text("Nothing saved yet.") }
        items(list, key = { it.id }) { a ->
            Row(Modifier.fillMaxWidth().clickable { vm.manage = null; vm.openSaved(a.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.wordCount} words · ${AirTrafficControlHost.of(a.url)}", fontSize = 11.sp)
                }
                IconButton({ vm.deleteSaved(a.id) }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

private object AirTrafficControlHost { fun of(url: String) = com.thebrowser.core.atc.AirTrafficControl.hostOf(url) ?: url }

@Composable
private fun ExtensionsSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::installExtension) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(
                "Chrome-style extensions are added as an unpacked ZIP (the extension folder zipped, with manifest.json). Nothing installs silently. " +
                    "Android WebView has no extension host, so ONLY content scripts run (plus a small chrome.storage.local shim). Background workers, popups, tabs/webRequest APIs and Chrome Web Store installs are not supported. " +
                    "Each extension only runs on the sites you switch on, never in private tabs. Adding a newer ZIP of the same extension updates it and keeps your approvals unless it asks for new access.",
                fontSize = 12.sp,
            )
            if (!vm.engine.supportsContentScripts) Text("This WebView can't inject start-of-page scripts, so extensions are unavailable until Android System WebView is updated.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Button({ picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }, Modifier.padding(vertical = 8.dp), enabled = vm.engine.supportsContentScripts) { Text("Add / update extension (.zip)") }
        }
        items(state.extensions, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.name} ${e.version}", Modifier.weight(1f), fontSize = 15.sp)
                        Switch(e.enabled, { vm.toggleExtension(e.id) }, enabled = e.granted && e.allowedPatterns.isNotEmpty())
                    }
                    Text("Publisher: ${e.publisher.ifBlank { "not stated" }} (self-declared in the extension, not verified)" + if (e.homepage.isNotBlank()) " · ${e.homepage}" else "", fontSize = 11.sp)
                    if (e.permissions.isNotEmpty()) Text("Permissions: ${e.permissions.joinToString()}", fontSize = 11.sp)
                    if (e.optionalPermissions.isNotEmpty()) Text("Optional (not grantable here): ${e.optionalPermissions.joinToString()}", fontSize = 11.sp)
                    if (e.findings.isNotEmpty()) { Text("Security checks (heuristic, not a malware scan):", fontSize = 11.sp, color = MaterialTheme.colorScheme.error); e.findings.take(8).forEach { Text("• $it", fontSize = 11.sp) } }
                    if (e.unsupported.isNotEmpty()) Text("Won't work: ${e.unsupported.joinToString("; ")}", fontSize = 11.sp)
                    Text("Sites it may run on:", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    if (e.hostPatterns.isEmpty()) Text("(none requested)", fontSize = 11.sp)
                    e.hostPatterns.forEach { pat ->
                        val broad = com.thebrowser.core.ext.ExtensionManifest.isBroad(pat)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(pat in e.allowedPatterns, { on -> vm.setExtensionPatterns(e.id, if (on) e.allowedPatterns + pat else e.allowedPatterns - pat) }, enabled = e.granted)
                            Text(pat + if (broad) "  ⚠ every website" else "", Modifier.padding(start = 8.dp), fontSize = 12.sp, color = if (broad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Row {
                        if (!e.granted) TextButton({ vm.extensionToApprove = e }) { Text("Review & enable") }
                        TextButton({ vm.removeExtension(e.id) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncSection(vm: BrowserViewModel) {
    val context = LocalContext.current
    var scope by remember { mutableStateOf(SyncScope()) }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { o -> o.write(vm.exportSync(scope, pass).toByteArray()) }; status = "Exported." }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { status = vm.importSync(context.contentResolver.openInputStream(it)?.bufferedReader()?.readText().orEmpty(), pass) }
    }
    Text("Local-first, no account. Save a sync file into a folder you already sync (Google Drive, Dropbox, iCloud Drive via Files) and import it on another device. Merging adds missing items; existing items are kept.", fontSize = 12.sp)
    Label("What to include")
    @Composable fun toggle(label: String, v: Boolean, set: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) { Switch(v, set); Text(label, Modifier.padding(start = 8.dp), fontSize = 13.sp) }
    toggle("Bookmarks", scope.bookmarks) { scope = scope.copy(bookmarks = it) }
    toggle("Spaces", scope.spaces) { scope = scope.copy(spaces = it) }
    toggle("Tab groups", scope.groups) { scope = scope.copy(groups = it) }
    toggle("Air Traffic rules", scope.rules) { scope = scope.copy(rules = it) }
    toggle("Reading list (index only)", scope.readingList) { scope = scope.copy(readingList = it) }
    toggle("Preferences", scope.settings) { scope = scope.copy(settings = it) }
    toggle("Notes (sensitive)", scope.notes) { scope = scope.copy(notes = it) }
    toggle("Browsing history (sensitive)", scope.history) { scope = scope.copy(history = it) }
    Text("Passwords, cookies, logins and your Gemini key are never included.", fontSize = 11.sp)
    OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Passphrase (optional, encrypts the file)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button({ exporter.launch("thebrowser-sync.json") }) { Text("Export sync file") }
        OutlinedButton({ importer.launch(arrayOf("application/json", "*/*")) }) { Text("Import & merge") }
    }
    status?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 13.sp) }
}
