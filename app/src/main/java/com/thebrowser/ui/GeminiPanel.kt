package com.thebrowser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thebrowser.BrowserViewModel
import com.thebrowser.core.ai.GeminiClient
import com.thebrowser.core.model.ChatMessage

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GeminiPanel(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val g = vm.gemini
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    LaunchedEffect(vm.geminiPrefill) { vm.geminiPrefill?.let { input = it; vm.geminiPrefill = null } }
    var includePage by remember { mutableStateOf(false) }
    var includeSelection by remember { mutableStateOf(false) }
    var tabIds by remember { mutableStateOf(emptyList<String>()) }
    var showHistory by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    val messages = g.active?.messages.orEmpty()
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, g.streaming) {
        val n = messages.size + if (g.generating) 1 else 0
        if (n > 0) list.animateScrollToItem(n - 1)
    }

    fun go(prompt: String, page: Boolean = includePage, sel: Boolean = includeSelection, tabs: List<String> = tabIds) {
        vm.requestSend(prompt, page, sel, tabs)
        input = ""; includePage = false; includeSelection = false; tabIds = emptyList()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✦ Gemini", Modifier.weight(1f), fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                IconButton({ showHistory = true }) { Icon(Icons.Default.List, "Conversations") }
                IconButton({ g.newConversation() }) { Icon(Icons.Default.Add, "New conversation") }
                IconButton({ g.clearConversation() }, enabled = messages.isNotEmpty()) { Icon(Icons.Default.Delete, "Clear conversation") }
                IconButton({ vm.geminiOpen = false }) { Icon(Icons.Default.Close, "Close") }
            }
            Text("Model: ${state.settings.geminiModel}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.clickable { vm.manage = ManageSection.Gemini })

            // Quick actions. Each one asks for consent before any page text leaves the device.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip({ go(GeminiClient.SUMMARISE_PROMPT, page = true, sel = false, tabs = emptyList()) }, { Text("Summarise page") })
                AssistChip({ go(GeminiClient.EXPLAIN_PROMPT, page = false, sel = true, tabs = emptyList()) }, { Text("Explain selection") })
                AssistChip({ go(GeminiClient.translatePrompt(state.settings.translateTarget), page = true, sel = false, tabs = emptyList()) }, { Text("Translate page") })
                AssistChip({ go(GeminiClient.translatePrompt(state.settings.translateTarget), page = false, sel = true, tabs = emptyList()) }, { Text("Translate selection") })
                AssistChip({ showTabs = true }, { Text("Compare tabs…") })
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (messages.isEmpty() && !g.generating) item {
                    Text("Ask about the current page, summarise it, explain selected text, translate, or compare tabs you choose. " +
                        "Page text is only sent after you confirm. Follow-up questions don't re-send the page; tick “Include page” again if needed.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
                items(messages) { Bubble(it, context) }
                if (g.generating) item {
                    if (g.streaming.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)); Text("Thinking…", fontSize = 13.sp)
                    } else Bubble(ChatMessage("model", g.streaming), context, streaming = true)
                }
                g.error?.let { e -> item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Text(e, Modifier.padding(10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                } }
            }

            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(includePage, { includePage = !includePage }, { Text("Include page") })
                FilterChip(includeSelection, { includeSelection = !includeSelection }, { Text("Include selection") })
                if (tabIds.isNotEmpty()) FilterChip(true, { tabIds = emptyList() }, { Text("${tabIds.size} tabs") })
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask Gemini…") }, maxLines = 4)
                Spacer(Modifier.width(6.dp))
                if (g.generating) FilledIconButton({ g.cancel() }) { Icon(Icons.Default.Close, "Stop generation") }
                else FilledIconButton({ go(input) }, enabled = input.isNotBlank()) { Icon(Icons.Default.Send, "Send") }
            }
        }
    }

    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false }, title = { Text("Conversations") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (g.conversations.isEmpty()) item { Text("No conversations yet.") }
                items(g.conversations.toList()) { c ->
                    Row(Modifier.fillMaxWidth().clickable { g.open(c.id); showHistory = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.title.ifBlank { "Conversation" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton({ g.delete(c.id) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        },
        confirmButton = { TextButton({ showHistory = false }) { Text("Done") } },
        dismissButton = { TextButton({ g.deleteAll() }) { Text("Delete all") } },
    )

    if (showTabs) {
        var picked by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { showTabs = false }, title = { Text("Choose tabs to compare") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.tabs.filter { it.url.isNotBlank() }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { picked = if (t.id in picked) picked - t.id else picked + t.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(t.id in picked, { picked = if (it) picked + t.id else picked - t.id })
                            Text(t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showTabs = false; if (picked.size >= 2) go(GeminiClient.COMPARE_PROMPT, page = false, sel = false, tabs = picked.toList()) else vm.message = "Pick at least two tabs." }) { Text("Compare") } },
            dismissButton = { TextButton({ showTabs = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Bubble(m: ChatMessage, context: Context, streaming: Boolean = false) {
    val mine = m.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(10.dp)) {
                m.attachmentLabel?.let { Text("📎 $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                SelectionContainer { Text(m.text, fontSize = 14.sp) }
            }
        }
        if (!mine && !streaming) TextButton({
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Gemini", m.text))
        }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Copy", fontSize = 12.sp) }
    }
}
