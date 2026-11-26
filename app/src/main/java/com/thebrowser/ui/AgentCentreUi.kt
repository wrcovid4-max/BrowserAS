package com.thebrowser.ui

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.core.agent.*
import com.thebrowser.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private fun pdfBytes(text: String): ByteArray {
    val doc = PdfDocument(); val paint = Paint().apply { textSize = 11f }
    val lines = text.lines().flatMap { l -> l.chunked(92).ifEmpty { listOf("") } }
    lines.chunked(52).forEachIndexed { i, page ->
        val p = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
        page.forEachIndexed { r, l -> p.canvas.drawText(l, 40f, 50f + r * 14f, paint) }; doc.finishPage(p)
    }
    return java.io.ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
}

private fun record(h: JobHandle) = JobRecord(h.id, h.agent.id, h.agent.name, h.goal, h.kind, h.state.value.name, h.startedAt, System.currentTimeMillis(), h.result.value, h.steps.value, h.sources.all())

/** Compact by default: a small chip while something runs in the background. A panel appears only when the task, an approval or a result needs you, and never over typing or touching the page. */
@Composable
fun AgentDock(vm: BrowserViewModel) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val snap = vm.hub.snapshot(now)
    val running = vm.hub.jobs.count { !it.finished }
    if (snap.indicator) Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp, end = 8.dp), contentAlignment = Alignment.TopEnd) {
        AssistChip({ vm.hub.jobs.firstOrNull { !it.finished }?.let { it.visible = true; vm.hub.openJob = it.id }; vm.hub.onHumanInteraction().also { } ; vm.hub.jobs.firstOrNull { !it.finished }?.visible = true },
            { Text(if (snap.approvalAsChip) "🤖 approval waiting" else "🤖 $running working") })
    }
    if (snap.panel == Panel.APPROVAL) vm.agents.approval?.let { AgentApprovalDialog(vm, it) }
    val job = vm.hub.jobs.firstOrNull { it.id == vm.hub.openJob && it.visible } ?: vm.hub.jobs.firstOrNull { it.visible && (!it.finished || !it.seen) }
    if (job != null && snap.panel in setOf(Panel.AGENT, Panel.MULTI, Panel.RESEARCH, Panel.RESULTS)) JobSheet(vm, job)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobSheet(vm: BrowserViewModel, h: JobHandle) {
    ModalBottomSheet(onDismissRequest = { h.visible = false; if (h.finished) h.seen = true }) { JobMonitor(vm, h, Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) }
}

@Composable
fun JobMonitor(vm: BrowserViewModel, h: JobHandle, modifier: Modifier = Modifier) {
    val state by h.state.collectAsStateWithLifecycle(); val steps by h.steps.collectAsStateWithLifecycle(); val result by h.result.collectAsStateWithLifecycle(); val workers by h.workers.collectAsStateWithLifecycle()
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u -> u?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(pdfBytes(ReportBuilder.markdown(record(h)))) } } }
    LaunchedEffect(state) { if (h.finished) h.seen = true }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("${h.agent.name}${if (h.kind == "multi") " · team" else if (h.kind == "research") " · deep research" else ""}", fontSize = 16.sp)
            Text(h.goal, fontSize = 13.sp); Text("State: ${state.name.lowercase().replace('_', ' ')} · ${(steps.size)} steps · ${h.inputTokens.get() + h.outputTokens.get()} tokens", fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                if (!h.finished) { if (state == JobState.PAUSED) Button({ vm.hub.resume(h) }) { Text("Resume") } else OutlinedButton({ vm.hub.pause(h) }) { Text("Pause") }; Button({ vm.hub.cancel(h) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("Stop") } }
                OutlinedButton({ h.visible = false }) { Text(if (h.finished) "Close" else "Hide (keeps running)") }
            }
        }
        if (workers.isNotEmpty()) { item { Text("Team", fontSize = 12.sp) }; items(workers) { w -> Text("• ${w.title} — ${w.agent} — ${w.state.name.lowercase()}", fontSize = 12.sp) } }
        if (result.isNotBlank()) item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("Result", fontSize = 12.sp); Text(result, fontSize = 13.sp)
                if (h.finished) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_TEXT, ReportBuilder.markdown(record(h))), "Share report")) }) { Text("Share Markdown") }
                    TextButton({ exporter.launch("report-${h.id.take(6)}.pdf") }) { Text("Save PDF") }
                    TextButton({ vm.message = vm.hub.saveResultToMemory(h) ?: "Saved to memory." }) { Text("Save to memory") }
                } } }
        }
        val srcs = h.sources.all()
        if (srcs.isNotEmpty()) { item { Text("Sources", fontSize = 12.sp) }; items(srcs) { s -> Text("[${s.n}] ${s.title.ifBlank { s.url }} — ${s.url}", fontSize = 11.sp) } }
        item { Text("Activity", fontSize = 12.sp) }
        items(steps.takeLast(40).reversed()) { s -> Text("${when (s.kind) { "tool" -> "🔧"; "blocked" -> "⛔"; "warning" -> "⚠️"; "approval" -> "✋"; "plan" -> "📝"; "verify" -> "✅"; else -> "•" }} ${s.title}${if (s.approval.isNotBlank()) " (${s.approval})" else ""}${if (s.detail.isNotBlank() && s.kind in setOf("plan", "warning", "verify", "error")) "\n${s.detail.take(300)}" else ""}", fontSize = 11.sp) }
    }
}

@Composable
fun AgentCentreScreen(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Run", "Tasks", "Agents", "Memory", "Services")
    ScrollableTabRow(tab, edgePadding = 0.dp) { titles.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
    Box(Modifier.padding(top = 8.dp)) { when (tab) { 0 -> RunTab(vm); 1 -> TasksTab(vm); 2 -> StudioTab(vm); 3 -> MemoryTab(vm); else -> ServicesTab(vm) } }
}

@Composable
private fun RunTab(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val all = vm.hub.allAgents()
    var sel by remember { mutableStateOf("research") }; var goal by remember { mutableStateOf("") }; var team by remember { mutableStateOf(false) }; var withPage by remember { mutableStateOf(false) }
    val agent = all.firstOrNull { it.id == sel } ?: all.first()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Hydrogen stays a normal browser. Tasks run in the background; nothing opens over your browsing unless you ask or it needs you.", fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { all.forEach { a -> FilterChip(sel == a.id, { sel = a.id }, { Text(a.name) }) } }
            Text(agent.purpose, fontSize = 13.sp); Text("Tools: ${agent.tools.size} · ${if (agent.profile.readOnly) "read-only" else "can interact"} · approvals: ${agent.profile.approvalMode}", fontSize = 11.sp)
            Text(agent.instructions.substringAfter("Known limits:", "").trim().takeIf { it.isNotBlank() }?.let { "Limits: $it" } ?: "", fontSize = 11.sp, color = Color(0xFFE17055))
            OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text("What should it do?") }, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(team, { team = it }); Text("Team: split the task across specialists and cross-check", Modifier.padding(start = 8.dp), fontSize = 13.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(withPage, { withPage = it }); Text("Give it the current page as context (not for private tabs)", Modifier.padding(start = 8.dp), fontSize = 13.sp) }
            Text("Sends your goal and what it reads to your chosen AI provider (Settings → AI). Page content is fenced as untrusted; passwords and card numbers are never given to it.", fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    val tab = state.activeTab
                    vm.hub.start(if (team) all.first { it.id == "research" }.copy(name = "Supervisor") else agent, goal, if (team) "multi" else if (agent.id == "research") "research" else "single", if (withPage && tab?.isPrivate != true) tab?.url else null)
                    goal = ""; vm.manage = null
                }, enabled = goal.isNotBlank()) { Text("Start") }
                OutlinedButton({ vm.hub.stopAll() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB3261E))) { Text("Emergency stop") }
            }
        }
    }
}

@Composable
private fun TasksTab(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<JobHandle?>(null) }
    open?.let { h -> Column { TextButton({ open = null }) { Text("‹ Tasks") }; JobMonitor(vm, h) }; return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Running and recent", fontSize = 12.sp) }
        items(vm.hub.jobs.toList()) { h -> TextButton({ open = h }) { Text("${if (h.finished) "✔" else "⏳"} ${h.agent.name}: ${h.goal.take(60)}", fontSize = 13.sp) } }
        item { Text("History (kept ${state.settings.ai.keepJobHistoryDays} days)", fontSize = 12.sp) }
        items(state.jobs) { j -> Column(Modifier.fillMaxWidth()) { Text("${j.state.lowercase()} · ${j.agentName}: ${j.goal.take(70)}", fontSize = 13.sp); Text("${j.steps.size} steps · ${j.sources.size} sources · ${j.inputTokens + j.outputTokens} tokens", fontSize = 11.sp); Text(j.result.take(200), fontSize = 11.sp) } }
        item { TextButton({ vm.hub.clearHistory() }) { Text("Clear history") } }
    }
}

@Composable
private fun StudioTab(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<AgentDef?>(null) }
    edit?.let { d -> StudioEditor(vm, d) { edit = null }; return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Built-in specialists can be copied and changed. Your agents are saved on this device.", fontSize = 12.sp); Button({ edit = AgentDef(UUID.randomUUID().toString(), "My agent", "", "", listOf("browser_read_page", "browser_open_url", "web_search", "fetch_page"), PermissionProfile(scopes = listOf("read", "navigate", "research"), readOnly = true)) }) { Text("New agent") } }
        items(vm.hub.allAgents()) { a -> Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(a.name + if (a.builtIn) "  (built-in)" else "", fontSize = 14.sp); Text(a.purpose, fontSize = 11.sp) }
            TextButton({ edit = if (a.builtIn) a.copy(id = UUID.randomUUID().toString(), name = a.name + " (copy)", builtIn = false) else a }) { Text(if (a.builtIn) "Copy" else "Edit") }
            if (!a.builtIn) TextButton({ vm.hub.deleteAgent(a.id) }) { Text("Delete") } } }
    }
}

@Composable
private fun StudioEditor(vm: BrowserViewModel, d0: AgentDef, close: () -> Unit) {
    var d by remember { mutableStateOf(d0) }
    val toolNames = AgentCatalog.tools.map { it.name } + listOf("web_search", "fetch_page", "memory_search", "memory_propose")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            OutlinedTextField(d.name, { d = d.copy(name = it) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(d.purpose, { d = d.copy(purpose = it) }, Modifier.fillMaxWidth(), label = { Text("Purpose") })
            OutlinedTextField(d.instructions, { d = d.copy(instructions = it) }, Modifier.fillMaxWidth(), label = { Text("Instructions") }, minLines = 3)
            Text("Permissions the agent may ever use", fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("read", "navigate", "tabs", "interact", "research", "memory", "integration").forEach { s -> FilterChip(s in d.profile.scopes, { d = d.copy(profile = d.profile.copy(scopes = if (s in d.profile.scopes) d.profile.scopes - s else d.profile.scopes + s)) }, { Text(s) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(d.profile.readOnly, { d = d.copy(profile = d.profile.copy(readOnly = it)) }); Text("Read-only: can't click, type or submit", Modifier.padding(start = 8.dp), fontSize = 13.sp) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("ask" to "Ask every action", "risky" to "Ask for risky", "auto" to "Auto (not payments/sending/deleting)").forEach { (m, l) -> FilterChip(d.profile.approvalMode == m, { d = d.copy(profile = d.profile.copy(approvalMode = m)) }, { Text(l) }) } }
            OutlinedTextField(d.profile.allowedDomains.joinToString(", "), { v -> d = d.copy(profile = d.profile.copy(allowedDomains = v.split(',').map(String::trim).filter(String::isNotEmpty))) }, Modifier.fillMaxWidth(), label = { Text("Only these sites (blank = any)") }, singleLine = true)
            OutlinedTextField(d.profile.blockedDomains.joinToString(", "), { v -> d = d.copy(profile = d.profile.copy(blockedDomains = v.split(',').map(String::trim).filter(String::isNotEmpty))) }, Modifier.fillMaxWidth(), label = { Text("Never these sites") }, singleLine = true)
            Text("Tools (a tool is only used if its permission above is on)", fontSize = 12.sp)
        }
        items(toolNames) { t -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(t in d.tools, { on -> d = d.copy(tools = if (on) d.tools + t else d.tools - t) }); Text(t, fontSize = 12.sp) } }
        item {
            Text("Max steps: ${d.maxSteps}", fontSize = 12.sp); Slider(d.maxSteps.toFloat(), { d = d.copy(maxSteps = it.toInt()) }, valueRange = 3f..40f)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(d.verify, { d = d.copy(verify = it) }); Text("Verify the result before reporting", Modifier.padding(start = 8.dp), fontSize = 13.sp) }
            Text("Purchases are never allowed for custom agents here; payments always need you.", fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ vm.hub.saveAgent(d.copy(profile = d.profile.copy(allowPurchases = false))); close() }) { Text("Save") }; OutlinedButton({ vm.hub.saveAgent(d); vm.hub.start(d, "Test: introduce yourself and list the tools you can use. Do not use any tool.", "single"); close() }) { Text("Save & test") }; TextButton(close) { Text("Cancel") } }
        }
    }
}

@Composable
private fun MemoryTab(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }; var scope by remember { mutableStateOf("long") }; var project by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Only what you approve is remembered. Agents can search it and can ask to add to it (you approve). Secrets, card numbers and passwords are refused. Private tabs never write to memory.", fontSize = 12.sp)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Add a fact or preference") })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("long" to "Long-term", "project" to "Project", "session" to "Session").forEach { (s, l) -> FilterChip(scope == s, { scope = s }, { Text(l) }) } }
            if (scope == "project") OutlinedTextField(project, { project = it }, Modifier.fillMaxWidth(), label = { Text("Project name") }, singleLine = true)
            Button({ err = vm.hub.addMemory(text, scope, project); if (err == null) text = "" }, enabled = text.isNotBlank()) { Text("Remember") }
            err?.let { Text(it, color = Color(0xFFB3261E), fontSize = 12.sp) }
            Text("Keep long-term memory: ${if (state.settings.ai.memoryRetentionDays == 0) "forever" else "${state.settings.ai.memoryRetentionDays} days"}", fontSize = 12.sp)
            Slider(state.settings.ai.memoryRetentionDays.toFloat(), { vm.hub.setRetention(it.toInt()) }, valueRange = 0f..365f)
        }
        items(state.memories, key = { it.id }) { m -> var editing by remember { mutableStateOf(false) }; var t by remember(m.text) { mutableStateOf(m.text) }
            Column { Text("${m.scope}${if (m.project.isNotBlank()) "/${m.project}" else ""} · ${m.source}", fontSize = 10.sp)
                if (editing) { OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth()); TextButton({ err = vm.hub.editMemory(m.id, t); editing = false }) { Text("Save") } } else Text(m.text, fontSize = 13.sp)
                Row { TextButton({ editing = !editing }) { Text("Edit") }; TextButton({ vm.hub.deleteMemory(m.id) }) { Text("Delete") } } } }
        item { TextButton({ vm.hub.clearMemory() }) { Text("Delete all memory") } }
    }
}

@Composable
private fun ServicesTab(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle(); val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var token by remember { mutableStateOf("") }; var kind by remember { mutableStateOf("mcp") }
    var tools by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Connect tool servers (MCP) or get a webhook when a task finishes. Agents can only use tools you approve, each use asks first, and results are treated as untrusted. Secrets are stored in the Keystore.", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(kind == "mcp", { kind = "mcp" }, { Text("MCP server") }); FilterChip(kind == "webhook", { kind = "webhook" }, { Text("Webhook") }) }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS URL") }, singleLine = true)
            if (kind == "mcp") OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bearer token (optional)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Button({ vm.hub.addIntegration(kind, name, url, token); name = ""; url = ""; token = "" }, enabled = url.startsWith("https://") || url.startsWith("http://localhost")) { Text("Add") }
        }
        items(state.integrations, key = { it.id }) { i ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("${i.name} (${i.kind})", Modifier.weight(1f), fontSize = 14.sp); Switch(i.enabled, { v -> vm.hub.setIntegration(i.id) { it.copy(enabled = v) } }) }
                Text(i.url, fontSize = 11.sp)
                if (i.kind == "mcp") { Text("Approved tools: ${i.allowedTools.joinToString().ifBlank { "none" }}", fontSize = 11.sp)
                    TextButton({ scope.launch { runCatching { vm.hub.listMcpTools(i) }.onSuccess { tools = i.id to it.map { t -> t.name } }.onFailure { vm.message = "Couldn't connect: ${it.message}" } } }) { Text("Connect & choose tools") } }
                TextButton({ vm.hub.removeIntegration(i.id) }) { Text("Remove") } } }
        }
        item {
            Text("Sign-in connectors", fontSize = 12.sp)
            listOf("Google (Gmail, Calendar, Drive)", "Microsoft (Outlook, OneDrive, Teams)", "GitHub", "Notion", "Slack").forEach { Text("• $it — not available: needs an OAuth client ID registered by you or the publisher. Connect a service through MCP instead.", fontSize = 11.sp) }
        }
    }
    tools?.let { (id, names) ->
        val i = state.integrations.firstOrNull { it.id == id }
        if (i != null) AlertDialog(onDismissRequest = { tools = null }, title = { Text("Tools agents may use") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) { items(names) { n -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(n in i.allowedTools, { on -> vm.hub.setIntegration(id) { it.copy(allowedTools = if (on) it.allowedTools + n else it.allowedTools - n) } }); Text(n, fontSize = 13.sp) } } } },
            confirmButton = { TextButton({ tools = null }) { Text("Done") } })
    }
}
