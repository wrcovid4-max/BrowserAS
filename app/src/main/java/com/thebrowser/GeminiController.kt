package com.thebrowser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thebrowser.core.ai.GeminiClient
import com.thebrowser.core.ai.GeminiException
import com.thebrowser.core.model.ChatMessage
import com.thebrowser.core.model.Conversation
import com.thebrowser.core.state.StateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** Page content the user has chosen to send. Only ever built after an explicit user action. */
data class Attachment(val label: String, val text: String)

class GeminiController(
    private val scope: CoroutineScope,
    private val client: GeminiClient,
    private val store: StateStore,
    private val model: () -> String,
) {
    val conversations = mutableStateListOf<Conversation>().apply { addAll(store.loadConversations()) }
    var activeId by mutableStateOf(conversations.firstOrNull()?.id)
    var generating by mutableStateOf(false)
    var streaming by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    val active: Conversation? get() = conversations.firstOrNull { it.id == activeId }

    fun newConversation() {
        if (generating) cancel()
        activeId = null
        error = null
    }

    fun open(id: String) {
        if (generating) cancel()
        activeId = id
        error = null
    }

    fun clearConversation() {
        cancel()
        active?.let { replace(it.copy(messages = emptyList())) }
        error = null
    }

    fun delete(id: String) {
        conversations.removeAll { it.id == id }
        if (activeId == id) activeId = conversations.firstOrNull()?.id
        persist()
    }

    fun deleteAll() {
        cancel()
        conversations.clear()
        activeId = null
        persist()
    }

    fun cancel() {
        job?.cancel()
        client.cancel()
    }

    /**
     * [prompt] is what the user sees. [attachments] are sent with THIS message only; they are not
     * stored in history, so page content is never re-sent without a fresh user action.
     */
    fun send(prompt: String, attachments: List<Attachment>) {
        if (generating || prompt.isBlank()) return
        val now = System.currentTimeMillis()
        val conv = active ?: Conversation(UUID.randomUUID().toString(), prompt.take(40), emptyList(), now)
            .also { conversations.add(0, it); activeId = it.id }
        val label = attachments.takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.label }
        val history = conv.messages
        replace(conv.copy(messages = history + ChatMessage("user", prompt, label), updated = now))

        val composed = buildString {
            attachments.forEach { append("[Attached by the user — ${it.label}]\n${it.text}\n\n---\n") }
            append(prompt)
        }
        generating = true
        error = null
        streaming = ""
        job = scope.launch {
            try {
                val full = client.stream(model(), GeminiClient.SYSTEM_PROMPT, history + ChatMessage("user", composed)) { streaming += it }
                finish(full)
            } catch (e: CancellationException) {
                finish(streaming)
                throw e
            } catch (e: GeminiException) {
                error = e.message
                finish(streaming)
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
                finish(streaming)
            }
        }
    }

    private fun finish(text: String) {
        val id = activeId
        if (text.isNotBlank() && id != null) {
            conversations.firstOrNull { it.id == id }?.let {
                replace(it.copy(messages = it.messages + ChatMessage("model", text), updated = System.currentTimeMillis()))
            }
        }
        streaming = ""
        generating = false
    }

    private fun replace(c: Conversation) {
        val i = conversations.indexOfFirst { it.id == c.id }
        if (i >= 0) conversations[i] = c
        persist()
    }

    private fun persist() {
        val snapshot = conversations.toList()
        scope.launch(Dispatchers.IO) { store.saveConversations(snapshot) }
    }
}
