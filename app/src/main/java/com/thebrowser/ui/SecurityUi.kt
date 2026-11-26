package com.thebrowser.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.CheckStatus
import com.thebrowser.ManageSection
import com.thebrowser.core.atc.AirTrafficControl
import com.thebrowser.core.model.PermissionKinds
import com.thebrowser.core.shield.ShieldEventKind
import com.thebrowser.core.shield.ShieldToggles
import com.thebrowser.core.shield.SecurityBlock
import com.thebrowser.core.vault.Credential
import com.thebrowser.core.vault.PasswordTools
import java.text.DateFormat
import java.util.Date

private val GOOD = Color(0xFF00B894)
private val WARN = Color(0xFFE17055)
private val GREY = Color(0xFF636E72)

@Composable
private fun StatusDot(status: CheckStatus) {
    val c = when (status) { CheckStatus.Secure -> GOOD; CheckStatus.Attention -> WARN; CheckStatus.Unsupported -> GREY; CheckStatus.NotChecked -> Color(0xFFB2BEC3) }
    Box(Modifier.size(12.dp).background(c, RoundedCornerShape(6.dp)))
}

@Composable private fun H(text: String) = Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

// ───────────────────────── Security & Privacy Centre ─────────────────────────

@Composable
fun SecuritySection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sh = state.settings.shield
    val st = vm.stats
    var checks by remember { mutableStateOf<List<com.thebrowser.CheckItem>?>(null) }
    var listDialog by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (sh.enabled) "Protection is ON" else "Protection is OFF", fontSize = 18.sp, color = if (sh.enabled) GOOD else WARN)
                            Text("Level: ${sh.level.replaceFirstChar { it.uppercase() }} · HTTPS: ${sh.httpsMode} · Safe Browsing: engine-managed", fontSize = 12.sp)
                        }
                        Switch(sh.enabled, { v -> vm.updateShield { it.copy(enabled = v) } })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("standard" to "Standard", "aggressive" to "Aggressive", "custom" to "Custom").forEach { (id, label) ->
                            FilterChip(sh.level == id, { vm.updateShield { it.copy(level = id) } }, { Text(label) })
                        }
                    }
                    Text(when (sh.level) {
                        "aggressive" -> "Blocks more trackers and ads, hides cookie banners and ad slots, same-origin referrers, and applies font and browser-API fingerprint protection. May break some sites."
                        "custom" -> "You choose each protection below."
                        else -> "Strong everyday protection: third-party trackers/ads/social widgets, third-party cookies, tracking parameters, CNAME cloaking, canvas/WebGL/audio fingerprinting, minimal referrers."
                    }, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // Real, event-based numbers
        item { H("Protection statistics (real events only)") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val rows = listOf(
                        "Trackers blocked" to listOf(ShieldEventKind.Tracker, ShieldEventKind.Social, ShieldEventKind.Cname),
                        "Ads blocked" to listOf(ShieldEventKind.Ad),
                        "Dangerous pages / downloads" to listOf(ShieldEventKind.Dangerous, ShieldEventKind.DangerousDownload),
                        "Certificate errors stopped" to listOf(ShieldEventKind.CertError),
                        "Tracking parameters removed" to listOf(ShieldEventKind.ParamStripped),
                        "Tracking redirects skipped" to listOf(ShieldEventKind.BounceUnwrapped),
                        "Upgraded to HTTPS" to listOf(ShieldEventKind.HttpsUpgrade),
                        "Suspicious redirects / lookalikes flagged" to listOf(ShieldEventKind.SuspiciousRedirect, ShieldEventKind.Lookalike),
                        "Fingerprinting attempts altered" to listOf(ShieldEventKind.FpCanvas, ShieldEventKind.FpWebgl, ShieldEventKind.FpAudio, ShieldEventKind.FpFonts, ShieldEventKind.FpApi),
                        "Cookie banners / ad slots hidden" to listOf(ShieldEventKind.ConsentHidden, ShieldEventKind.AdsHidden),
                    )
                    Row { Spacer(Modifier.weight(1f)); Text("Today", Modifier.width(56.dp), fontSize = 11.sp); Text("Total", Modifier.width(64.dp), fontSize = 11.sp) }
                    rows.forEach { (label, kinds) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(label, Modifier.weight(1f), fontSize = 13.sp)
                            Text("${st.todayAll(*kinds.toTypedArray())}", Modifier.width(56.dp), fontSize = 13.sp)
                            Text("${st.totalAll(*kinds.toTypedArray())}", Modifier.width(64.dp), fontSize = 13.sp)
                        }
                    }
                    Text("Counted since ${DateFormat.getDateInstance().format(Date(st.since))}. Each number moves only when the browser actually blocked, rewrote or altered something. Cookies blocked can't be counted: Chromium doesn't report them.", fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    TextButton({ vm.resetStats() }) { Text("Reset statistics") }
                }
            }
        }

        item { H("HTTPS and connection") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("off" to "Off", "first" to "HTTPS-First", "strict" to "Strict").forEach { (id, l) -> FilterChip(sh.httpsMode == id, { vm.updateShield { it.copy(httpsMode = id) } }, { Text(l) }) }
            }
            Text("HTTPS-First upgrades http:// links and warns if a site can't do HTTPS. Strict never opens HTTP and removes the “proceed” option on certificate errors. Invalid certificates are always stopped with a warning; they are never skipped silently. Mixed content and ws:// from https pages are blocked by the engine. HSTS and Certificate Transparency are enforced by Chromium where your WebView version supports them.", fontSize = 11.sp)
        }
        item { Toggle("Send Global Privacy Control & Do Not Track", sh.gpc && sh.dnt) { v -> vm.updateShield { it.copy(gpc = v, dnt = v) } } }
        item {
            H("WebRTC (can reveal your local network address)")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("default" to "Default", "relay" to "Relay only", "off" to "Disabled").forEach { (id, l) -> FilterChip(sh.webRtc == id, { vm.updateShield { it.copy(webRtc = id) } }, { Text(l) }) }
            }
            Text("Relay only forces calls through a relay server so your address isn't exposed (may break some calls). Applied from the next page load.", fontSize = 11.sp)
        }

        if (sh.level == "custom") {
            item { H("Custom protections") }
            val c = sh.custom
            fun set(f: (ShieldToggles) -> ShieldToggles) = vm.updateShield { it.copy(custom = f(it.custom)) }
            item { Toggle("Third-party trackers", c.trackers) { v -> set { it.copy(trackers = v) } } }
            item { Toggle("Third-party ads", c.ads) { v -> set { it.copy(ads = v) } } }
            item { Toggle("Social media trackers & embeds", c.social) { v -> set { it.copy(social = v) } } }
            item { Toggle("Third-party cookies", c.thirdPartyCookies) { v -> set { it.copy(thirdPartyCookies = v) } } }
            item { Toggle("Canvas fingerprinting", c.fpCanvas) { v -> set { it.copy(fpCanvas = v) } } }
            item { Toggle("WebGL fingerprinting", c.fpWebgl) { v -> set { it.copy(fpWebgl = v) } } }
            item { Toggle("Audio fingerprinting", c.fpAudio) { v -> set { it.copy(fpAudio = v) } } }
            item { Toggle("Font fingerprinting", c.fpFonts) { v -> set { it.copy(fpFonts = v) } } }
            item { Toggle("Browser-API fingerprinting", c.fpApis) { v -> set { it.copy(fpApis = v) } } }
            item { Toggle("Remove tracking parameters", c.stripParams) { v -> set { it.copy(stripParams = v) } } }
            item { Toggle("Skip tracking redirects (bounce)", c.bounce) { v -> set { it.copy(bounce = v) } } }
            item { Toggle("CNAME-cloaking protection", c.cname) { v -> set { it.copy(cname = v) } } }
            item { Toggle("Hide cookie-consent banners", c.consent) { v -> set { it.copy(consent = v) } } }
            item { Toggle("Hide ad slots (cosmetic)", c.cosmeticAds) { v -> set { it.copy(cosmeticAds = v) } } }
            item {
                Text("Referrer", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("default" to "Site default", "strict-origin-when-cross-origin" to "Minimal", "same-origin" to "Same-origin only", "no-referrer" to "None").forEach { (id, l) -> FilterChip(c.referrer == id, { set { it.copy(referrer = id) } }, { Text(l) }) }
                }
            }
        }

        item { H("Filter lists") }
        item {
            Text("Built-in lists cover well-known trackers, ad networks and social widgets. Add lists for more. Only plain domain rules are used (no cosmetic or regex rules), and updating a list contacts only that list's host.", fontSize = 11.sp)
            Toggle("Update lists automatically (weekly)", sh.autoUpdateLists) { v -> vm.updateShield { it.copy(autoUpdateLists = v) } }
        }
        items(sh.lists, key = { it.id }) { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(l.enabled, { vm.toggleFilterList(l.id) })
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(l.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${l.category} · ${l.hostCount} domains · " + (if (l.lastUpdated > 0) "updated ${DateFormat.getDateInstance().format(Date(l.lastUpdated))}" else "not downloaded"), fontSize = 11.sp)
                }
                if (l.url.isNotBlank()) TextButton({ vm.updateFilterList(l.id) }) { Text("Update") }
                IconButton({ vm.removeFilterList(l.id) }) { Icon(Icons.Default.Delete, "Remove list") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ listDialog = true }) { Text("Add list") }
                OutlinedButton({ customText = true }) { Text("Paste rules") }
            }
        }

        item { H("Sites with protection turned off") }
        item {
            if (sh.siteOff.isEmpty()) Text("None. Use the lock icon next to the address to switch protection off for one site.", fontSize = 12.sp)
            sh.siteOff.forEach { h -> Row(verticalAlignment = Alignment.CenterVertically) { Text(h, Modifier.weight(1f)); TextButton({ vm.toggleSiteShield(h) }) { Text("Turn on") } } }
        }

        item { H("Security Check") }
        item {
            Button({ checks = vm.securityCheck() }) { Text(if (checks == null) "Run Security Check" else "Run again") }
            Text("Each row comes from a real signal. A switch being on is never counted as proof; what can't be verified is shown as Unsupported or Not checked.", fontSize = 11.sp)
        }
        checks?.let { list ->
            items(list) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp)) { StatusDot(c.status) }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("${c.title} — ${c.status.label}", fontSize = 14.sp)
                        Text(c.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                }
            }
        }

        item { H("How your data is handled") }
        item {
            Text("• No analytics, no telemetry, no accounts. Browsing history stays on this device and is never sent for analytics or to a security provider.\n" +
                "• Safe Browsing is provided by Google through Android System WebView. It checks a local hash-prefix list and may send partial URL hashes for matches. That is the only security provider.\n" +
                "• Optional, off unless you use them: search suggestions (your typing goes to the search engine), Gemini (only what you confirm), filter-list updates (a request to the list host), breach check (first 5 characters of a password hash).\n" +
                "• Private browsing doesn't make you anonymous: websites, your network and your internet provider can still see your activity.\n" +
                "• Android WebView limits: no per-site cookie blocking, no storage partitioning controls, no site isolation controls, no web notifications.".replace("\\\n", "\n"), fontSize = 12.sp)
        }
    }

    if (listDialog) AddListDialog(vm) { listDialog = false }
    if (customText) {
        var name by remember { mutableStateOf("") }; var text by remember { mutableStateOf("") }; var cat by remember { mutableStateOf("ads") }
        AlertDialog(
            onDismissRequest = { customText = false }, title = { Text("Custom filter rules") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(cat == "ads", { cat = "ads" }, { Text("Ads") }); FilterChip(cat == "trackers", { cat = "trackers" }, { Text("Trackers") }) }
                    OutlinedTextField(text, { text = it }, label = { Text("One per line: ||example.com^  or  0.0.0.0 example.com") }, modifier = Modifier.heightIn(min = 120.dp))
                }
            },
            confirmButton = { TextButton({ vm.addFilterListText(name, text, cat); customText = false }, enabled = text.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton({ customText = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontSize = 14.sp); Switch(on, set) }

@Composable
private fun AddListDialog(vm: BrowserViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var cat by remember { mutableStateOf("ads") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add a filter list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Popular lists (downloaded from their own sites when you tap):", fontSize = 12.sp)
                vm.filterManager.presets.forEach { p ->
                    TextButton({ vm.addFilterListUrl(p.name, p.url, p.category); onDismiss() }) { Column { Text(p.name, fontSize = 14.sp); Text(p.note, fontSize = 11.sp) } }
                }
                HorizontalDivider()
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("HTTPS list URL") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(cat == "ads", { cat = "ads" }, { Text("Ads") }); FilterChip(cat == "trackers", { cat = "trackers" }, { Text("Trackers") }) }
            }
        },
        confirmButton = { TextButton({ vm.addFilterListUrl(name, url, cat); onDismiss() }, enabled = url.startsWith("https://")) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

// ───────────────────────── current website panel ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteInfoSheet(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tab = state.activeTab ?: run { vm.siteInfoOpen = false; return }
    val host = AirTrafficControl.hostOf(tab.url)
    val ps = vm.pageShield[tab.id]
    val origin = runCatching { Uri.parse(tab.url).let { "${it.scheme}://${it.authority}" } }.getOrDefault("")
    val pid = state.profileIdOfSpace(tab.spaceId)
    val rules = state.permissions.filter { it.profileId == pid && it.origin == origin }
    ModalBottomSheet(onDismissRequest = { vm.siteInfoOpen = false }) {
        LazyColumn(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text(host ?: tab.url, fontSize = 18.sp) }
            item {
                val https = tab.url.startsWith("https://")
                val local = host == "localhost" || host?.endsWith(".local") == true
                Text(when { https -> "🔒 Secure connection (HTTPS). The engine validated the certificate; details are shown only if there is a problem."; local -> "Local address"; else -> "⚠ Not secure: this page uses HTTP, so others on the network can read or change it." }, fontSize = 13.sp, color = if (https) GOOD else WARN)
                if (ps?.upgradedToHttps == true) Text("Upgraded from HTTP to HTTPS by HTTPS-First.", fontSize = 12.sp)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Protection for this site", Modifier.weight(1f))
                    Switch(vm.siteShieldOn(host), { host?.let(vm::toggleSiteShield) }, enabled = host != null)
                }
                if (!vm.siteShieldOn(host)) Text("Shields are off here: no tracker blocking, fingerprint protection, or URL cleaning. Certificate and Safe Browsing warnings still apply.", fontSize = 11.sp, color = WARN)
            }
            item { H("Blocked on this page (real events)") }
            val kinds = ShieldEventKind.values().filter { (ps?.of(it) ?: 0) > 0 }
            if (kinds.isEmpty()) item { Text("Nothing blocked or changed on this page so far.", fontSize = 13.sp) }
            items(kinds) { k -> Row { Text(k.label, Modifier.weight(1f), fontSize = 13.sp); Text("${ps?.of(k)}", fontSize = 13.sp) } }
            if (ps != null && ps.blockedHosts.isNotEmpty() && !tab.isPrivate) item { Text("Hosts: " + ps.blockedHosts.take(8).joinToString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
            item { Text("Cookies blocked: not measurable on Android WebView. Third-party cookies are ${if (vm.shieldConfigFor(tab).toggles.thirdPartyCookies && vm.siteShieldOn(host)) "blocked" else "allowed"} here.", fontSize = 12.sp) }
            item { H("Permissions for this site") }
            if (rules.isEmpty()) item { Text("No saved permissions. Sensitive ones always ask first.", fontSize = 13.sp) }
            items(rules) { r -> Text("${PermissionKinds.of(r.kind)?.label ?: r.kind}: ${r.mode}${if (r.expiresAt > 0) " (until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.expiresAt))})" else ""}", fontSize = 13.sp) }
            item { H("Extensions with access to this page") }
            val exts = vm.extensionsWithAccess(tab.url)
            if (exts.isEmpty()) item { Text("None.", fontSize = 13.sp) } else items(exts) { e -> Text("${e.name} ${e.version}", fontSize = 13.sp) }
            if (tab.isPrivate) item { Text("Private tab: extensions don't run and nothing is saved.", fontSize = 12.sp) }
            item { TextButton({ vm.siteInfoOpen = false; vm.manage = ManageSection.Permissions }) { Text("Site settings & Security Centre →") } }
        }
    }
}

// ───────────────────────── full-page interstitial ─────────────────────────

@Composable
fun SecurityInterstitial(vm: BrowserViewModel, tabId: String, b: SecurityBlock) {
    var details by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val danger = b.kind in setOf("cert", "malware", "phishing", "unwanted", "billing")
    Surface(Modifier.fillMaxSize(), color = if (danger) Color(0xFF7A1F1F) else Color(0xFF5B4A1A)) {
        Column(Modifier.fillMaxSize().padding(24.dp).safeDrawingPadding(), verticalArrangement = Arrangement.Center) {
            Text(if (danger) "⛔" else "⚠️", fontSize = 48.sp)
            Text(b.title, fontSize = 24.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            Text(b.explanation, fontSize = 15.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 12.dp))
            Text(b.host, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(20.dp))
            Button({ vm.blockBack(tabId) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Back to safety") }
            TextButton({ details = !details }) { Text(if (details) "Hide details" else "Why was this blocked?", color = Color.White) }
            if (details) {
                b.details.forEach { Text("• $it", fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f)) }
                if (b.proceed != null) {
                    Spacer(Modifier.height(8.dp))
                    if (!confirm) TextButton({ confirm = true }) { Text("I understand the risk — continue anyway", color = Color(0xFFFFB4AB)) }
                    else Button({ vm.blockProceed(tabId) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("Continue to ${b.host} (unsafe)") }
                } else Text("Bypassing is disabled in Strict mode.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
fun PrivateLockScreen(vm: BrowserViewModel) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF1B1B1F)) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒", fontSize = 56.sp)
            Text("Private tabs are locked", fontSize = 20.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            Text("Unlock with your fingerprint, face or device PIN.", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 8.dp))
            Button({ vm.unlockPrivate() }) { Text("Unlock") }
            TextButton({ vm.clearAllPrivate() }) { Text("Clear all private sessions", color = Color(0xFFFFB4AB)) }
            Text("Private browsing doesn't hide your activity from websites, your network or your internet provider.", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
fun SavePasswordDialog(vm: BrowserViewModel, p: com.thebrowser.SavePrompt) {
    AlertDialog(
        onDismissRequest = vm::dismissSavePrompt, title = { Text("Save password?") },
        text = { Text("Save the login for ${AirTrafficControl.hostOf(p.origin)}${if (p.username.isNotBlank()) " (${p.username})" else ""}? It is encrypted with a key in the device's secure hardware and needs your fingerprint, face or PIN to use.") },
        confirmButton = { TextButton({ vm.acceptSavePrompt(false) }) { Text("Save") } },
        dismissButton = { Row { TextButton({ vm.acceptSavePrompt(true) }) { Text("Never for this site") }; TextButton(vm::dismissSavePrompt) { Text("Not now") } } },
    )
}

@Composable
fun DownloadWarningDialog(vm: BrowserViewModel, w: com.thebrowser.DownloadWarning) {
    AlertDialog(
        onDismissRequest = { vm.downloadWarning = null }, title = { Text("This download may be risky") },
        text = { Column { Text(w.fileName, fontSize = 14.sp); w.findings.forEach { Text("• $it", fontSize = 13.sp) }; Text("TheBrowser can't scan the file's contents. Only continue if you trust the site.", fontSize = 12.sp) } },
        confirmButton = { TextButton({ vm.downloadWarning = null; w.proceed() }) { Text("Download anyway") } },
        dismissButton = { TextButton({ vm.downloadWarning = null }) { Text("Cancel") } },
    )
}

@Composable
fun FillChooserDialog(vm: BrowserViewModel, list: List<Credential>) {
    AlertDialog(
        onDismissRequest = { vm.fillChoices = emptyList() }, title = { Text("Choose a login") },
        text = { Column { list.forEach { c -> TextButton({ vm.fill(c) }) { Text(c.username.ifBlank { "(no username)" }) } } } },
        confirmButton = { TextButton({ vm.fillChoices = emptyList() }) { Text("Cancel") } },
    )
}

// ───────────────────────── permission centre ─────────────────────────

@Composable
fun PermissionsSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pid = state.activeSpace.profileId
    var tab by remember { mutableStateOf(0) }
    ScrollableTabRow(tab, edgePadding = 0.dp) {
        listOf("Defaults", "Sites", "History").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
    }
    when (tab) {
        0 -> LazyColumn {
            item { Text("What a site gets when you haven't decided yet. Camera, microphone and location are never granted silently.", fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            items(PermissionKinds.all) { k ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k.label, Modifier.weight(1f), fontSize = 15.sp)
                        if (k.support == "unsupported") Text("Not supported here", fontSize = 11.sp, color = GREY)
                    }
                    if (k.support == "unsupported") Text(k.note, fontSize = 11.sp)
                    else {
                        val cur = PermissionKinds.defaultMode(k.id, state.settings.permissionDefaults)
                        val options = if (k.support == "prompted") listOf("ask" to "Ask", "block" to "Block") else listOf("allow" to "Allow", "block" to "Block")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { options.forEach { (id, l) -> FilterChip(cur == id, { vm.setPermissionDefault(k.id, id) }, { Text(l) }) } }
                        if (k.note.isNotBlank()) Text(k.note, fontSize = 11.sp)
                    }
                }
            }
            item {
                H("Expiry")
                Text("Temporary grants last ${state.settings.permissionExpiryHours} hours. Camera, microphone, location and MIDI grants that go unused for ${state.settings.inactivityRevokeDays} days are revoked automatically.", fontSize = 12.sp)
                OutlinedButton({ vm.resetAllPermissions() }, Modifier.padding(top = 8.dp)) { Text("Reset all site permissions") }
            }
        }
        1 -> {
            val origins = state.permissions.filter { it.profileId == pid }.groupBy { it.origin }
            LazyColumn {
                if (origins.isEmpty()) item { Text("No saved site permissions in this Profile.", Modifier.padding(top = 12.dp)) }
                origins.forEach { (origin, list) ->
                    item(key = origin) {
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Text(origin.substringAfter("://"), Modifier.weight(1f), fontSize = 15.sp); TextButton({ vm.resetSitePermissions(pid, origin) }) { Text("Reset") } }
                                list.forEach { r ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${PermissionKinds.of(r.kind)?.label ?: r.kind}" + if (r.expiresAt > 0) " (until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.expiresAt))})" else "", Modifier.weight(1f), fontSize = 13.sp)
                                        listOf("allow" to "Allow", "ask" to "Ask", "block" to "Block").forEach { (m, l) -> FilterChip(r.mode == m, { vm.setSitePermission(pid, origin, r.kind, m) }, { Text(l, fontSize = 11.sp) }, Modifier.padding(start = 4.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        else -> LazyColumn {
            item { Row { Text("Decisions made for sites (private tabs are never logged).", Modifier.weight(1f), fontSize = 12.sp); TextButton({ vm.clearPermissionLog() }) { Text("Clear") } } }
            items(state.permissionLog.filter { it.profileId == pid }) { e ->
                Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.time))} · ${e.origin.substringAfter("://")} · ${PermissionKinds.of(e.kind)?.label ?: e.kind} · ${e.decision} (${e.source})", fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}

// ───────────────────────── password manager ─────────────────────────

@Composable
fun PasswordsSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val list = vm.credentials
    var edit by remember { mutableStateOf<Credential?>(null) }
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(setOf<String>()) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { u -> vm.exportPasswords(pass)?.let { txt -> context.contentResolver.openOutputStream(u)?.use { it.write(txt.toByteArray()) }; vm.message = if (pass.isBlank()) "Exported. This file contains passwords in PLAIN TEXT: delete it after use." else "Exported (encrypted)." } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u -> vm.importPasswords(context.contentResolver.openInputStream(u)?.bufferedReader()?.readText().orEmpty(), pass) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Offer to save passwords", Modifier.weight(1f)); Switch(state.settings.passwordManager, { v -> vm.updateSettings { it.copy(passwordManager = v) }; vm.refreshShield() }) }
    Text("Passwords are encrypted with a hardware-backed Android Keystore key that needs your fingerprint, face or device PIN. They are never logged and never sent to Gemini.", fontSize = 11.sp)
    vm.vaultError?.let { Text(it, color = WARN, fontSize = 12.sp) }
    if (list == null) {
        Button({ vm.unlockVault() }, Modifier.padding(top = 8.dp)) { Text(if (vm.vault.exists()) "Unlock passwords" else "Set up password manager") }
        if (vm.vault.exists()) OutlinedButton({ vm.wipeVault() }, Modifier.padding(top = 4.dp)) { Text("Delete all saved passwords") }
        return
    }
    val weak = list.filter { PasswordTools.isWeak(it.password) }.map { it.id }.toSet()
    val reused = PasswordTools.reused(list)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp).horizontalScroll(rememberScrollState())) {
        Button({ edit = Credential("", "https://", "", PasswordTools.generate()) }) { Text("Add") }
        OutlinedButton({ vm.checkBreaches() }) { Text("Check for breaches") }
        OutlinedButton({ vm.lockVault() }) { Text("Lock") }
    }
    Text("${weak.size} weak · ${reused.size} reused" + (vm.breachResult?.let { " · $it" } ?: ""), fontSize = 12.sp, color = if (weak.isNotEmpty() || reused.isNotEmpty()) WARN else GOOD)
    LazyColumn(Modifier.heightIn(max = 300.dp)) {
        items(list, key = { it.id }) { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { edit = c }) {
                    Text(c.origin.substringAfter("://"), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.username.ifBlank { "(no username)" } + if (c.id in weak) " · weak" else "" + if (c.id in reused) " · reused" else "", fontSize = 11.sp, color = if (c.id in weak || c.id in reused) WARN else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    if (c.id in show) Text(c.password, fontSize = 12.sp)
                }
                IconButton({ show = if (c.id in show) show - c.id else show + c.id }) { Text(if (c.id in show) "🙈" else "👁") }
                IconButton({ vm.deleteCredential(c.id) }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
    H("Import / export (Chrome & Firefox CSV)")
    OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Passphrase (encrypts exports; needed to import encrypted files)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton({ importer.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) }) { Text("Import") }
        OutlinedButton({ exporter.launch("thebrowser-passwords.csv") }) { Text("Export") }
    }
    Text("Without a passphrase an export is plain text. Passkeys can't be exported by an app.", fontSize = 11.sp)

    edit?.let { c0 ->
        var origin by remember(c0.id) { mutableStateOf(c0.origin) }; var user by remember(c0.id) { mutableStateOf(c0.username) }; var pw by remember(c0.id) { mutableStateOf(c0.password) }
        var len by remember { mutableStateOf(20f) }
        AlertDialog(
            onDismissRequest = { edit = null }, title = { Text(if (c0.id.isEmpty()) "Add login" else "Edit login") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(origin, { origin = it }, label = { Text("Website (https://…)") }, singleLine = true)
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true)
                    val s = PasswordTools.strength(pw)
                    Text("Strength: " + listOf("very weak", "weak", "fair", "good", "strong")[s], fontSize = 12.sp, color = if (s <= 1) WARN else GOOD)
                    Row(verticalAlignment = Alignment.CenterVertically) { Slider(len, { len = it }, valueRange = 12f..40f, modifier = Modifier.weight(1f)); Text("${len.toInt()}") }
                    TextButton({ pw = PasswordTools.generate(len.toInt()) }) { Text("Generate secure password") }
                }
            },
            confirmButton = { TextButton({ vm.saveCredential(c0.copy(id = c0.id.ifEmpty { java.util.UUID.randomUUID().toString() }, origin = origin.trim().trimEnd('/'), username = user, password = pw)); edit = null }, enabled = origin.startsWith("http") && pw.isNotEmpty()) { Text("Save") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } },
        )
    }
}
