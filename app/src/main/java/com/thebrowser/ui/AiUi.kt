package com.thebrowser.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.thebrowser.BrowserViewModel
import com.thebrowser.core.ai.UsageTracker
import com.thebrowser.core.model.ProviderConfig
import kotlinx.coroutines.launch

private fun kindLabel(k: String) = when (k) { "gemini" -> "Gemini"; "anthropic" -> "Anthropic"; else -> "OpenAI-compatible" }

@Composable
fun AiSection(vm: BrowserViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ai = state.settings.ai
    var add by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Every AI feature (assistant, agents, research) goes through one gateway. Pick a default provider and optional fallbacks that are tried in order if the default fails. " +
                "API keys are encrypted with the Android Keystore, are never shown again, never logged, and never reachable by web pages. Anything you send leaves this device for the provider you choose.", fontSize = 12.sp)
        }
        items(state.providers, key = { it.id }) { p -> ProviderCard(vm, p, isDefault = p.id == ai.defaultProviderId, isFallback = p.id in ai.fallbackProviderIds) }
        item { Button({ add = true }) { Text("Add provider") } }
        item {
            val chain = (listOf(ai.defaultProviderId) + ai.fallbackProviderIds).mapNotNull { id -> state.providers.firstOrNull { it.id == id }?.label }
            Text("Order tried: " + chain.joinToString(" → ").ifBlank { "none" }, fontSize = 12.sp)
        }
        item { Text("Usage (counted from provider responses)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp)) }
        item {
            val totals = UsageTracker.totals(state.usage)
            if (totals.isEmpty()) Text("No requests yet.", fontSize = 13.sp)
            totals.forEach { (id, t) ->
                Row { Text(state.providers.firstOrNull { it.id == id }?.label ?: id, Modifier.weight(1f), fontSize = 13.sp); Text("${t.first} calls · ${t.second} in · ${t.third} out", fontSize = 12.sp) }
            }
            val days = state.usage.map { it.day }.distinct().size
            if (days > 0) Text("Last $days day(s). Token numbers are what each provider reported; cost isn't estimated because prices change.", fontSize = 11.sp)
            TextButton({ vm.resetUsage() }) { Text("Reset usage") }
        }
    }
    if (add) AddProviderDialog(vm) { add = false }
}

@Composable
private fun ProviderCard(vm: BrowserViewModel, p: ProviderConfig, isDefault: Boolean, isFallback: Boolean) {
    var key by remember(p.id) { mutableStateOf("") }
    var hasKey by remember(p.id) { mutableStateOf(vm.hasProviderKey(p.id)) }
    var models by remember(p.id) { mutableStateOf(emptyList<String>()) }
    var loading by remember(p.id) { mutableStateOf(false) }
    var model by remember(p.id, p.model) { mutableStateOf(p.model) }
    var url by remember(p.id, p.baseUrl) { mutableStateOf(p.baseUrl) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(p.label, fontSize = 16.sp); Text(kindLabel(p.kind), fontSize = 11.sp) }
                Switch(p.enabled, { v -> vm.updateProvider(p.id) { it.copy(enabled = v) } })
                if (p.id != "gemini") IconButton({ vm.deleteProvider(p.id) }) { Icon(Icons.Default.Delete, "Delete provider") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(isDefault, { vm.setDefaultProvider(p.id) }, { Text("Default") })
                FilterChip(isFallback, { vm.toggleFallbackProvider(p.id) }, { Text("Fallback") }, enabled = !isDefault)
            }
            if (p.kind != "gemini") OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true,
                supportingText = { if (p.kind == "openai" && model.isBlank()) Text("Required: tap “List models”") })
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "API key saved — paste to replace" else if (p.kind == "openai") "API key (optional for local servers)" else "API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({
                    vm.updateProvider(p.id) { it.copy(model = model.trim(), baseUrl = if (p.kind == "gemini") it.baseUrl else url.trim()) }
                    if (key.isNotBlank()) { vm.setProviderKey(p.id, key); key = ""; hasKey = true }
                }) { Text("Save") }
                OutlinedButton({ vm.testProvider(p.id) }) { Text("Test") }
                OutlinedButton({
                    vm.updateProvider(p.id) { it.copy(baseUrl = if (p.kind == "gemini") it.baseUrl else url.trim()) }
                    loading = true
                    vm.viewModelScope.launch {
                        runCatching { vm.providerModels(p.id) }.onSuccess { models = it; if (it.isEmpty()) vm.message = "No models returned." }.onFailure { vm.message = it.message }
                        loading = false
                    }
                }, enabled = !loading) { Text(if (loading) "Loading…" else "List models") }
                if (hasKey) TextButton({ vm.removeProviderKey(p.id); hasKey = false }) { Text("Remove key") }
            }
            if (models.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 160.dp)) {
                items(models) { m -> TextButton({ model = m; vm.updateProvider(p.id) { it.copy(model = m) } }) { Text(m, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            }
        }
    }
}

@Composable
private fun AddProviderDialog(vm: BrowserViewModel, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf("openai") }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://api.openai.com/v1") }
    var model by remember { mutableStateOf("") }
    fun preset(k: String) { kind = k; url = when (k) { "anthropic" -> "https://api.anthropic.com"; "gemini" -> "https://generativelanguage.googleapis.com"; else -> "https://api.openai.com/v1" }; model = if (k == "anthropic") "claude-sonnet-5-5" else "" }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add AI provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("openai", "anthropic", "gemini").forEach { k -> FilterChip(kind == k, { preset(k) }, { Text(kindLabel(k)) }) }
                }
                OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("Model (you can list models after saving)") }, singleLine = true)
                Text("“OpenAI-compatible” also covers OpenRouter, Ollama (http://localhost:11434/v1), LM Studio and vLLM.", fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton({ vm.addProvider(kind, label, url, model); onDismiss() }, enabled = url.startsWith("http")) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
