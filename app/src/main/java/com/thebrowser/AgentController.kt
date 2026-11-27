package com.thebrowser

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.thebrowser.core.agent.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.thebrowser.core.engine.EngineSession
import com.thebrowser.core.model.*
import com.thebrowser.core.reader.Reader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

class AgentApprovalRequest(val agent: String, val tool: String, val description: String, val host: String?, val decision: CompletableDeferred<Boolean>)
class AgentActivity(val name: String, val lastTool: String)

/**
 * External agent control (Claude, ChatGPT, Muse, any MCP client). OFF by default. Every call is authenticated,
 * scope-checked, policy-checked in code, logged, and visible. Agents get their own "Agent" Space by default.
 */
class AgentController(private val vm: BrowserViewModel) {
    var activity by mutableStateOf<AgentActivity?>(null); private set
    var approval by mutableStateOf<AgentApprovalRequest?>(null); private set
    var paused by mutableStateOf(false); private set
    var serverRunning by mutableStateOf(false); private set
    var lastNewToken by mutableStateOf<String?>(null)
    private val approvalLock = Mutex()
    private val owned = HashSet<String>()
    private val hits = HashMap<String, ArrayDeque<Long>>()
    private var burstStart = 0L
    private var lastTouch = 0L
    private val backend = Backend()
    private val mcp = McpServer(backend)
    /** Used by built-in agents: same browser tools, same safety checks, but with the agent's own permission profile. */
    suspend fun executeTool(name: String, args: JSONObject, caller: AgentCaller) = backend.execute(name, args, caller)
    private val server = AgentHttpServer(vm.viewModelScope) { handle(it) }
    private val settings get() = vm.state.value.settings.agent
    private fun now() = System.currentTimeMillis()

    fun applySettings() {
        val s = settings
        serverRunning = if (s.enabled) (server.running || server.start(s.port, s.allowLan)) else { server.stop(); false }
    }

    fun updateSettings(f: (AgentSettings) -> AgentSettings) {
        val before = settings
        vm.updateSettings { it.copy(agent = f(it.agent)) }
        if (settings.enabled != before.enabled || settings.port != before.port || settings.allowLan != before.allowLan) { server.stop(); applySettings() }
    }

    fun stopAll() { paused = true; approval?.decision?.complete(false); activity = null; vm.message = "All agent control stopped." }
    fun resume() { paused = false; burstStart = 0 }
    fun decide(ok: Boolean) { approval?.decision?.complete(ok) }

    fun createGrant(name: String, scopes: List<String>, days: Int): String {
        val token = AgentAuth.newToken()
        val g = AgentGrant(UUID.randomUUID().toString(), name.ifBlank { "Agent" }, AgentAuth.hash(token), scopes, now(), if (days > 0) now() + days * 86_400_000L else 0L)
        vm.update { it.copy(agentGrants = it.agentGrants + g) }
        return token
    }
    fun revoke(id: String) = vm.update { it.copy(agentGrants = it.agentGrants.filter { g -> g.id != id }) }

    private fun log(agent: String, tool: String, host: String?, outcome: String) =
        vm.update { it.copy(agentLog = (listOf(AgentLogEntry(now(), agent, tool, host.orEmpty(), outcome)) + it.agentLog).take(200)) }

    // ── HTTP entry ──
    private suspend fun handle(r: HttpRequest): HttpReply {
        val s = settings
        if (!s.enabled) return HttpReply(404, "{\"error\":\"agent control is off\"}")
        if (r.headers.containsKey("origin") || r.headers["sec-fetch-site"] == "cross-site") return HttpReply(403, "{\"error\":\"browser-originated requests are refused\"}")
        val host = r.headers["host"]?.lowercase().orEmpty()
        if (!s.allowLan && host !in setOf("127.0.0.1:${s.port}", "localhost:${s.port}", "[::1]:${s.port}")) return HttpReply(403, "{\"error\":\"bad host\"}")
        if (r.path == "/health" && r.method == "GET") return HttpReply(200, "{\"ok\":true}")
        if (r.path != "/mcp" && r.path != "/") return HttpReply(404, "{\"error\":\"not found\"}")
        if (r.method != "POST") return HttpReply(405, "{\"error\":\"POST only\"}", headers = mapOf("Allow" to "POST"))
        val token = r.headers["authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
        val grant = AgentAuth.verify(vm.state.value.agentGrants, token, now())
            ?: return HttpReply(401, "{\"error\":\"unauthorised\"}", headers = mapOf("WWW-Authenticate" to "Bearer"))
        val q = hits.getOrPut(grant.id) { ArrayDeque() }
        q.addLast(now()); while (q.isNotEmpty() && now() - q.first() > 10_000) q.removeFirst()
        if (q.size > 40) return HttpReply(429, "{\"error\":\"slow down\"}")
        vm.update { st -> st.copy(agentGrants = st.agentGrants.map { if (it.id == grant.id) it.copy(lastUsed = now()) else it }) }
        val reply = mcp.handle(r.body, AgentCaller(grant.id, grant.name, grant.scopes.toSet()))
        return if (reply == null) HttpReply(202) else HttpReply(200, reply)
    }

    suspend fun requestApproval(agent: String, tool: String, desc: String, host: String?): Boolean = approvalLock.withLock {
        val req = AgentApprovalRequest(agent, tool, desc, host, CompletableDeferred())
        withContext(Dispatchers.Main) { approval = req }
        val ok = withTimeoutOrNull(90.seconds) { req.decision.await() } ?: false
        withContext(Dispatchers.Main) { approval = null }
        log(agent, tool, host, if (ok) "approved" else "declined")
        ok
    }

    private suspend fun evalJs(s: EngineSession, js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> s.evaluate(js) { raw -> c.resumeWith(Result.success(Reader.pageText(raw, 400_000))) } }
    }

    private suspend fun waitLoaded(tabId: String) { delay(250.milliseconds); var i = 0; while ((vm.progress[tabId] ?: 100) < 100 && i++ < 100) delay(150.milliseconds) }

    private fun agentSpaceId(): String {
        vm.state.value.spaces.firstOrNull { it.name == "Agent" && it.emoji == "🤖" }?.let { return it.id }
        val sp = Space(UUID.randomUUID().toString(), "Agent", 0xFF636E72, "🤖", vm.state.value.activeSpace.profileId)
        vm.update { it.copy(spaces = it.spaces + sp) }
        return sp.id
    }

    private fun visibleTabs(): List<Tab> {
        val a = vm.state.value.spaces.firstOrNull { it.name == "Agent" && it.emoji == "🤖" }?.id
        return vm.state.value.tabs.filter { !it.isPrivate && (settings.allowAllTabs || it.spaceId == a) }
    }

    private fun resolve(id: String?): Tab? = if (id.isNullOrBlank()) visibleTabs().filter { it.id in owned }.maxByOrNull { it.lastActive } ?: visibleTabs().firstOrNull() else visibleTabs().firstOrNull { it.id == id }

    private suspend fun session(t: Tab): EngineSession = withContext(Dispatchers.Main) { vm.sessions[t.id] ?: vm.sessionFor(vm.uiContext ?: vm.getApplication(), t) }

    /** New tab in the Agent Space that does NOT steal focus from the person using the browser. */
    private suspend fun openBackground(url: String): Tab = withContext(Dispatchers.Main) {
        val t = Tab(UUID.randomUUID().toString(), agentSpaceId(), url, lastActive = now())
        vm.update { it.copy(tabs = it.tabs + t) }; owned += t.id; t
    }

    private inner class Backend : ToolBackend {
        private fun err(t: String) = AgentResult(t, isError = true)

        override suspend fun execute(name: String, args: JSONObject, caller: AgentCaller): AgentResult {
            val s = settings
            if (paused) return err("The user paused agent control. Stop and tell the user.")
            if (!s.enabled && caller.profile == null) return err("Agent control is off.")
            val t0 = now()
            if (t0 - lastTouch > 300_000) burstStart = t0
            lastTouch = t0
            if (s.autoStopMinutes > 0 && t0 - burstStart > s.autoStopMinutes * 60_000L) { paused = true; return err("Agent control reached its ${s.autoStopMinutes}-minute limit and was paused. The user can resume it.") }
            withContext(Dispatchers.Main) { activity = AgentActivity(caller.name, name) }
            vm.viewModelScope.launch { delay(20.seconds); if (now() - lastTouch >= 19_000) activity = null }
            val def = AgentDef(caller.id, caller.name, "", "", AgentCatalog.toolsFor(caller.scopes).map { it.name },
                caller.profile?.let { p -> p.copy(scopes = caller.scopes.toList(), blockedDomains = (p.blockedDomains + s.blockedDomains).distinct(), allowPurchases = p.allowPurchases && s.allowPurchases) }
                    ?: PermissionProfile(scopes = caller.scopes.toList(), approvalMode = s.mode, allowPurchases = s.allowPurchases, blockedDomains = s.blockedDomains))
            suspend fun gate(risk: ToolRisk, desc: String): String? = when (val d = PolicyEngine.check(name, def, risk, emptyList())) {
                PolicyDecision.Allow -> null
                is PolicyDecision.Deny -> "Blocked by Hydrogen: ${d.reason}"
                is PolicyDecision.Approve -> if (requestApproval(caller.name, name, "${d.reason} $desc".trim(), risk.host)) null else "The user declined this action."
            }
            val out = try { run(name, args, caller, ::gate) } catch (e: Exception) { err(e.message ?: "failed") }
            log(caller.name, name, resolve(args.optString("tabId").ifBlank { null })?.let { com.thebrowser.core.atc.AirTrafficControl.hostOf(it.url) }, if (out.isError) "error: ${out.text.take(80)}" else "ok")
            return out
        }

        private suspend fun run(name: String, a: JSONObject, caller: AgentCaller, gate: suspend (ToolRisk, String) -> String?): AgentResult {
            fun noTab() = err("No agent tab yet. Call browser_open_url first (or pass a tabId from browser_list_tabs).")
            when (name) {
                "browser_list_tabs" -> return AgentResult(visibleTabs().joinToString("\n") { "${it.id} | ${it.displayTitle} | ${it.url}" }.ifBlank { "No tabs available to this agent." })
                "browser_new_tab" -> {
                    val url = a.optString("url")
                    if (url.isNotBlank()) { if (!url.startsWith("http")) return err("Only http(s) URLs."); gate(ToolRisk(host = com.thebrowser.core.atc.AirTrafficControl.hostOf(url)), "open $url")?.let { return err(it) } }
                    val t = openBackground(url); if (url.isNotBlank()) { session(t); waitLoaded(t.id) }
                    return AgentResult("Opened tab ${t.id}")
                }
                "browser_open_url" -> {
                    val url = a.optString("url"); if (!url.startsWith("http://") && !url.startsWith("https://")) return err("Only http(s) URLs.")
                    val host = com.thebrowser.core.atc.AirTrafficControl.hostOf(url)
                    gate(ToolRisk(host = host), "open $url")?.let { return err(it) }
                    val existing = if (a.optBoolean("newTab")) null else resolve(a.optString("tabId").ifBlank { null })
                    val t = existing ?: openBackground("")
                    withContext(Dispatchers.Main) { vm.loadInTab(t.id, url) }; session(t).let { s -> withContext(Dispatchers.Main) { if (existing == null) s.load(url) } }
                    waitLoaded(t.id)
                    vm.securityBlocks[t.id]?.let { return err("Hydrogen's security warning stopped this page: ${it.title}. The user must decide; do not try to bypass it.") }
                    return AgentResult("Opened ${vm.tabOf(t.id)?.url} — title: ${vm.tabOf(t.id)?.displayTitle}. Tab id: ${t.id}")
                }
                "browser_switch_tab" -> { val t = resolve(a.optString("tabId")) ?: return noTab(); withContext(Dispatchers.Main) { vm.selectTab(t.id) }; return AgentResult("Switched.") }
                "browser_close_tab" -> {
                    val t = resolve(a.optString("tabId")) ?: return noTab()
                    if (t.id !in owned && !settings.allowAllTabs) return err("Agents may only close tabs they opened.")
                    withContext(Dispatchers.Main) { vm.closeTab(t.id) }; owned -= t.id; return AgentResult("Closed.")
                }
                "browser_wait" -> { delay((a.optDouble("seconds", 2.0).coerceIn(0.0, 10.0) * 1000).toLong().milliseconds); return AgentResult("Waited.") }
            }
            val tab = resolve(a.optString("tabId").ifBlank { null }) ?: return noTab()
            val sess = session(tab)
            val host = com.thebrowser.core.atc.AirTrafficControl.hostOf(tab.url)
            vm.securityBlocks[tab.id]?.let { return err("A Hydrogen security warning is showing on this tab (${it.title}). The user must resolve it.") }
            when (name) {
                "browser_read_page" -> {
                    gate(ToolRisk(host = host), "read ${host.orEmpty()}")?.let { return err(it) }
                    val text = AgentJs.format(evalJs(sess, AgentJs.call("snapshot")))
                    val flags = Injection.scan(text)
                    return AgentResult(Injection.wrap(tab.url, text, flags))
                }
                "browser_screenshot" -> {
                    val bmp: Bitmap = withContext(Dispatchers.Main) { sess.snapshot(900) } ?: return err("Couldn't capture the page.")
                    val b = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 90, b)
                    return AgentResult("Screenshot of ${tab.displayTitle}", listOf(Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP)))
                }
                "browser_back" -> { withContext(Dispatchers.Main) { sess.goBack() }; waitLoaded(tab.id); return AgentResult("Went back.") }
                "browser_forward" -> { withContext(Dispatchers.Main) { sess.goForward() }; waitLoaded(tab.id); return AgentResult("Went forward.") }
                "browser_reload" -> { withContext(Dispatchers.Main) { sess.reload() }; waitLoaded(tab.id); return AgentResult("Reloaded.") }
                "browser_scroll" -> { gate(ToolRisk(host = host), "")?.let { return err(it) }; return AgentResult(evalJs(sess, AgentJs.call("scroll", a.optString("direction", "down")))) }
                "browser_press_key" -> { gate(ToolRisk(host = host), "")?.let { return err(it) }; return AgentResult(evalJs(sess, AgentJs.call("press", a.optString("key", "Enter")))) }
                "browser_fill_login" -> {
                    if (host == null || !tab.url.startsWith("https://")) return err("Saved logins are only filled on https pages.")
                    if (!requestApproval(caller.name, name, "wants you to unlock the password manager and fill your saved login on $host. It will never see the password.", host)) return err("The user declined.")
                    withContext(Dispatchers.Main) { vm.selectTab(tab.id); vm.fillCurrentPage() }
                    return AgentResult("The user was asked to unlock the password manager. Call browser_read_page to see whether the form was filled. You never receive the password.")
                }
                "browser_click", "browser_type", "browser_select" -> {
                    val ref = a.optString("ref"); if (ref.isBlank()) return err("ref is required.")
                    // The host always comes from the tab itself, never from what the page says about itself; the address can only add risk.
                    val risk = UrlRisk.escalate(AgentJs.risk(evalJs(sess, AgentJs.call("inspect", ref))).copy(host = host), tab.url)
                    if (evalJs(sess, AgentJs.call("inspect", ref)).contains("\"error\"")) return err("ref $ref not found. Call browser_read_page again.")
                    gate(risk, "“${risk.label}” on ${risk.host}")?.let { return err(it) }
                    val r = when (name) {
                        "browser_click" -> evalJs(sess, AgentJs.call("click", ref))
                        "browser_type" -> evalJs(sess, AgentJs.call("type", ref, a.optString("text"), a.optBoolean("submit")))
                        else -> evalJs(sess, AgentJs.call("select", ref, a.optString("value")))
                    }
                    waitLoaded(tab.id)
                    return if (r.contains("\"error\"")) err(JSONObject(r).optString("error")) else AgentResult("Done. Now on ${vm.tabOf(tab.id)?.url}. Call browser_read_page to see the result.")
                }
            }
            return err("Unsupported tool.")
        }
    }
}
