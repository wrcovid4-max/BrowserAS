package com.thebrowser

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thebrowser.core.ai.ModelGateway
import com.thebrowser.core.ai.SecretStore
import com.thebrowser.core.ai.Turn
import com.thebrowser.core.ai.UsageTracker
import com.thebrowser.core.atc.AirTrafficControl
import com.thebrowser.core.atc.AtcDecision
import com.thebrowser.core.engine.EngineListener
import com.thebrowser.core.engine.EngineSession
import com.thebrowser.core.engine.EngineSettings
import com.thebrowser.core.page.DistractionJs
import com.thebrowser.core.page.JsValue
import com.thebrowser.core.page.TabMemory
import com.thebrowser.core.page.TabMemoryJs
import com.thebrowser.core.model.*
import com.thebrowser.core.ext.MatchPattern
import com.thebrowser.core.reader.Reader
import com.thebrowser.core.reader.ReaderArticle
import com.thebrowser.core.shield.*
import com.thebrowser.core.stats.PageShield
import com.thebrowser.core.stats.ProtectionStats
import com.thebrowser.core.vault.*
import android.webkit.URLUtil
import java.net.HttpURLConnection
import java.net.URL
import com.thebrowser.core.state.AppJson
import com.thebrowser.core.sync.Sync
import com.thebrowser.core.sync.SyncScope
import com.thebrowser.core.util.BookmarksIO
import com.thebrowser.core.util.Suggestions
import com.thebrowser.core.util.UrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

enum class ManageSection(val label: String) {
    Security("Security"), Spaces("Spaces"), Profiles("Profiles"), Routing("Air Traffic"), Permissions("Permissions"),
    Passwords("Passwords"), Bookmarks("Bookmarks"), History("History"), Downloads("Downloads"), Notes("Notes"),
    Reading("Reading list"), Extensions("Extensions"), Agents("Agents"), AgentCentre("Agent Centre"), Appearance("Appearance"), Privacy("Privacy"), Gemini("Gemini"), Ai("AI"), Sync("Sync"), About("About"),
}

data class RoutePrompt(val url: String, val target: Space, val current: Space, val reuseTabId: String?)
data class PendingSend(val prompt: String, val items: List<Attachment>)
/** decide(mode): once | day | always | block | block_always */
class PermissionPrompt(val origin: String, val kinds: Set<String>, val isPrivate: Boolean, val decide: (mode: String) -> Unit)
class SavePrompt(val origin: String, val username: String, val password: String)
class DownloadWarning(val fileName: String, val url: String, val findings: List<String>, val proceed: () -> Unit)

class BrowserViewModel(private val app: Application) : AndroidViewModel(app) {
    private val c = (app as TheBrowserApp).container
    val engine get() = c.engine
    val secrets get() = c.secrets
    val gemini = GeminiController(viewModelScope, c.gemini, c.stateStore) { state.value.settings.geminiModel }
    val geminiClient get() = c.gemini
    val downloads = DownloadCenter(app, viewModelScope, c.stateStore, { pid, u -> c.engine.cookiesFor(pid, u) }, { state.value.settings.downloadTreeUri })

    private val _state = MutableStateFlow(prune(c.stateStore.loadState()))
    val state: StateFlow<BrowserState> = _state

    // ---- ephemeral UI state ----
    var commandBarOpen by mutableStateOf(false)
    var geminiOpen by mutableStateOf(false)
    var geminiPrefill by mutableStateOf<String?>(null)
    var findOpen by mutableStateOf(false)
    var tabSwitcherOpen by mutableStateOf(false)
    var manage by mutableStateOf<ManageSection?>(null)
    var reader by mutableStateOf<ReaderArticle?>(null)
    var routePrompt by mutableStateOf<RoutePrompt?>(null)

    // ───────── Hide Distracting Items ─────────
    var hideMode by mutableStateOf(false)
    var hideSelected by mutableStateOf(false)
    var hideCount by mutableStateOf(0)
    private var hideTabId: String? = null
    private var hideJob: kotlinx.coroutines.Job? = null

    internal fun applyHidden(tabId: String, url: String) {
        val host = AirTrafficControl.hostOf(url) ?: return
        val list = state.value.hiddenElements[host]?.takeIf { it.isNotEmpty() } ?: return
        sessions[tabId]?.evaluate(DistractionJs.apply(list)) { }
    }

    fun startHideMode() {
        val tab = state.value.activeTab ?: return
        if (tab.url.isBlank()) { message = "Open a web page first."; return }
        val s = sessions[tab.id] ?: return
        findOpen = false
        s.evaluate(DistractionJs.START) { raw ->
            if (raw == null) { message = "Hide Distracting Items needs page scripts, which this engine can't run (Gecko mode)." }
            else {
                hideMode = true; hideTabId = tab.id; hideSelected = false; hideCount = 0
                hideJob?.cancel()
                hideJob = viewModelScope.launch {
                    while (hideMode) {
                        val id = hideTabId
                        if (id == null || state.value.activeTabId != id) { endHideMode(true); break }
                        sessions[id]?.evaluate(DistractionJs.STATE) { r -> DistractionJs.parseState(r)?.let { hideSelected = it.selected; hideCount = it.hiddenCount } }
                        delay(350)
                    }
                }
            }
        }
    }

    private fun hideSession() = hideTabId?.let { sessions[it] }
    private fun hideHost(): String? = hideTabId?.let { id -> tabOf(id)?.url?.let { AirTrafficControl.hostOf(it) } }

    fun hideLarger() { hideSession()?.evaluate(DistractionJs.LARGER) { } }

    fun hideSelectedItem() {
        val id = hideTabId ?: return
        val host = hideHost()
        hideSession()?.evaluate(DistractionJs.HIDE) { raw ->
            val sel = JsValue.string(raw)
            if (!sel.isNullOrBlank() && host != null) {
                // Private tabs hide the item for this visit only: remembering it would record that the site was visited.
                if (tabOf(id)?.isPrivate != true) update { st -> st.copy(hiddenElements = st.hiddenElements + (host to DistractionJs.safe((st.hiddenElements[host] ?: emptyList()) + sel).takeLast(200))) }
            }
        }
    }

    fun hideUndo() {
        val host = hideHost()
        hideSession()?.evaluate(DistractionJs.UNDO) { raw ->
            val sel = JsValue.string(raw)
            if (!sel.isNullOrBlank() && host != null) update { st -> st.copy(hiddenElements = st.hiddenElements + (host to ((st.hiddenElements[host] ?: emptyList()) - sel))) }
        }
    }

    fun endHideMode(stopPage: Boolean) {
        if (stopPage) hideSession()?.evaluate(DistractionJs.STOP) { }
        hideMode = false; hideSelected = false; hideCount = 0; hideTabId = null; hideJob?.cancel()
    }

    /** Brings back everything hidden on a site (Settings, or the page menu). */
    fun showHiddenItems(host: String) {
        update { st -> st.copy(hiddenElements = st.hiddenElements - host) }
        val tab = state.value.activeTab
        if (tab != null && AirTrafficControl.hostOf(tab.url) == host) sessions[tab.id]?.evaluate(DistractionJs.apply(emptyList())) { }
    }

    fun hiddenCountForActiveSite(): Int = state.value.activeTab?.url?.let { AirTrafficControl.hostOf(it) }?.let { state.value.hiddenElements[it]?.size } ?: 0

    // ───────── Tab hover preview + memory ─────────
    val hoverMemory = mutableStateMapOf<String, TabMemory>()
    val hoverLoaded = mutableStateMapOf<String, Boolean>()

    /** Refreshes the preview picture and the memory figure for a tab the pointer is resting on. */
    fun refreshHover(tabId: String) {
        val s = sessions[tabId]
        hoverLoaded[tabId] = s != null
        if (s == null) { hoverMemory.remove(tabId); return }
        s.snapshot(THUMB_W)?.let { thumbs[tabId] = it }
        s.evaluate(TabMemoryJs.SCRIPT) { raw -> TabMemoryJs.parse(raw)?.let { hoverMemory[tabId] = it } ?: hoverMemory.remove(tabId) }
    }

    fun setTabLayout(layout: String) {
        updateSettings { it.copy(tabLayout = layout) }
        if (layout == "horizontal") sidebarVisible = false
    }

    var pendingSend by mutableStateOf<PendingSend?>(null)
    var permissionPrompt by mutableStateOf<PermissionPrompt?>(null)
    var extensionToApprove by mutableStateOf<InstalledExtension?>(null)
    var message by mutableStateOf<String?>(null)
    var sidebarVisible by mutableStateOf(true)
    var standalone by mutableStateOf(false) // launched from a pinned "web app" shortcut
    var inPip by mutableStateOf(false)
    var siteInfoOpen by mutableStateOf(false)
    var pdfFile by mutableStateOf<java.io.File?>(null)
    private var pdfIsTemp = false
    /** The visible Activity, when there is one (set by MainActivity). Agents create web views with it so dialogs work. */
    var uiContext: Context? = null
    val agents by lazy { AgentController(this) }
    val hub by lazy { AgentHub(this) }
    val netSpeed by lazy { NetSpeedMonitor(this) }
    var savePrompt by mutableStateOf<SavePrompt?>(null)
    var downloadWarning by mutableStateOf<DownloadWarning?>(null)
    var fillChoices by mutableStateOf<List<Credential>>(emptyList())
    var privateLocked by mutableStateOf(false)
    var breachResult by mutableStateOf<String?>(null)
    var vaultError by mutableStateOf<String?>(null)
    var credentials by mutableStateOf<List<Credential>?>(null) // decrypted only while unlocked
    var stats by mutableStateOf(c.stateStore.loadStats())
    val pageShield = mutableStateMapOf<String, PageShield>()
    val securityBlocks = mutableStateMapOf<String, SecurityBlock>()
    /** Hook set by MainActivity: shows the system biometric / device-credential prompt. */
    var authenticate: ((title: String, subtitle: String, result: (Boolean) -> Unit) -> Unit)? = null
    var customView by mutableStateOf<View?>(null)
    private var customViewHidden: (() -> Unit)? = null
    var suggestions by mutableStateOf<List<String>>(emptyList())
    val progress = mutableStateMapOf<String, Int>()
    val canBack = mutableStateMapOf<String, Boolean>()
    val canForward = mutableStateMapOf<String, Boolean>()
    val thumbs = mutableStateMapOf<String, Bitmap>()
    private var sessionConsent = false

    // Activity-owned hooks (result launchers live in MainActivity)
    var fileChooserLauncher: ((Intent, (Int, Intent?) -> Unit) -> Unit)? = null
    var runtimePermissionLauncher: ((Array<String>, (Boolean) -> Unit) -> Unit)? = null

    // ---- private browsing: throw-away engine Profile, deleted when the last private tab closes ----
    private fun newPrivateId() = "private_" + UUID.randomUUID().toString().replace("-", "_")
    private var privateProfileId = newPrivateId()

    // ---- shields (filters, per-page counters, stats) ----
    val filterEngine = FilterEngine()
    val filterManager = FilterListManager(c.stateStore, filterEngine)
    private val shieldSeed = UUID.randomUUID().toString() // per app run: fingerprint noise differs between runs
    private val privatePerms = mutableMapOf<String, String>() // "origin|kind" → allow|block, private tabs only, never saved
    val vault get() = c.vault
    private var vaultTimer: Job? = null
    private var statsJob: Job? = null

    init {
        engine.purgePrivateProfiles() // leftovers from a crash
        publishShortcuts()
        maintainPermissions()
        agents.applySettings()
        netSpeed.apply()
        viewModelScope.launch(Dispatchers.IO) { filterManager.rebuild(_state.value.settings.shield.lists) }
        autoUpdateLists()
    }

    private fun profileOf(tab: Tab) = if (tab.isPrivate) privateProfileId else state.value.profileIdOfSpace(tab.spaceId)

    // ---- persistence ----
    private var saveJob: Job? = null
    internal fun update(block: (BrowserState) -> BrowserState) {
        _state.update(block)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            val snapshot = _state.value
            withContext(Dispatchers.IO) { c.stateStore.saveState(snapshot) }
            publishShortcuts()
        }
    }

    private fun publishShortcuts() = (app as TheBrowserApp).publishSpaceShortcuts(_state.value.spaces)

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    private fun prune(s: BrowserState): BrowserState {
        val cutoff = System.currentTimeMillis() - s.settings.temporaryTabHours * 3_600_000L
        val stale = s.tabs.filter { it.temporary && !it.pinned && it.lastActive < cutoff && it.id != s.activeTabId }
        if (stale.isEmpty()) return s
        return s.copy(tabs = s.tabs - stale.toSet(), closed = (stale.map { ClosedTab(it, System.currentTimeMillis()) } + s.closed).take(25))
    }

    // ---- engine sessions (live WebViews, LRU-capped for memory) ----
    internal val sessions = LinkedHashMap<String, EngineSession>(16, 0.75f, true)

    private fun isActive(p: SitePermission) = p.expiresAt == 0L || p.expiresAt > System.currentTimeMillis()

    fun shieldConfigFor(tab: Tab?): ShieldConfig {
        val st = state.value; val sh = st.settings.shield
        val priv = tab?.isPrivate == true
        var t = sh.effective()
        // Private tabs always get at least the core protections.
        if (priv) t = t.copy(trackers = true, ads = true, social = true, thirdPartyCookies = true, fpCanvas = true, fpWebgl = true, fpAudio = true, stripParams = true, bounce = true, cname = true)
        val pid = tab?.let(::profileOf) ?: DEFAULT_PROFILE_ID
        val rules = if (priv) emptyList() else st.permissions.filter { it.profileId == pid && isActive(it) }
        fun hosts(kind: String, mode: String) = rules.filter { it.kind == kind && it.mode == mode }.mapNotNull { AirTrafficControl.hostOf(it.origin) }.toSet()
        return ShieldConfig(
            enabled = sh.enabled, toggles = t, httpsMode = sh.httpsMode, webRtc = sh.webRtc, gpc = sh.gpc, dnt = sh.dnt,
            siteOff = sh.siteOff.map { it.lowercase() }.toSet(), filters = filterEngine, seed = shieldSeed + if (priv) privateProfileId else "",
            isPrivate = priv, jsBlockedHosts = hosts("javascript", "block"), thirdPartyCookiesAllowedHosts = hosts("third_party_cookies", "allow"),
            popupAllowedHosts = hosts("popups", "allow"), captureCredentials = st.settings.passwordManager && !priv,
        )
    }

    /** Pushes current settings to live pages. Request blocking changes at once; page-start scripts apply on the next load. */
    fun refreshShield() {
        sessions.forEach { (id, sess) -> sess.updateShield(shieldConfigFor(state.value.tabs.firstOrNull { it.id == id })) }
    }

    fun sessionFor(context: Context, tab: Tab): EngineSession {
        sessions[tab.id]?.let { return it }
        val s = state.value
        val scripts = if (tab.isPrivate) emptyList() else c.extensions.scripts(s.extensions) // extensions never run in private tabs
        val session = engine.createSession(context, profileOf(tab), EngineSettings(shieldConfigFor(tab), s.settings.blockPopups, scripts, s.settings.passkeys), listenerFor(tab.id))
        sessions[tab.id] = session
        if (tab.url.isNotBlank()) session.load(tab.url)
        evict()
        return session
    }

    private fun evict() {
        val keep = setOfNotNull(state.value.activeTabId, state.value.splitTabId)
        for (id in sessions.keys.toList()) {
            if (sessions.size <= MAX_LIVE) break
            if (id in keep) continue
            sessions.remove(id)?.let { it.snapshot(THUMB_W)?.let { b -> thumbs[id] = b }; it.destroy() }
        }
    }

    private fun dropSession(id: String) {
        securityBlocks.remove(id)?.let { runCatching { it.back() } } // always resolve pending SSL / Safe Browsing handlers
        pageShield.remove(id)
        sessions.remove(id)?.destroy()
        progress.remove(id); canBack.remove(id); canForward.remove(id); thumbs.remove(id)
    }

    fun activeSession(): EngineSession? = state.value.activeTabId?.let { sessions[it] }

    fun captureThumbs() {
        sessions.forEach { (id, s) -> s.snapshot(THUMB_W)?.let { thumbs[id] = it } }
    }

    internal fun tabOf(id: String) = state.value.tabs.firstOrNull { it.id == id }

    private fun listenerFor(tabId: String) = object : EngineListener {
        override fun onUrlChanged(url: String) = updateTab(tabId) { it.copy(url = url) }
        override fun onTitleChanged(title: String) = updateTab(tabId) { it.copy(title = title) }
        override fun onProgress(percent: Int) { progress[tabId] = percent }
        override fun onNavState(canGoBack: Boolean, canGoForward: Boolean) { canBack[tabId] = canGoBack; canForward[tabId] = canGoForward }
        override fun onPageFinished(url: String, title: String) { addHistory(tabId, url, title); applyHidden(tabId, url) }
        override fun onOpenInNewTab(url: String) = openLink(url)
        override fun onPageCommitted(url: String) { pageShield[tabId] = PageShield(); securityBlocks.remove(tabId); applyHidden(tabId, url); if (hideTabId == tabId) endHideMode(false) }

        override fun onShieldEvent(kind: ShieldEventKind, host: String) {
            recordStat(kind)
            val priv = tabOf(tabId)?.isPrivate == true
            var ps = (pageShield[tabId] ?: PageShield()).with(kind, if (priv) "" else host)
            if (kind == ShieldEventKind.HttpsUpgrade) ps = ps.copy(upgradedToHttps = true)
            pageShield[tabId] = ps
        }

        override fun onSecurityBlock(block: SecurityBlock) { securityBlocks[tabId] = block }

        override fun onCredentialSubmitted(origin: String, username: String, password: String) {
            val st = state.value.settings
            val host = AirTrafficControl.hostOf(origin) ?: return
            if (!st.passwordManager || password.isBlank() || host in st.neverSavePasswords || vault.hasCredentialFor(origin)) return
            savePrompt = SavePrompt(origin, username, password)
        }

        override fun onDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
            val tab = tabOf(tabId)
            if (state.value.settings.openPdfInViewer && url.startsWith("https://") && (mimeType?.contains("pdf") == true || url.substringBefore('?').endsWith(".pdf", true))) { openPdfFromUrl(url, userAgent, tab?.let(::profileOf) ?: DEFAULT_PROFILE_ID); return }
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val findings = UrlSafety.riskyDownload(name, url)
            val start = {
                val ok = downloads.start(url, userAgent, contentDisposition, mimeType, tab?.let(::profileOf) ?: DEFAULT_PROFILE_ID, tab?.isPrivate == true)
                message = if (ok) "Download started — see Settings → Downloads." else "This kind of download isn't supported (only http/https)."
            }
            if (findings.isEmpty()) start() else { recordStat(ShieldEventKind.DangerousDownload); downloadWarning = DownloadWarning(name, url, findings, start) }
        }

        override fun onPermissionRequest(origin: String, kinds: Set<String>, decide: (Boolean) -> Unit) = handlePermission(tabId, origin, kinds, decide)
        override fun onFileChooser(intent: Intent, complete: (Int, Intent?) -> Unit) {
            val l = fileChooserLauncher
            if (l == null) complete(Activity.RESULT_CANCELED, null) else l(intent, complete)
        }
        override fun onCustomView(view: View?, onHidden: (() -> Unit)?) {
            if (view == null) { customViewHidden = null; customView = null } else { customViewHidden = onHidden; customView = view }
        }
    }

    // ---- real, event-based protection statistics ----
    private fun recordStat(kind: ShieldEventKind) {
        stats = stats.record(kind)
        statsJob?.cancel()
        statsJob = viewModelScope.launch { delay(2000); val snap = stats; withContext(Dispatchers.IO) { c.stateStore.saveStats(snap) } }
    }
    fun resetStats() { stats = ProtectionStats(); viewModelScope.launch(Dispatchers.IO) { c.stateStore.saveStats(stats) } }

    // ---- security interstitials ----
    fun blockProceed(tabId: String) { securityBlocks.remove(tabId)?.proceed?.invoke() }
    fun blockBack(tabId: String) {
        val b = securityBlocks.remove(tabId) ?: return
        b.back()
        val s = sessions[tabId]
        if (s?.canGoBack == true) s.goBack() else updateTab(tabId) { it.copy(url = "", title = "") }
    }

    fun exitFullscreenVideo() { customViewHidden?.invoke(); customViewHidden = null; customView = null }

    private fun updateTab(id: String, f: (Tab) -> Tab) =
        update { s -> s.copy(tabs = s.tabs.map { if (it.id == id) f(it) else it }) }

    // ---- website permission centre ----
    private fun logPerm(profileId: String, origin: String, kind: String, decision: String, source: String) = update { s ->
        s.copy(permissionLog = (listOf(PermissionLogEntry(now(), profileId, origin, kind, decision, source)) + s.permissionLog).take(500))
    }

    private fun putPermission(pid: String, origin: String, kinds: Set<String>, mode: String, expiresAt: Long, priv: Boolean) {
        if (priv) { kinds.forEach { privatePerms["$origin|$it"] = mode }; return } // private rules live in memory only
        update { s ->
            s.copy(permissions = s.permissions.filterNot { it.profileId == pid && it.origin == origin && it.kind in kinds } +
                kinds.map { SitePermission(pid, origin, it, mode, expiresAt, if (mode == "allow") now() else 0L) })
        }
    }

    private fun handlePermission(tabId: String, origin: String, kinds: Set<String>, decide: (Boolean) -> Unit) {
        val tab = tabOf(tabId)
        val priv = tab?.isPrivate == true
        val pid = tab?.let(::profileOf) ?: DEFAULT_PROFILE_ID
        val st = state.value
        fun saved(k: String): String? =
            if (priv) privatePerms["$origin|$k"]
            else st.permissions.firstOrNull { it.profileId == pid && it.origin == origin && it.kind == k && isActive(it) }?.mode
        val modes = kinds.associateWith { saved(it) ?: PermissionKinds.defaultMode(it, st.settings.permissionDefaults) }

        fun grant() {
            if (!priv) kinds.forEach { k ->
                logPerm(pid, origin, k, "allowed", if (saved(k) != null) "saved rule" else "prompt")
                st.permissions.firstOrNull { it.profileId == pid && it.origin == origin && it.kind == k }?.let { p -> update { s -> s.copy(permissions = s.permissions.map { if (it == p) it.copy(lastUsed = now()) else it }) } }
            }
            val perms = kinds.flatMap {
                when (it) {
                    "camera" -> listOf(Manifest.permission.CAMERA)
                    "microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
                    "location" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    else -> emptyList()
                }
            }.toTypedArray()
            val launcher = runtimePermissionLauncher
            if (perms.isEmpty()) decide(true) else if (launcher == null) decide(false) else launcher(perms) { ok -> decide(ok) }
        }

        if (modes.values.any { it == "block" }) {
            if (!priv) kinds.forEach { logPerm(pid, origin, it, "blocked", if (saved(it) != null) "saved rule" else "default") }
            decide(false); return
        }
        if (modes.values.all { it == "allow" }) { grant(); return } // never reached by default: sensitive kinds default to "ask"

        permissionPrompt = PermissionPrompt(origin, kinds, priv) { mode ->
            permissionPrompt = null
            when (mode) {
                "block" -> { if (!priv) kinds.forEach { logPerm(pid, origin, it, "blocked", "prompt") }; decide(false) }
                "block_always" -> { putPermission(pid, origin, kinds, "block", 0L, priv); if (!priv) kinds.forEach { logPerm(pid, origin, it, "blocked", "prompt") }; decide(false) }
                "once" -> grant()
                "day" -> { putPermission(pid, origin, kinds, "allow", now() + st.settings.permissionExpiryHours * 3_600_000L, priv); grant() }
                else -> { putPermission(pid, origin, kinds, "allow", 0L, priv); grant() }
            }
        }
    }

    /** Permission Centre edit. mode: allow | ask | block (ask removes the saved rule). */
    fun setSitePermission(profileId: String, origin: String, kind: String, mode: String) {
        update { s ->
            val rest = s.permissions.filterNot { it.profileId == profileId && it.origin == origin && it.kind == kind }
            s.copy(permissions = if (mode == "ask") rest else rest + SitePermission(profileId, origin, kind, mode, 0L, if (mode == "allow") now() else 0L))
        }
        logPerm(profileId, origin, kind, if (mode == "ask") "reset" else if (mode == "allow") "allowed" else "blocked", "saved rule")
        refreshShield()
    }
    fun resetSitePermissions(profileId: String, origin: String) { update { s -> s.copy(permissions = s.permissions.filterNot { it.profileId == profileId && it.origin == origin }) }; logPerm(profileId, origin, "all", "reset", "saved rule"); refreshShield() }
    fun resetAllPermissions() { update { it.copy(permissions = emptyList()) }; privatePerms.clear(); refreshShield(); message = "All site permissions reset." }
    fun clearPermissionLog() = update { it.copy(permissionLog = emptyList()) }

    /** Run at startup: drops expired temporary grants and revokes unused sensitive grants after the inactivity period. */
    private fun maintainPermissions() {
        val st = _state.value; val n = System.currentTimeMillis()
        val cutoff = n - st.settings.inactivityRevokeDays * 86_400_000L
        val expired = st.permissions.filter { it.expiresAt in 1..n }
        val inactive = st.permissions.filter { it.mode == "allow" && it.expiresAt == 0L && it.kind in setOf("camera", "microphone", "location", "midi") && it.lastUsed in 1 until cutoff }
        if (expired.isEmpty() && inactive.isEmpty()) return
        update { s ->
            s.copy(
                permissions = s.permissions - expired.toSet() - inactive.toSet(),
                permissionLog = (expired.map { PermissionLogEntry(n, it.profileId, it.origin, it.kind, "expired", "maintenance") } +
                    inactive.map { PermissionLogEntry(n, it.profileId, it.origin, it.kind, "revoked-inactive", "maintenance") } + s.permissionLog).take(500),
            )
        }
    }

    // ---- tabs ----
    fun newTab(url: String = "", spaceId: String = state.value.activeSpaceId, openCommandBar: Boolean = true, private: Boolean = false): String? {
        if (private && !engine.supportsIsolatedProfiles) {
            message = "Private tabs need a WebView with Profile support. Update Android System WebView."
            return null
        }
        val t = Tab(newId(), spaceId, url, lastActive = now(), isPrivate = private)
        update { s -> s.copy(tabs = s.tabs + t, activeTabId = t.id, activeSpaceId = spaceId) }
        if (url.isBlank() && openCommandBar) commandBarOpen = true
        return t.id
    }

    fun selectTab(id: String) {
        val prev = state.value.activeTabId
        if (prev != null && prev != id) sessions[prev]?.snapshot(THUMB_W)?.let { thumbs[prev] = it }
        update { s ->
            val t = s.tabs.firstOrNull { it.id == id } ?: return@update s
            s.copy(activeTabId = id, activeSpaceId = t.spaceId, tabs = s.tabs.map { if (it.id == id) it.copy(lastActive = now()) else it })
        }
        if (prev != null && prev != id && prev != state.value.splitTabId) sessions[prev]?.pause()
        sessions[id]?.resume()
        findOpen = false
    }

    fun closeTab(id: String) {
        val s = state.value
        val t = s.tabs.firstOrNull { it.id == id } ?: return
        dropSession(id)
        val remaining = s.tabs.filter { it.id != id }
        val next = if (s.activeTabId == id) remaining.filter { it.spaceId == t.spaceId }.maxByOrNull { it.lastActive }?.id else s.activeTabId
        update {
            it.copy(
                tabs = remaining, activeTabId = next,
                splitTabId = if (it.splitTabId == id) null else it.splitTabId,
                closed = if (t.isPrivate) it.closed else (listOf(ClosedTab(t, now())) + it.closed).take(25),
            )
        }
        if (t.isPrivate && remaining.none { it.isPrivate }) {
            engine.deleteProfileData(privateProfileId) // all private WebViews are destroyed: wipes cookies/storage
            privateProfileId = newPrivateId()
        }
    }

    fun reopenClosed() {
        val c = state.value.closed.firstOrNull() ?: run { message = "No recently closed tabs."; return }
        val spaceOk = state.value.spaces.any { it.id == c.tab.spaceId }
        val tab = c.tab.copy(spaceId = if (spaceOk) c.tab.spaceId else state.value.activeSpaceId, lastActive = now())
        update { it.copy(closed = it.closed.drop(1), tabs = it.tabs + tab, activeTabId = tab.id, activeSpaceId = tab.spaceId) }
    }

    fun togglePin(id: String) = updateTab(id) { it.copy(pinned = !it.pinned, temporary = false, groupId = if (!it.pinned) null else it.groupId) }
    fun toggleFavourite(id: String) = updateTab(id) { it.copy(favourite = !it.favourite, temporary = false) }
    fun toggleTemporary(id: String) = updateTab(id) { it.copy(temporary = !it.temporary, pinned = false, favourite = false) }
    fun setSplit(id: String?) = update { it.copy(splitTabId = id) }

    /** Moves [id] to sit just before [beforeId] (used by drag-and-drop reordering). Same Space only. */
    fun moveTabBefore(id: String, beforeId: String) = update { s ->
        val me = s.tabs.firstOrNull { it.id == id } ?: return@update s
        val target = s.tabs.firstOrNull { it.id == beforeId } ?: return@update s
        if (me.spaceId != target.spaceId || id == beforeId) return@update s
        val list = s.tabs.filter { it.id != id }.toMutableList()
        val idx = list.indexOfFirst { it.id == beforeId }
        list.add(idx, me.copy(pinned = target.pinned, groupId = target.groupId))
        s.copy(tabs = list)
    }

    fun moveTab(id: String, delta: Int) = update { s ->
        val i = s.tabs.indexOfFirst { it.id == id }
        if (i < 0) return@update s
        val me = s.tabs[i]
        var j = i + delta
        while (j in s.tabs.indices) {
            val o = s.tabs[j]
            if (o.spaceId == me.spaceId && o.pinned == me.pinned && o.groupId == me.groupId) break
            j += delta
        }
        if (j !in s.tabs.indices) return@update s
        val list = s.tabs.toMutableList()
        list[i] = list[j]; list[j] = me
        s.copy(tabs = list)
    }

    fun profilesDiffer(spaceA: String, spaceB: String) = state.value.profileIdOfSpace(spaceA) != state.value.profileIdOfSpace(spaceB)

    /** Callers must have obtained consent first when [profilesDiffer] is true. */
    fun moveTabToSpace(id: String, spaceId: String) {
        dropSession(id)
        update { s ->
            val follow = s.activeTabId == id
            s.copy(
                tabs = s.tabs.map { if (it.id == id) it.copy(spaceId = spaceId, groupId = null) else it },
                activeSpaceId = if (follow) spaceId else s.activeSpaceId,
            )
        }
    }

    fun assignGroup(tabId: String, groupId: String?) = updateTab(tabId) { it.copy(groupId = groupId, pinned = if (groupId != null) false else it.pinned) }

    // ---- groups ----
    fun createGroup(name: String, colorArgb: Long, tabId: String? = null) {
        val spaceId = tabId?.let { id -> state.value.tabs.firstOrNull { it.id == id }?.spaceId } ?: state.value.activeSpaceId
        val g = TabGroup(newId(), spaceId, name.ifBlank { "Group" }, colorArgb)
        update { s -> s.copy(groups = s.groups + g, tabs = s.tabs.map { if (it.id == tabId) it.copy(groupId = g.id, pinned = false) else it }) }
    }
    fun renameGroup(id: String, name: String) = update { s -> s.copy(groups = s.groups.map { if (it.id == id) it.copy(name = name) else it }) }
    fun recolorGroup(id: String, color: Long) = update { s -> s.copy(groups = s.groups.map { if (it.id == id) it.copy(colorArgb = color) else it }) }
    fun toggleGroup(id: String) = update { s -> s.copy(groups = s.groups.map { if (it.id == id) it.copy(collapsed = !it.collapsed) else it }) }
    fun deleteGroup(id: String) = update { s -> s.copy(groups = s.groups.filter { it.id != id }, tabs = s.tabs.map { if (it.groupId == id) it.copy(groupId = null) else it }) }
    fun moveGroupToSpace(groupId: String, spaceId: String) {
        state.value.tabs.filter { it.groupId == groupId }.forEach { dropSession(it.id) }
        update { s ->
            s.copy(
                groups = s.groups.map { if (it.id == groupId) it.copy(spaceId = spaceId) else it },
                tabs = s.tabs.map { if (it.groupId == groupId) it.copy(spaceId = spaceId) else it },
            )
        }
    }

    // ---- spaces ----
    fun createSpace(name: String, colorArgb: Long, emoji: String, profileId: String): String {
        val sp = Space(newId(), name.ifBlank { "Space" }, colorArgb, emoji, profileId)
        update { it.copy(spaces = it.spaces + sp, activeSpaceId = sp.id, activeTabId = null) }
        return sp.id
    }

    fun switchSpace(id: String) {
        val recent = state.value.tabs.filter { it.spaceId == id && !it.isPrivate }.maxByOrNull { it.lastActive }
        update { it.copy(activeSpaceId = id, activeTabId = recent?.id) }
    }

    fun updateSpace(id: String, f: (Space) -> Space) {
        val old = state.value.spaces.firstOrNull { it.id == id } ?: return
        val new = f(old)
        if (new.profileId != old.profileId) state.value.tabs.filter { it.spaceId == id }.forEach { dropSession(it.id) }
        update { s -> s.copy(spaces = s.spaces.map { if (it.id == id) new else it }) }
    }

    fun deleteSpace(id: String) {
        val s = state.value
        if (s.spaces.size <= 1) { message = "You need at least one Space."; return }
        s.tabs.filter { it.spaceId == id }.forEach { dropSession(it.id) }
        val remainingSpaces = s.spaces.filter { it.id != id }
        update {
            it.copy(
                spaces = remainingSpaces,
                tabs = it.tabs.filter { t -> t.spaceId != id },
                groups = it.groups.filter { g -> g.spaceId != id },
                rules = it.rules.filter { r -> r.spaceId != id },
                activeSpaceId = if (it.activeSpaceId == id) remainingSpaces.first().id else it.activeSpaceId,
                activeTabId = if (it.activeTab?.spaceId == id) null else it.activeTabId,
                splitTabId = if (it.tabs.firstOrNull { t -> t.id == it.splitTabId }?.spaceId == id) null else it.splitTabId,
            )
        }
    }

    fun exportSpace(id: String): String? {
        val s = state.value
        val sp = s.spaces.firstOrNull { it.id == id } ?: return null
        return AppJson.encodeToString(SpaceSnapshot.serializer(), SpaceSnapshot(sp, s.groups.filter { it.spaceId == id }, s.tabs.filter { it.spaceId == id && !it.isPrivate }))
    }

    fun importSpace(json: String): Boolean = runCatching {
        val snap = AppJson.decodeFromString(SpaceSnapshot.serializer(), json)
        val s = state.value
        val profile = if (s.profiles.any { it.id == snap.space.profileId }) snap.space.profileId else DEFAULT_PROFILE_ID
        val spaceId = newId()
        val groupIds = snap.groups.associate { it.id to newId() }
        update {
            it.copy(
                spaces = it.spaces + snap.space.copy(id = spaceId, profileId = profile),
                groups = it.groups + snap.groups.map { g -> g.copy(id = groupIds.getValue(g.id), spaceId = spaceId) },
                tabs = it.tabs + snap.tabs.map { t -> t.copy(id = newId(), spaceId = spaceId, groupId = t.groupId?.let(groupIds::get), isPrivate = false) },
            )
        }
        true
    }.getOrDefault(false)

    // ---- profiles ----
    fun createProfile(name: String): String? {
        if (!engine.supportsIsolatedProfiles) { message = "This device's WebView can't isolate Profiles. Update Android System WebView."; return null }
        val p = Profile(newId(), name.ifBlank { "Profile" })
        update { it.copy(profiles = it.profiles + p) }
        return p.id
    }

    fun deleteProfile(id: String) {
        val s = state.value
        if (id == DEFAULT_PROFILE_ID) { message = "The default Profile can't be deleted."; return }
        if (s.spaces.any { it.profileId == id }) { message = "Move or delete the Spaces that use this Profile first."; return }
        engine.deleteProfileData(id)
        update { it.copy(profiles = it.profiles.filter { p -> p.id != id }, bookmarks = it.bookmarks.filter { b -> b.profileId != id }, history = it.history.filter { h -> h.profileId != id }, notes = it.notes.filter { n -> n.profileId != id }) }
    }

    fun renameProfile(id: String, name: String) = update { s -> s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(name = name) else it }) }
    fun clearCookies(profileId: String) { engine.clearCookies(profileId); message = "Cookies cleared." }

    // ---- Air Traffic Control ----
    fun addRule(pattern: String, spaceId: String, exception: Boolean) {
        if (pattern.isBlank()) return
        update { it.copy(rules = it.rules + AtcRule(newId(), pattern.trim(), spaceId, true, exception)) }
    }
    fun toggleRule(id: String) = update { s -> s.copy(rules = s.rules.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }) }
    fun deleteRule(id: String) = update { s -> s.copy(rules = s.rules.filter { it.id != id }) }

    // ---- opening links / navigation ----
    fun navigate(input: String, inNewTab: Boolean = false) {
        val s = state.value
        val url = UrlResolver.resolve(input, s.settings.searchEngine)
        if (url.isBlank()) return
        val active = s.activeTab
        if (inNewTab || active == null || active.url.isBlank()) openLink(url, reuseTabId = active?.takeIf { it.url.isBlank() }?.id, private = active?.isPrivate == true && active.url.isBlank())
        else loadInTab(active.id, url)
    }

    internal fun loadInTab(id: String, url: String) {
        updateTab(id) { it.copy(url = url, title = "") }
        sessions[id]?.load(url)
    }

    /** External intents, pop-ups and "new tab" navigations pass through Air Traffic Control. */
    fun openLink(url: String, reuseTabId: String? = null, bypassAtc: Boolean = false, private: Boolean = false) {
        val s = state.value
        if (!bypassAtc && !private) {
            when (val d = AirTrafficControl.decide(url, s.rules, s.spaces, s.activeSpaceId)) {
                is AtcDecision.Route -> if (d.space.id != s.activeSpaceId) {
                    if (d.needsProfileConsent) { routePrompt = RoutePrompt(url, d.space, s.activeSpace, reuseTabId); return }
                    place(url, d.space.id, reuseTabId, false); return
                }
                AtcDecision.None -> Unit
            }
        }
        place(url, s.activeSpaceId, reuseTabId, private || s.activeTab?.isPrivate == true)
    }

    private fun place(url: String, spaceId: String, reuseTabId: String?, private: Boolean) {
        val reuse = reuseTabId?.takeIf { id -> state.value.tabs.any { it.id == id && it.spaceId == spaceId } }
        if (reuse != null) { loadInTab(reuse, url); selectTab(reuse) } else newTab(url, spaceId, openCommandBar = false, private = private)
    }

    fun resolveRoute(choice: String) {
        val p = routePrompt ?: return
        routePrompt = null
        when (choice) {
            "target" -> place(p.url, p.target.id, null, false)
            "here" -> place(p.url, p.current.id, p.reuseTabId, false)
        }
    }

    // ---- intents from other apps / shortcuts ----
    fun handleExternalIntent(url: String?) {
        if (url.isNullOrBlank()) return
        if (state.value.settings.littleWindowForExternal) openInLittleWindow(url) else openLink(url)
    }

    fun handleSharedText(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) navigate(t, inNewTab = true)
    }

    fun askGeminiAbout(text: String) { geminiOpen = true; geminiPrefill = text }

    fun openInLittleWindow(url: String) {
        app.startActivity(
            Intent(app, LittleActivity::class.java).putExtra(LittleActivity.EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        )
    }

    /** Offers a home-screen shortcut that opens this page in chrome-less "web app" mode. */
    fun pinWebApp() {
        val tab = state.value.activeTab ?: return
        if (tab.url.isBlank() || !ShortcutManagerCompat.isRequestPinShortcutSupported(app)) { message = "Pinned shortcuts aren't supported by this launcher."; return }
        val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(tab.url)).putExtra(MainActivity.EXTRA_STANDALONE, true)
        val info = ShortcutInfoCompat.Builder(app, "webapp-${tab.id}").setShortLabel(tab.displayTitle.take(20))
            .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher)).setIntent(intent).build()
        ShortcutManagerCompat.requestPinShortcut(app, info, null)
    }

    // ---- toolbar actions ----
    fun back() { activeSession()?.goBack() }
    fun forward() { activeSession()?.goForward() }
    fun reload() { activeSession()?.reload() }
    fun stopLoading() { activeSession()?.stop() }
    fun print() { activeSession()?.print(state.value.activeTab?.displayTitle ?: "Page") }

    fun isBookmarked(tab: Tab?): Boolean {
        tab ?: return false
        val pid = state.value.profileIdOfSpace(tab.spaceId)
        return state.value.bookmarks.any { it.profileId == pid && it.url == tab.url }
    }

    fun toggleBookmark() {
        val s = state.value
        val tab = s.activeTab ?: return
        if (tab.url.isBlank()) return
        val pid = s.profileIdOfSpace(tab.spaceId)
        if (isBookmarked(tab)) update { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.profileId == pid && b.url == tab.url }) }
        else update { it.copy(bookmarks = it.bookmarks + Bookmark(newId(), pid, tab.url, tab.displayTitle)) }
    }
    fun removeBookmark(id: String) = update { s -> s.copy(bookmarks = s.bookmarks.filter { it.id != id }) }
    fun moveBookmarkToFolder(id: String, folder: String) = update { s -> s.copy(bookmarks = s.bookmarks.map { if (it.id == id) it.copy(folder = folder) else it }) }

    fun exportBookmarksHtml(): String = BookmarksIO.export(state.value.bookmarks.filter { it.profileId == state.value.activeSpace.profileId })
    fun importBookmarksHtml(html: String): Int {
        val pid = state.value.activeSpace.profileId
        val incoming = BookmarksIO.import(html, pid).filter { n -> state.value.bookmarks.none { it.profileId == pid && it.url == n.url } }
        update { it.copy(bookmarks = it.bookmarks + incoming) }
        return incoming.size
    }

    // ---- history (never recorded for private tabs) ----
    private fun addHistory(tabId: String, url: String, title: String) {
        val s = state.value
        val tab = s.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.isPrivate) return
        val pid = s.profileIdOfSpace(tab.spaceId)
        val last = s.history.firstOrNull { it.profileId == pid }
        if (last != null && last.url == url && now() - last.time < 30_000) return
        update { it.copy(history = (listOf(HistoryEntry(newId(), pid, url, title, now())) + it.history).take(2000)) }
    }
    fun deleteHistory(id: String) = update { s -> s.copy(history = s.history.filter { it.id != id }) }
    fun clearHistory(profileId: String, sinceMillis: Long?) = update { s -> s.copy(history = s.history.filterNot { it.profileId == profileId && (sinceMillis == null || it.time >= sinceMillis) }) }

    fun updateSettings(f: (Settings) -> Settings) { val before = _state.value.settings.netSpeed; update { it.copy(settings = f(it.settings)) }; if (_state.value.settings.netSpeed != before) netSpeed.apply() }
    fun setPermissionDefault(kind: String, mode: String) = updateSettings { it.copy(permissionDefaults = it.permissionDefaults + (kind to mode)) }

    // ---- search suggestions (opt-in) ----
    private var suggestJob: Job? = null
    fun suggest(query: String) {
        suggestJob?.cancel()
        if (!state.value.settings.searchSuggestions || query.length < 2) { suggestions = emptyList(); return }
        suggestJob = viewModelScope.launch { delay(180); suggestions = Suggestions.fetch(state.value.settings.searchEngine, query) }
    }

    // ---- reader view + offline reading ----
    fun openReader() {
        val session = activeSession() ?: run { message = "Open a page first."; return }
        session.evaluate(Reader.EXTRACT_JS) { raw ->
            val a = Reader.parse(raw)
            if (a == null) message = "No article found on this page." else reader = a
        }
    }

    /** Saves the readable article so it opens without a connection (images still need the network). */
    fun saveOffline() {
        val session = activeSession() ?: run { message = "Open a page first."; return }
        val tab = state.value.activeTab ?: return
        session.evaluate(Reader.EXTRACT_JS) { raw ->
            val a = Reader.parse(raw)
            if (a == null) { message = "No article found on this page."; return@evaluate }
            val id = newId()
            viewModelScope.launch(Dispatchers.IO) { c.stateStore.saveArticleBody(id, Reader.toJson(a)) }
            update { it.copy(articles = listOf(SavedArticle(id, state.value.profileIdOfSpace(tab.spaceId), a.title, tab.url, now(), a.wordCount)) + it.articles) }
            message = "Saved for offline reading."
        }
    }

    fun openSaved(id: String) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { c.stateStore.loadArticleBody(id) }
            val a = json?.let(Reader::fromJson)
            if (a == null) message = "Saved article not found." else reader = a
        }
    }

    fun deleteSaved(id: String) {
        viewModelScope.launch(Dispatchers.IO) { c.stateStore.deleteArticleBody(id) }
        update { it.copy(articles = it.articles.filter { a -> a.id != id }) }
    }

    // ---- notes & research boards ----
    fun addNote(title: String): String {
        val n = Note(newId(), state.value.activeSpace.profileId, title.ifBlank { "Untitled" }, updated = now())
        update { it.copy(notes = listOf(n) + it.notes) }
        return n.id
    }
    fun updateNote(id: String, f: (Note) -> Note) = update { s -> s.copy(notes = s.notes.map { if (it.id == id) f(it).copy(updated = now()) else it }) }
    fun deleteNote(id: String) = update { s -> s.copy(notes = s.notes.filter { it.id != id }) }

    /** Adds the current page (and any selected text) to a note as a clip: a lightweight research board. */
    fun clipToNote(noteId: String?) {
        val tab = state.value.activeTab ?: run { message = "Open a page first."; return }
        val session = activeSession() ?: return
        session.evaluate(SELECTION_JS) { raw ->
            val sel = Reader.pageText(raw, 2000)
            val id = noteId ?: addNote("Research board")
            updateNote(id) { it.copy(clips = it.clips + Clip(tab.url, tab.displayTitle, sel, now())) }
            message = "Clipped to note."
        }
    }

    // ---- extensions (content scripts only: see core/ext/Extensions.kt) ----
    fun installExtension(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { c.extensions.install(uri) } }
                .onSuccess { ext ->
                    val prev = state.value.extensions.firstOrNull { it.name == ext.name }
                    if (prev == null) {
                        update { it.copy(extensions = it.extensions + ext) }; extensionToApprove = ext
                    } else {
                        // Update: keep the sites the user already approved; ask again if anything new is requested.
                        val newAccess = ext.hostPatterns.any { it !in prev.hostPatterns } || ext.permissions.any { it !in prev.permissions }
                        c.extensions.uninstall(prev.id)
                        val merged = ext.copy(allowedPatterns = prev.allowedPatterns.filter { it in ext.hostPatterns }, granted = prev.granted && !newAccess, enabled = prev.enabled && !newAccess)
                        update { s -> s.copy(extensions = s.extensions.filter { it.id != prev.id } + merged) }
                        if (newAccess) { extensionToApprove = merged; message = "${ext.name} was updated and asks for new access. Review it before it runs." }
                        else message = "${ext.name} updated to ${ext.version}. Your site approvals were kept."
                    }
                }
                .onFailure { message = "Couldn't install: ${it.message}" }
        }
    }

    /** [patterns] are the match patterns the user switched on. Broad (all-sites) patterns are only included if they chose them. */
    fun approveExtension(id: String, patterns: List<String>) {
        update { s -> s.copy(extensions = s.extensions.map { if (it.id == id) it.copy(granted = true, enabled = patterns.isNotEmpty(), allowedPatterns = patterns) else it }) }
        extensionToApprove = null
        message = if (patterns.isEmpty()) "Nothing approved, so the extension stays off." else "Enabled for the sites you chose. It applies to tabs you open from now on."
    }
    fun setExtensionPatterns(id: String, patterns: List<String>) = update { s -> s.copy(extensions = s.extensions.map { if (it.id == id) it.copy(allowedPatterns = patterns, enabled = it.enabled && patterns.isNotEmpty()) else it }) }
    fun declineExtension() { extensionToApprove?.let { e -> if (!e.granted) removeExtension(e.id) }; extensionToApprove = null }
    fun toggleExtension(id: String) = update { s -> s.copy(extensions = s.extensions.map { if (it.id == id && it.granted) it.copy(enabled = !it.enabled) else it }) }
    fun removeExtension(id: String) { c.extensions.uninstall(id); update { s -> s.copy(extensions = s.extensions.filter { it.id != id }) } }

    // ---- sync (file based, optional) ----
    fun exportSync(scope: SyncScope, passphrase: String): String {
        val json = Sync.toJson(Sync.export(state.value, scope))
        return if (passphrase.isBlank()) json else Sync.encrypt(json, passphrase)
    }

    fun importSync(text: String, passphrase: String): String = runCatching {
        val json = if (Sync.isEncrypted(text)) { if (passphrase.isBlank()) return "This file is encrypted: enter its passphrase." else Sync.decrypt(text, passphrase) } else text
        val payload = Sync.fromJson(json)
        update { Sync.merge(it, payload) }
        "Merged: ${payload.bookmarks.size} bookmarks, ${payload.spaces.size} Spaces, ${payload.rules.size} rules."
    }.getOrElse { "Couldn't read that file (wrong passphrase or not a TheBrowser sync file)." }

    // ---- Gemini: nothing leaves the device until the user confirms ----
    private suspend fun evalString(tabId: String, js: String): String? = suspendCancellableCoroutine { cont ->
        val sess = sessions[tabId]
        if (sess == null) cont.resume(null) else sess.evaluate(js) { cont.resume(Reader.pageText(it)) }
    }

    fun requestSend(prompt: String, page: Boolean, selection: Boolean, tabIds: List<String> = emptyList()) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            val s = state.value
            val items = mutableListOf<Attachment>()
            var skipped = 0
            if (page) {
                val t = s.activeTab
                val text = t?.let { evalString(it.id, PAGE_JS) }.orEmpty()
                if (t != null && text.isNotBlank()) items += Attachment("Page: ${t.displayTitle}", "Title: ${t.displayTitle}\nURL: ${t.url}\n\n$text")
            }
            if (selection) {
                val text = s.activeTab?.let { evalString(it.id, SELECTION_JS) }.orEmpty()
                if (text.isNotBlank()) items += Attachment("Selected text", text)
            }
            for (id in tabIds) {
                val t = s.tabs.firstOrNull { it.id == id } ?: continue
                val text = evalString(id, PAGE_JS).orEmpty()
                if (text.isBlank()) skipped++ else items += Attachment("Tab: ${t.displayTitle}", "Title: ${t.displayTitle}\nURL: ${t.url}\n\n$text")
            }
            if (skipped > 0) message = "Skipped $skipped tab(s) that aren't loaded yet."
            val wantedContext = page || selection || tabIds.isNotEmpty()
            when {
                wantedContext && items.isEmpty() -> gemini.error = if (selection && !page) "Select some text on the page first." else "No readable text found. Wait for the page to load."
                items.isEmpty() -> gemini.send(prompt, emptyList())
                sessionConsent -> gemini.send(prompt, items)
                else -> pendingSend = PendingSend(prompt, items)
            }
        }
    }

    fun confirmSend(rememberForSession: Boolean) {
        val p = pendingSend ?: return
        pendingSend = null
        if (rememberForSession) sessionConsent = true
        gemini.send(p.prompt, p.items)
    }
    fun cancelSend() { pendingSend = null }

    // ---- AI platform: providers, keys, fallback, usage ----
    private fun providerKey(id: String): String? = if (id == "gemini") secrets.get(SecretStore.GEMINI_KEY) else secrets.get("provider_key_$id")

    /** One gateway for every AI feature: default provider, ordered fallbacks, retries, real token accounting. */
    val gateway = ModelGateway(
        configs = { state.value.providers },
        keyOf = { providerKey(it) },
        defaultId = { state.value.settings.ai.defaultProviderId },
        fallbackIds = { state.value.settings.ai.fallbackProviderIds },
        onUsage = { pid, model, u -> update { s -> s.copy(usage = UsageTracker.add(s.usage, ProtectionStats.today(), pid, model, u)) } },
    )

    fun hasProviderKey(id: String) = providerKey(id) != null
    fun setProviderKey(id: String, key: String) { if (id == "gemini") secrets.put(SecretStore.GEMINI_KEY, key.trim()) else secrets.put("provider_key_$id", key.trim()) }
    fun removeProviderKey(id: String) { if (id == "gemini") secrets.remove(SecretStore.GEMINI_KEY) else secrets.remove("provider_key_$id") }

    fun addProvider(kind: String, label: String, baseUrl: String, model: String): String {
        val p = ProviderConfig(newId(), kind, label.ifBlank { kind }, baseUrl.trim(), model.trim())
        update { it.copy(providers = it.providers + p) }
        return p.id
    }

    fun updateProvider(id: String, f: (ProviderConfig) -> ProviderConfig) = update { s -> s.copy(providers = s.providers.map { if (it.id == id) f(it) else it }) }

    fun deleteProvider(id: String) {
        if (id == "gemini") { message = "Gemini is built in. Turn it off instead."; return }
        removeProviderKey(id)
        update { s ->
            val rest = s.providers.filter { it.id != id }
            val ai = s.settings.ai
            s.copy(providers = rest, settings = s.settings.copy(ai = ai.copy(
                defaultProviderId = if (ai.defaultProviderId == id) rest.firstOrNull()?.id ?: "gemini" else ai.defaultProviderId,
                fallbackProviderIds = ai.fallbackProviderIds - id,
            )))
        }
    }

    fun setDefaultProvider(id: String) = updateSettings { it.copy(ai = it.ai.copy(defaultProviderId = id, fallbackProviderIds = it.ai.fallbackProviderIds - id)) }
    fun toggleFallbackProvider(id: String) = updateSettings { s ->
        val ai = s.ai
        s.copy(ai = ai.copy(fallbackProviderIds = if (id in ai.fallbackProviderIds) ai.fallbackProviderIds - id else if (id == ai.defaultProviderId) ai.fallbackProviderIds else ai.fallbackProviderIds + id))
    }

    suspend fun providerModels(id: String): List<String> = withContext(Dispatchers.IO) { gateway.provider(id)?.listModels() ?: emptyList() }

    /** Sends one tiny request to prove the key, URL and model work. Reports the real error otherwise. */
    fun testProvider(id: String) {
        val p = gateway.provider(id) ?: run { message = "Turn the provider on first."; return }
        message = "Testing ${p.config.label}…"
        viewModelScope.launch {
            message = runCatching { p.complete(p.config.model, "Reply with the single word OK.", listOf(Turn.User("ping")), emptyList()) }
                .fold({ "✓ ${p.config.label} works (${it.usage.inputTokens + it.usage.outputTokens} tokens used)." }, { "✗ ${p.config.label}: ${it.message}" })
        }
    }

    fun resetUsage() = update { it.copy(usage = emptyList()) }

    // ---- PDF viewer / editor ----
    fun closePdf(f: java.io.File) { if (pdfFile == f) { pdfFile = null; if (pdfIsTemp) f.delete() } }

    /** Opens a local PDF the user picked. The original is only read; edits go into a saved copy. */
    fun openPdfUri(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { java.io.File(app.cacheDir, "pdf/${UUID.randomUUID()}.pdf").also { f -> f.parentFile?.mkdirs(); app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } } } } }
                .onSuccess { pdfIsTemp = true; pdfFile = it }.onFailure { message = "Couldn't open that PDF: ${it.message}" }
        }
    }

    /** PDFs a page would download open in the viewer instead (setting). Uses the tab's own cookies; the temp copy is deleted on close. */
    private fun openPdfFromUrl(url: String, ua: String, pid: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val c = URL(url).openConnection() as HttpURLConnection; c.setRequestProperty("User-Agent", ua); engine.cookiesFor(pid, url)?.let { c.setRequestProperty("Cookie", it) }
                try { if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}"); java.io.File(app.cacheDir, "pdf/${UUID.randomUUID()}.pdf").also { f -> f.parentFile?.mkdirs(); c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } } } } finally { c.disconnect() }
            } }.onSuccess { pdfIsTemp = true; pdfFile = it }.onFailure { message = "Couldn't open the PDF: ${it.message}" }
        }
    }

    // ---- shield settings ----
    fun updateShield(f: (ShieldSettings) -> ShieldSettings) { updateSettings { it.copy(shield = f(it.shield)) }; refreshShield() }
    fun siteShieldOn(host: String?) = host == null || host.lowercase() !in state.value.settings.shield.siteOff.map { it.lowercase() }
    fun toggleSiteShield(host: String) {
        val h = host.lowercase()
        updateShield { sh -> sh.copy(siteOff = if (h in sh.siteOff) sh.siteOff - h else sh.siteOff + h) }
        activeSession()?.reload()
    }

    // ---- filter lists (custom + auto-updating) ----
    private fun setLists(f: (List<FilterListRef>) -> List<FilterListRef>) {
        updateShield { it.copy(lists = f(it.lists)) }
        val lists = state.value.settings.shield.lists
        viewModelScope.launch(Dispatchers.IO) { filterManager.rebuild(lists) }
    }

    fun addFilterListUrl(name: String, url: String, category: String) {
        val ref = FilterListRef(newId(), name.ifBlank { url.substringAfter("://").substringBefore('/') }, url.trim(), category, true)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { filterManager.fetch(ref) } }
                .onSuccess { r -> setLists { it + (r ?: ref) }; message = "Added “${ref.name}” (${(r ?: ref).hostCount} domains understood)." }
                .onFailure { message = "Couldn't fetch list: ${it.message}" }
        }
    }

    fun addFilterListText(name: String, text: String, category: String) {
        val ref = filterManager.addText(newId(), name.ifBlank { "Custom list" }, text, category)
        setLists { it + ref }
        message = "Added “${ref.name}”: ${ref.hostCount} domain rules understood (other rule types are ignored)."
    }

    fun updateFilterList(id: String) {
        val ref = state.value.settings.shield.lists.firstOrNull { it.id == id && it.url.isNotBlank() } ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { filterManager.fetch(ref) } }
                .onSuccess { r -> setLists { l -> l.map { if (it.id == id) (r ?: it) else it } } }
                .onFailure { message = "Couldn't update ${ref.name}: ${it.message}" }
        }
    }

    fun toggleFilterList(id: String) = setLists { l -> l.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } }
    fun removeFilterList(id: String) { filterManager.delete(id); setLists { l -> l.filter { it.id != id } } }

    private fun autoUpdateLists() {
        val sh = _state.value.settings.shield
        if (!sh.autoUpdateLists) return
        val stale = sh.lists.filter { it.enabled && it.url.isNotBlank() && System.currentTimeMillis() - it.lastUpdated > 7 * 86_400_000L }
        if (stale.isEmpty()) return
        viewModelScope.launch {
            for (ref in stale) {
                runCatching { withContext(Dispatchers.IO) { filterManager.fetch(ref) } }.onSuccess { r -> if (r != null) setLists { l -> l.map { if (it.id == ref.id) r else it } } }
            }
        }
    }

    // ---- private browsing lock ----
    fun lockPrivate() {
        val priv = state.value.tabs.filter { it.isPrivate }
        if (priv.isEmpty()) return
        privateLocked = true
        priv.forEach { sessions[it.id]?.pause() }
    }

    fun unlockPrivate() {
        val a = authenticate
        if (a == null) { privateLocked = false; return }
        a("Unlock private tabs", "Confirm it's you to continue") { ok ->
            if (ok) { privateLocked = false; state.value.activeTabId?.let { sessions[it]?.resume() } } else message = "Private tabs stay locked."
        }
    }

    /** Closes every private tab, deletes the private Profile (cookies, storage, cache) and forgets private downloads. */
    fun clearAllPrivate() {
        state.value.tabs.filter { it.isPrivate }.map { it.id }.forEach(::closeTab)
        privatePerms.clear()
        downloads.clearPrivate()
        privateLocked = false
        message = "All private sessions cleared."
    }

    /** Called when the app leaves the foreground. */
    fun onAppBackgrounded() {
        if (state.value.settings.privateAutoLock) lockPrivate()
        lockVault()
    }

    // ---- password manager ----
    fun lockVault() { credentials = null; vaultTimer?.cancel() }
    private fun touchVault() { vaultTimer?.cancel(); vaultTimer = viewModelScope.launch { delay(120_000); credentials = null } }

    /** Runs [op]; if the Keystore says the user must authenticate, prompts once and retries. */
    private fun <T> vaultOp(op: () -> T, done: (T) -> Unit) {
        vaultError = null
        try { done(op()) }
        catch (e: VaultLockedException) {
            val a = authenticate ?: run { vaultError = "Authentication isn't available on this device."; return }
            a("Unlock passwords", "Confirm it's you to use saved passwords") { ok ->
                if (!ok) vaultError = "Not unlocked."
                else try { done(op()) } catch (e2: VaultLockedException) { vaultError = "Still locked. Try again." } catch (e2: VaultUnavailableException) { vaultError = e2.message }
            }
        } catch (e: VaultUnavailableException) { vaultError = e.message }
    }

    fun unlockVault() = vaultOp({ vault.load() }) { credentials = it; touchVault() }

    fun saveCredential(cr: Credential) = vaultOp({
        val list = vault.load().toMutableList()
        val i = list.indexOfFirst { it.id == cr.id || (it.origin == cr.origin && it.username == cr.username) }
        val item = cr.copy(updated = now(), created = if (cr.created == 0L) now() else cr.created)
        if (i >= 0) list[i] = item.copy(id = list[i].id, created = list[i].created) else list.add(item)
        vault.save(list); list.toList()
    }) { credentials = it; touchVault(); message = "Saved." }

    fun deleteCredential(id: String) = vaultOp({ val l = vault.load().filter { it.id != id }; vault.save(l); l }) { credentials = it; touchVault() }

    fun wipeVault() { vault.wipe(); credentials = null; breachResult = null; message = "All saved passwords deleted." }

    fun acceptSavePrompt(neverForSite: Boolean) {
        val p = savePrompt ?: return
        savePrompt = null
        if (neverForSite) { AirTrafficControl.hostOf(p.origin)?.let { h -> updateSettings { it.copy(neverSavePasswords = it.neverSavePasswords + h) } }; return }
        saveCredential(Credential(newId(), p.origin, p.username, p.password))
    }
    fun dismissSavePrompt() { savePrompt = null }

    private fun originOf(url: String): String? = runCatching { java.net.URI(url).let { "${it.scheme}://${it.authority}" } }.getOrNull()?.takeIf { it.startsWith("http") }

    fun canFill(tab: Tab?): Boolean = tab != null && !tab.isPrivate && state.value.settings.passwordManager && !privateLocked &&
        originOf(tab.url)?.let { vault.hasCredentialFor(it) } == true

    /** Fills only on an HTTPS page whose origin exactly matches the saved login, after authentication. */
    fun fillCurrentPage() {
        val tab = state.value.activeTab ?: return
        val origin = originOf(tab.url) ?: return
        if (!tab.url.startsWith("https://")) { message = "Passwords are only filled on secure (HTTPS) pages."; return }
        vaultOp({ vault.load() }) { all ->
            credentials = all; touchVault()
            val m = all.filter { it.origin == origin }
            when { m.isEmpty() -> message = "No saved login for this site."; m.size == 1 -> fill(m[0]); else -> fillChoices = m }
        }
    }

    fun fill(cr: Credential) {
        fillChoices = emptyList()
        activeSession()?.fillCredential(cr.username, cr.password) { ok -> message = if (ok) "Filled." else "No login form found on this page." }
    }

    /** Breach check with k-anonymity: only the first 5 characters of each password's SHA-1 are sent (Have I Been Pwned range API). */
    fun checkBreaches() {
        val list = credentials ?: run { message = "Unlock the vault first."; return }
        viewModelScope.launch {
            breachResult = runCatching {
                withContext(Dispatchers.IO) {
                    val n = list.count { cr -> pwned(cr.password) }
                    "$n of ${list.size} saved passwords appear in known data breaches."
                }
            }.getOrElse { "Breach check failed: ${it.message}" }
        }
    }

    private fun pwned(pw: String): Boolean {
        val h = PasswordTools.sha1(pw)
        val conn = URL("https://api.pwnedpasswords.com/range/${h.take(5)}").openConnection() as HttpURLConnection
        conn.setRequestProperty("Add-Padding", "true"); conn.connectTimeout = 8000; conn.readTimeout = 8000
        try {
            return conn.inputStream.bufferedReader().useLines { lines -> lines.any { it.startsWith(h.drop(5), ignoreCase = true) && (it.substringAfter(':').trim().toIntOrNull() ?: 0) > 0 } }
        } finally { conn.disconnect() }
    }

    fun exportPasswords(passphrase: String): String? {
        val list = credentials ?: return null
        val csv = PasswordTools.toCsv(list)
        return if (passphrase.isBlank()) csv else Sync.encrypt(csv, passphrase)
    }

    fun importPasswords(text: String, passphrase: String): Boolean {
        val csv = runCatching { if (Sync.isEncrypted(text)) Sync.decrypt(text, passphrase) else text }.getOrNull() ?: run { message = "Wrong passphrase."; return false }
        val incoming = PasswordTools.fromCsv(csv)
        if (incoming.isEmpty()) { message = "No passwords found in that file."; return false }
        vaultOp({
            val list = vault.load().toMutableList()
            incoming.forEach { n -> if (list.none { it.origin == n.origin && it.username == n.username }) list.add(n) }
            vault.save(list); list.toList()
        }) { credentials = it; touchVault(); message = "Imported ${incoming.size} login(s)." }
        return true
    }

    // ---- security check ----
    fun securityCheck(): List<CheckItem> {
        val creds = credentials
        return SecurityCheck.run(app, engine, state.value, vault.exists(), creds?.count { PasswordTools.isWeak(it.password) }, creds?.let { PasswordTools.reused(it).size }, breachResult)
    }

    fun extensionsWithAccess(url: String): List<InstalledExtension> = state.value.extensions.filter { e ->
        e.enabled && e.granted && e.allowedPatterns.any { p -> MatchPattern.toRegex(p)?.let { Regex(it).matches(url) } == true }
    }

    // ---- hardware keyboard shortcuts (Ctrl + key) ----
    fun onShortcut(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        if (e.isAltPressed && !e.isCtrlPressed) {
            return when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { back(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { forward(); true }
                else -> false
            }
        }
        if (!e.isCtrlPressed) {
            if (e.keyCode == KeyEvent.KEYCODE_ESCAPE && (commandBarOpen || findOpen || manage != null || reader != null || tabSwitcherOpen)) {
                commandBarOpen = false; findOpen = false; manage = null; reader = null; tabSwitcherOpen = false; return true
            }
            return false
        }
        val shift = e.isShiftPressed
        return when (e.keyCode) {
            KeyEvent.KEYCODE_T -> { if (shift) reopenClosed() else newTab(); true }
            KeyEvent.KEYCODE_N -> { if (shift) newTab(private = true); shift }
            KeyEvent.KEYCODE_W -> { state.value.activeTabId?.let(::closeTab); true }
            KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_K -> { commandBarOpen = true; true }
            KeyEvent.KEYCODE_R -> { reload(); true }
            KeyEvent.KEYCODE_F -> { findOpen = true; true }
            KeyEvent.KEYCODE_G -> { if (shift) geminiOpen = !geminiOpen; shift }
            KeyEvent.KEYCODE_D -> { toggleBookmark(); true }
            KeyEvent.KEYCODE_B -> { if (shift) sidebarVisible = !sidebarVisible; shift }
            KeyEvent.KEYCODE_J -> { manage = ManageSection.AgentCentre; true }
            KeyEvent.KEYCODE_COMMA -> { manage = ManageSection.Spaces; true }
            KeyEvent.KEYCODE_TAB -> { captureThumbs(); tabSwitcherOpen = true; true }
            else -> false
        }
    }

    override fun onCleared() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
    }

    companion object {
        private const val MAX_LIVE = 6
        private const val THUMB_W = 360
        private const val PAGE_JS = "(function(){return document.body?document.body.innerText:''})()"
        private const val SELECTION_JS = "(function(){return window.getSelection?window.getSelection().toString():''})()"
    }
}
