package com.thebrowser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.AgentApprovalRequest
import com.thebrowser.BrowserViewModel
import com.thebrowser.core.agent.AgentCatalog
import java.text.DateFormat
import java.util.Date

/** Always visible while an agent is acting: who, what, and a Stop button. Never hidden by the agent. */
@Composable
fun AgentBanner(vm: BrowserViewModel) {
    val a = vm.agents.activity ?: return
    Box(Modifier.fillMaxWidth().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Surface(Modifier.padding(top = 4.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer, tonalElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🤖 ${a.name} is controlling this browser · ${a.lastTool.removePrefix("browser_")}", fontSize = 12.sp)
                TextButton({ vm.agents.stopAll() }) { Text("Stop", color = Color(0xFFB3261E)) }
            }
        }
    }
}

@Composable
fun AgentApprovalDialog(vm: BrowserViewModel, r: AgentApprovalRequest) {
    AlertDialog(
        onDismissRequest = { vm.agents.decide(false) },
        title = { Text("${r.agent} needs your approval") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(r.description, fontSize = 14.sp); r.host?.let { Text("Site: $it", fontSize = 12.sp) }; Text("Action: ${r.tool.removePrefix("browser_")}", fontSize = 12.sp); Text("Declines automatically after 90 seconds.", fontSize = 11.sp) } },
        confirmButton = { TextButton({ vm.agents.decide(true) }) { Text("Approve") } },
        dismissButton = { TextButton({ vm.agents.decide(false) }) { Text("Decline") } },
    )
}

@Composable
fun AgentsSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val a = state.settings.agent
    val ctrl = vm.agents
    val context = LocalContext.current
    var pair by remember { mutableStateOf(false) }
    var blocked by remember(a.blockedDomains) { mutableStateOf(a.blockedDomains.joinToString(", ")) }
    fun copy(t: String) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Hydrogen", t)); vm.message = "Copied." }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Let an AI agent (Claude, ChatGPT, Muse or any MCP client) use this browser. It is OFF until you turn it on, needs a pairing token you create, only listens on this device unless you allow your network, and every action is logged. Agents never see passwords, can't use private tabs, and purchases are blocked by default.", fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) { Text(if (ctrl.serverRunning) "On · http://127.0.0.1:${a.port}/mcp" else if (a.enabled) "On, but the port is busy" else "Off", Modifier.weight(1f), fontSize = 14.sp); Switch(a.enabled, { v -> ctrl.updateSettings { it.copy(enabled = v) } }) }
            if (ctrl.paused) Row(verticalAlignment = Alignment.CenterVertically) { Text("Agent control is PAUSED", Modifier.weight(1f), color = Color(0xFFB3261E)); TextButton({ ctrl.resume() }) { Text("Resume") } }
            Button({ ctrl.stopAll() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("Stop all agents now") }
        }
        item { Text("Approvals", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ask" to "Ask every action", "risky" to "Ask for risky actions", "auto" to "Auto (except payments, sending, deleting)").forEach { (id, l) -> FilterChip(a.mode == id, { ctrl.updateSettings { it.copy(mode = id) } }, { Text(l) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Agents may see all my open tabs (default: only their own Agent tabs)", Modifier.weight(1f), fontSize = 13.sp); Switch(a.allowAllTabs, { v -> ctrl.updateSettings { it.copy(allowAllTabs = v) } }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Allow purchase steps (still needs approval each time)", Modifier.weight(1f), fontSize = 13.sp); Switch(a.allowPurchases, { v -> ctrl.updateSettings { it.copy(allowPurchases = v) } }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Allow other devices on my Wi-Fi to connect (plain HTTP: only on networks you trust)", Modifier.weight(1f), fontSize = 13.sp); Switch(a.allowLan, { v -> ctrl.updateSettings { it.copy(allowLan = v) } }) }
            OutlinedTextField(blocked, { blocked = it; ctrl.updateSettings { s -> s.copy(blockedDomains = it.split(',').map(String::trim).filter(String::isNotEmpty)) } }, Modifier.fillMaxWidth(), label = { Text("Sites agents may never use (comma separated, * allowed)") }, singleLine = true)
            Text("Auto-pause after ${a.autoStopMinutes} minutes of continuous control", fontSize = 12.sp)
            Slider(a.autoStopMinutes.toFloat(), { v -> ctrl.updateSettings { it.copy(autoStopMinutes = v.toInt()) } }, valueRange = 1f..60f)
        }
        item { Text("Paired agents", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        items(state.agentGrants, key = { it.id }) { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(g.name, fontSize = 15.sp); Text("${g.scopes.joinToString()} · ${if (g.expiresAt > 0) "expires ${DateFormat.getDateInstance().format(Date(g.expiresAt))}" else "no expiry"}${if (g.lastUsed > 0) " · last used ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(g.lastUsed))}" else ""}", fontSize = 11.sp) }
                TextButton({ ctrl.revoke(g.id) }) { Text("Revoke") }
            }
        }
        item { Button({ pair = true }) { Text("Pair a new agent") } }
        item {
            Text("Connect", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text("• Clients that speak MCP over HTTP: URL above + header “Authorization: Bearer <token>”.\n• Claude Desktop (stdio): use agent/hydrogen_mcp_bridge.py with HYDROGEN_URL and HYDROGEN_TOKEN.\n• Phone to phone or phone to computer needs “Wi-Fi” access above, or your own secure tunnel. There is no cloud relay.\n• The server runs while Hydrogen is running.", fontSize = 12.sp)
        }
        item { Text("Activity log", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        if (state.agentLog.isEmpty()) item { Text("No agent activity yet.", fontSize = 12.sp) }
        items(state.agentLog.take(60)) { e -> Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.time))} · ${e.agent} · ${e.tool.removePrefix("browser_")}${if (e.host.isNotBlank()) " · ${e.host}" else ""} · ${e.outcome}", fontSize = 11.sp) }
    }
    if (pair) {
        var name by remember { mutableStateOf("") }; var scopes by remember { mutableStateOf(setOf("read")) }; var days by remember { mutableStateOf(7f) }
        AlertDialog(
            onDismissRequest = { pair = false }, title = { Text("Pair an agent") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Agent name (e.g. Claude)") }, singleLine = true)
                    Text("What it may do", fontSize = 12.sp)
                    AgentCatalog.allScopes.forEach { s -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s in scopes, { on -> scopes = if (on) scopes + s else scopes - s }); Text(when (s) { "read" -> "read: see pages and tabs"; "interact" -> "interact: click, type, scroll"; "navigate" -> "navigate: open URLs, back, reload"; else -> "tabs: open, switch, close" }, fontSize = 13.sp) } }
                    Text(if (days.toInt() == 0) "No expiry" else "Expires in ${days.toInt()} days", fontSize = 12.sp); Slider(days, { days = it }, valueRange = 0f..90f)
                }
            },
            confirmButton = { TextButton({ ctrl.lastNewToken = ctrl.createGrant(name, scopes.toList(), days.toInt()); pair = false }, enabled = scopes.isNotEmpty()) { Text("Create token") } },
            dismissButton = { TextButton({ pair = false }) { Text("Cancel") } },
        )
    }
    ctrl.lastNewToken?.let { tok ->
        AlertDialog(
            onDismissRequest = { ctrl.lastNewToken = null }, title = { Text("Copy this token now") },
            text = { Column { Text("It is shown once and can't be recovered; only its hash is kept.", fontSize = 12.sp); Text(tok, fontSize = 12.sp, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp)) } },
            confirmButton = { TextButton({ copy(tok) }) { Text("Copy token") } },
            dismissButton = { TextButton({ ctrl.lastNewToken = null }) { Text("Done") } },
        )
    }
}
