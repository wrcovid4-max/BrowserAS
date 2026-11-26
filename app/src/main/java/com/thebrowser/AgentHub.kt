package com.thebrowser

import android.content.Intent
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import com.thebrowser.core.agent.*
import com.thebrowser.core.ai.ToolSpec
import com.thebrowser.core.model.*
import com.thebrowser.core.stats.ProtectionStats
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/**
 * Runs built-in and custom agents, teams and deep research as background jobs, with approvals, audit, memory, history and
 * integrations. Jobs keep running while their panel is hidden; the adaptive UI only decides what the human sees.
 */
class AgentHub(private val vm: BrowserViewModel) {
    val jobs = mutableStateListOf<JobHandle>()
    var humanAt by mutableLongStateOf(0L); private set
    var openJob by mutableStateOf<String?>(null)
    private val app get() = vm.getApplication<android.app.Application>()
    private val container get() = (app as TheBrowserApp).container
    private val settings get() = vm.state.value.settings
    private val runtime = AgentRuntime(vm.gateway, HubHost(), Gate(), { settings.agent.blockedDomains },
        { j, tool, host, outcome -> vm.update { s -> s.copy(agentLog = (listOf(AgentLogEntry(System.currentTimeMillis(), j.agent.name, tool, host.orEmpty(), outcome)) + s.agentLog).take(200)) } })
    private val orchestrator = Orchestrator(vm.gateway, runtime, { Templates.all + vm.state.value.agents })

    init {
        container.hubStopper = { stopAll() }
        val s = vm.state.value
        vm.update { it.copy(memories = MemoryRules.purge(it.memories, it.settings.ai.memoryRetentionDays, System.currentTimeMillis()), jobs = it.jobs.filter { j -> System.currentTimeMillis() - j.finishedAt < it.settings.ai.keepJobHistoryDays * 86_400_000L }) }
    }

    fun allAgents(): List<AgentDef> = Templates.all + vm.state.value.agents

    // ── adaptive UI ──
    fun onHumanInteraction() {
        humanAt = System.currentTimeMillis()
        jobs.filter { !it.finished && it.visible }.forEach { it.visible = false } // never force a workspace on the person; the task keeps running
    }

    fun snapshot(nowMs: Long): AdaptiveSnapshot = AdaptiveUiController.derive(UiInputs(
        assistantOpen = vm.geminiOpen,
        jobs = jobs.map { JobUi(it.id, it.kind, !it.finished && it.state.value != JobState.PAUSED, it.state.value == JobState.WAITING_APPROVAL || vm.agents.approval?.agent == it.agent.name, it.visible, it.finished && !it.seen) },
        humanTyping = vm.commandBarOpen || vm.findOpen, humanBusy = nowMs - humanAt < 4000, externalApproval = vm.agents.approval != null))

    // ── jobs ──
    fun start(agent: AgentDef, goal: String, kind: String = "single", pageContext: String? = null): JobHandle {
        val a = agent.copy(profile = agent.profile.copy(usePrivateTabs = false))
        val h = JobHandle(UUID.randomUUID().toString(), a, goal.trim(), kind).also { it.pageContext = pageContext }
        jobs.add(0, h); openJob = h.id
        keepAlive(true)
        h.coroutine = vm.viewModelScope.launch(Dispatchers.Default) {
            try { if (kind == "multi") orchestrator.run(h) else runtime.run(h) } finally { withContext(NonCancellable + Dispatchers.Main) { finish(h) } }
        }
        return h
    }

    private fun finish(h: JobHandle) {
        val rec = JobRecord(h.id, h.agent.id, h.agent.name, h.goal, h.kind, h.state.value.name, h.startedAt, h.finishedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), h.result.value, h.steps.value, h.sources.all(), h.inputTokens.get(), h.outputTokens.get())
        vm.update { it.copy(jobs = (listOf(rec) + it.jobs).take(100)) }
        if (jobs.none { !it.finished }) keepAlive(false)
        if (!h.visible && settings.ai.notifyOnComplete) vm.message = "${h.agent.name} finished: ${h.goal.take(40)}"
        settings.ai.let { _ -> vm.state.value.integrations.filter { it.kind == "webhook" && it.enabled }.forEach { w -> vm.viewModelScope.launch(Dispatchers.IO) { Webhook.send(w.url, JSONObject().put("agent", h.agent.name).put("goal", h.goal).put("state", h.state.value.name).put("result", h.result.value.take(4000))) } } }
    }

    private fun keepAlive(on: Boolean) {
        if (!settings.ai.agentKeepAlive) return
        runCatching { if (on) app.startForegroundService(Intent(app, AgentService::class.java).putExtra("text", "${jobs.count { !it.finished }} task(s) running")) else app.stopService(Intent(app, AgentService::class.java)) }
    }

    fun pause(h: JobHandle) { h.pause.pause(); h.state.value = JobState.PAUSED; h.log("paused", "Paused by the user") }
    fun resume(h: JobHandle) { h.pause.resume(); h.state.value = JobState.RUNNING; h.log("resumed", "Resumed") }
    fun cancel(h: JobHandle) { h.coroutine?.cancel(); h.pause.resume() }
    fun stopAll() { jobs.filter { !it.finished }.forEach(::cancel); vm.agents.stopAll() }
    fun clearHistory() = vm.update { it.copy(jobs = emptyList()) }

    // ── agents (Studio) ──
    fun saveAgent(def: AgentDef) = vm.update { s -> s.copy(agents = s.agents.filter { it.id != def.id } + def.copy(builtIn = false)) }
    fun deleteAgent(id: String) = vm.update { s -> s.copy(agents = s.agents.filter { it.id != id }) }

    // ── memory (user-controlled) ──
    fun addMemory(text: String, scope: String, project: String): String? {
        MemoryRules.sensitiveReason(text)?.let { return "Not saved: $it." }
        vm.update { it.copy(memories = listOf(MemoryItem(UUID.randomUUID().toString(), scope, project, text.trim(), "user", System.currentTimeMillis())) + it.memories) }; return null
    }
    fun editMemory(id: String, text: String): String? { MemoryRules.sensitiveReason(text)?.let { return "Not saved: $it." }; vm.update { s -> s.copy(memories = s.memories.map { if (it.id == id) it.copy(text = text.trim()) else it }) }; return null }
    fun deleteMemory(id: String) = vm.update { s -> s.copy(memories = s.memories.filter { it.id != id }) }
    fun clearMemory() = vm.update { it.copy(memories = emptyList()) }
    fun setRetention(days: Int) = vm.updateSettings { it.copy(ai = it.ai.copy(memoryRetentionDays = days)) }
    fun saveResultToMemory(h: JobHandle): String? = addMemory("From \"${h.goal.take(60)}\": " + h.result.value.take(500), "long", "")

    // ── integrations ──
    private val mcpClients = HashMap<String, McpClient>()
    private fun mcp(i: IntegrationConfig) = mcpClients.getOrPut(i.id) { McpClient(i.url, container.secrets.get("integration_${i.id}")) }
    fun addIntegration(kind: String, name: String, url: String, token: String): String {
        val id = UUID.randomUUID().toString()
        if (token.isNotBlank()) container.secrets.put("integration_$id", token.trim())
        vm.update { it.copy(integrations = it.integrations + IntegrationConfig(id, kind, name.ifBlank { url }, url.trim(), enabled = false)) }; return id
    }
    fun removeIntegration(id: String) { container.secrets.remove("integration_$id"); mcpClients.remove(id); vm.update { s -> s.copy(integrations = s.integrations.filter { it.id != id }) } }
    fun setIntegration(id: String, f: (IntegrationConfig) -> IntegrationConfig) { mcpClients.remove(id); vm.update { s -> s.copy(integrations = s.integrations.map { if (it.id == id) f(it) else it }) } }
    suspend fun listMcpTools(i: IntegrationConfig): List<ToolSpec> = withContext(Dispatchers.IO) { mcp(i).listTools() }

    // ── tools ──
    private val researchSpecs = listOf(
        ToolSpec("web_search", "Search the web (no sign-in). Returns titles, URLs and snippets.", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
        ToolSpec("fetch_page", "Fetch a public web page as text (no sign-in, no cookies). Returns a numbered source you must cite as [n].", """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""),
    )
    private val memorySpecs = listOf(
        ToolSpec("memory_search", "Search the user's saved memory for relevant facts or preferences.", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
        ToolSpec("memory_propose", "Ask the user to save a fact or preference to memory. The user must approve. Never include secrets.", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""),
    )

    private inner class HubHost : ToolHost {
        private fun integrationTools(def: AgentDef): List<Pair<IntegrationConfig, String>> =
            if ("integration" !in def.profile.scopes) emptyList() else vm.state.value.integrations.filter { it.kind == "mcp" && it.enabled }.flatMap { i -> i.allowedTools.map { i to it } }
        override fun specs(def: AgentDef): List<ToolSpec> {
            val own = AgentCatalog.specs(def.tools) + researchSpecs.filter { it.name in def.tools } + memorySpecs.filter { it.name in def.tools }
            return own + integrationTools(def).map { (i, t) -> ToolSpec("mcp_${i.id.take(6)}_$t", "Tool \"$t\" from ${i.name} (connected service).", """{"type":"object","properties":{"input":{"type":"string","description":"JSON arguments"}}}""") }
        }
        override fun handlesOwnApproval(tool: String) = tool.startsWith("browser_")
        override suspend fun preflight(tool: String, args: JSONObject, job: JobHandle): ToolRisk = when {
            tool == "fetch_page" -> ToolRisk(host = com.thebrowser.core.atc.AirTrafficControl.hostOf(args.optString("url")))
            tool == "memory_propose" -> ToolRisk(message = true, label = "save to memory: ${args.optString("text").take(80)}")
            tool.startsWith("mcp_") -> ToolRisk(submit = true, label = "use a connected service")
            else -> ToolRisk()
        }
        override suspend fun execute(tool: String, args: JSONObject, job: JobHandle): ToolOutput = when {
            tool.startsWith("browser_") -> {
                val d = job.agent
                val r = vm.agents.executeTool(tool, args, AgentCaller(job.id, d.name, d.profile.scopes.toSet(), d.profile))
                ToolOutput(r.text, r.isError, untrusted = tool == "browser_read_page")
            }
            tool == "web_search" -> runCatching { withContext(Dispatchers.IO) { WebFetch.search(args.optString("query")) } }
                .fold({ ToolOutput(it.joinToString("\n\n") { r -> "${r.title}\n${r.url}\n${r.snippet}" }.ifBlank { "No results." }, untrusted = true) }, { ToolOutput("Search failed: ${it.message}", true) })
            tool == "fetch_page" -> runCatching { withContext(Dispatchers.IO) { WebFetch.fetch(args.optString("url")) } }
                .fold({ val n = job.sources.add(it.url, it.title, it.text.take(240)); ToolOutput("Source [$n] ${it.title}\n${it.url}\n\n${it.text}", untrusted = true) }, { ToolOutput("Fetch failed: ${it.message}", true) })
            tool == "memory_search" -> ToolOutput(MemoryRules.search(vm.state.value.memories, args.optString("query"), null).joinToString("\n") { "- ${it.text}" }.ifBlank { "Nothing saved matches." })
            tool == "memory_propose" -> addMemory(args.optString("text"), "long", "").let { err -> if (err == null) ToolOutput("Saved to memory with the user's approval.") else ToolOutput(err, true) }
            tool.startsWith("mcp_") -> {
                val hit = integrationTools(job.agent).firstOrNull { (i, t) -> "mcp_${i.id.take(6)}_$t" == tool }
                if (hit == null) ToolOutput("That connected tool isn't enabled.", true)
                else runCatching { withContext(Dispatchers.IO) { mcp(hit.first).call(hit.second, runCatching { JSONObject(args.optString("input", "{}")) }.getOrDefault(JSONObject())) } }
                    .fold({ ToolOutput(it, untrusted = true) }, { ToolOutput("Connected service failed: ${it.message}", true) })
            }
            else -> ToolOutput("Unknown tool.", true)
        }
    }

    private inner class Gate : ApprovalGate {
        override suspend fun request(job: JobHandle, tool: String, description: String, risk: ToolRisk): Boolean {
            val ok = vm.agents.requestApproval(job.agent.name, tool, description, risk.host)
            job.log("approval", tool, description, if (ok) "approved" else "declined"); return ok
        }
    }
}
