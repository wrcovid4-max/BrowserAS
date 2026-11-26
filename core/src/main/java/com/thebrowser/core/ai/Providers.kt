package com.thebrowser.core.ai

import com.thebrowser.core.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Provider-independent tool/turn model. The agent runtime only ever sees these types. */
data class ToolSpec(val name: String, val description: String, val schemaJson: String)
data class ToolCall(val id: String, val name: String, val args: JSONObject)
data class ToolResultMsg(val callId: String, val name: String, val text: String, val isError: Boolean = false)
data class Usage(val inputTokens: Long = 0, val outputTokens: Long = 0)

sealed class Turn {
    data class User(val text: String) : Turn()
    /** [raw] carries provider-specific content that must be echoed back (e.g. Gemini thought signatures). */
    data class Assistant(val text: String, val calls: List<ToolCall>, val raw: Any? = null) : Turn()
    data class ToolResults(val results: List<ToolResultMsg>) : Turn()
}

data class ModelReply(val text: String, val calls: List<ToolCall>, val usage: Usage, val raw: Any? = null)

class ProviderException(message: String, val code: Int = 0, val retryable: Boolean = false) : Exception(message)

interface ModelProvider {
    val config: ProviderConfig
    /** Model capabilities are discovered, not assumed: tool calling is reported by the provider kind. */
    val supportsTools: Boolean get() = true
    suspend fun complete(model: String, system: String, turns: List<Turn>, tools: List<ToolSpec>): ModelReply
    suspend fun listModels(): List<String>
}

internal fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int = 90_000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = timeoutMs; c.doOutput = true
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    c.setRequestProperty("Content-Type", "application/json")
    try {
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).let { it.optJSONObject("error")?.optString("message") ?: it.optString("message") } }.getOrNull().orEmpty()
            throw ProviderException(msg.ifBlank { "HTTP $code" }, code, retryable = code == 429 || code >= 500)
        }
        return text
    } catch (e: java.io.IOException) { throw ProviderException("Network error: ${e.message}", retryable = true) }
    finally { c.disconnect() }
}

internal fun get(url: String, headers: Map<String, String>): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000; c.readTimeout = 30_000
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    try {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) throw ProviderException("HTTP $code", code)
        return text
    } finally { c.disconnect() }
}

// ───────── Gemini (verified: functionDeclarations / functionCall / functionResponse, thought signatures echoed back) ─────────
class GeminiProvider(override val config: ProviderConfig, private val key: () -> String?) : ModelProvider {
    private val base get() = config.baseUrl.ifBlank { "https://generativelanguage.googleapis.com" }.trimEnd('/')
    private fun headers() = mapOf("x-goog-api-key" to (key()?.takeIf { it.isNotBlank() } ?: throw ProviderException("No Gemini API key set.")))

    private fun upper(o: Any?): Any? = when (o) {
        is JSONObject -> JSONObject().also { n -> o.keys().forEach { k -> n.put(k, if (k == "type" && o.get(k) is String) o.getString(k).uppercase() else upper(o.get(k))) } }
        is JSONArray -> JSONArray().also { n -> for (i in 0 until o.length()) n.put(upper(o.get(i))) }
        else -> o
    }

    override suspend fun complete(model: String, system: String, turns: List<Turn>, tools: List<ToolSpec>): ModelReply = withContext(Dispatchers.IO) {
        val contents = JSONArray()
        for (t in turns) when (t) {
            is Turn.User -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", t.text))))
            is Turn.Assistant -> contents.put((t.raw as? JSONObject) ?: JSONObject().put("role", "model").put("parts", JSONArray().also { a ->
                if (t.text.isNotBlank()) a.put(JSONObject().put("text", t.text))
                t.calls.forEach { c -> a.put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args))) }
            }))
            is Turn.ToolResults -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().also { a ->
                t.results.forEach { r -> a.put(JSONObject().put("functionResponse", JSONObject().put("name", r.name).put("response", JSONObject().put(if (r.isError) "error" else "result", r.text)))) }
            }))
        }
        val body = JSONObject().put("contents", contents)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        if (tools.isNotEmpty()) body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().also { a ->
            tools.forEach { t ->
                val schema = upper(JSONObject(t.schemaJson)) as JSONObject
                if (schema.optJSONObject("properties")?.length() == 0) { schema.remove("properties"); schema.remove("required") }
                if (schema.optJSONArray("required")?.length() == 0) schema.remove("required")
                a.put(JSONObject().put("name", t.name).put("description", t.description).also { if (schema.has("properties")) it.put("parameters", schema) })
            }
        })))
        val resp = JSONObject(post("$base/v1beta/models/${model.ifBlank { config.model }}:generateContent", headers(), body.toString()))
        val cand = resp.optJSONArray("candidates")?.optJSONObject(0) ?: throw ProviderException("The model returned no answer (it may have been blocked).")
        val content = cand.optJSONObject("content") ?: JSONObject().put("role", "model").put("parts", JSONArray())
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder(); val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            p.optString("text").takeIf { it.isNotEmpty() && !p.optBoolean("thought") }?.let(text::append)
            p.optJSONObject("functionCall")?.let { calls += ToolCall(UUID.randomUUID().toString(), it.getString("name"), it.optJSONObject("args") ?: JSONObject()) }
        }
        val u = resp.optJSONObject("usageMetadata")
        ModelReply(text.toString(), calls, Usage(u?.optLong("promptTokenCount") ?: 0, u?.optLong("candidatesTokenCount") ?: 0), content)
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val arr = JSONObject(get("$base/v1beta/models?pageSize=200", headers())).optJSONArray("models") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m -> (m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } }) == true }
            .map { it.getString("name").removePrefix("models/") }.filter { it.startsWith("gemini") }.sortedDescending()
    }
}

// ───────── OpenAI-compatible chat completions (OpenAI, OpenRouter, Ollama, LM Studio, vLLM…) ─────────
class OpenAiProvider(override val config: ProviderConfig, private val key: () -> String?) : ModelProvider {
    private val base get() = config.baseUrl.ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
    private fun headers() = key()?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap() // local servers may need no key

    override suspend fun complete(model: String, system: String, turns: List<Turn>, tools: List<ToolSpec>): ModelReply = withContext(Dispatchers.IO) {
        val m = model.ifBlank { config.model }
        if (m.isBlank()) throw ProviderException("Choose a model for ${config.label} in Settings → AI.")
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (t in turns) when (t) {
            is Turn.User -> msgs.put(JSONObject().put("role", "user").put("content", t.text))
            is Turn.Assistant -> msgs.put(JSONObject().put("role", "assistant").put("content", t.text.ifBlank { JSONObject.NULL }).also { o ->
                if (t.calls.isNotEmpty()) o.put("tool_calls", JSONArray().also { a -> t.calls.forEach { c -> a.put(JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString()))) } })
            })
            is Turn.ToolResults -> t.results.forEach { r -> msgs.put(JSONObject().put("role", "tool").put("tool_call_id", r.callId).put("content", if (r.isError) "ERROR: ${r.text}" else r.text)) }
        }
        val body = JSONObject().put("model", m).put("messages", msgs)
        if (tools.isNotEmpty()) body.put("tools", JSONArray().also { a -> tools.forEach { t -> a.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", JSONObject(t.schemaJson)))) } })
        val resp = JSONObject(post("$base/chat/completions", headers(), body.toString()))
        val msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val calls = msg.optJSONArray("tool_calls")?.let { a -> (0 until a.length()).map { i -> val f = a.getJSONObject(i); val fn = f.getJSONObject("function"); ToolCall(f.optString("id", UUID.randomUUID().toString()), fn.getString("name"), runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrDefault(JSONObject())) } } ?: emptyList()
        val u = resp.optJSONObject("usage")
        ModelReply(msg.optString("content").takeIf { it != "null" }.orEmpty(), calls, Usage(u?.optLong("prompt_tokens") ?: 0, u?.optLong("completion_tokens") ?: 0))
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val arr = JSONObject(get("$base/models", headers())).optJSONArray("data") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.sorted()
    }
}

// ───────── Anthropic Messages API ─────────
class AnthropicProvider(override val config: ProviderConfig, private val key: () -> String?) : ModelProvider {
    private val base get() = config.baseUrl.ifBlank { "https://api.anthropic.com" }.trimEnd('/')
    private fun headers() = mapOf("x-api-key" to (key()?.takeIf { it.isNotBlank() } ?: throw ProviderException("No Anthropic API key set.")), "anthropic-version" to "2023-06-01")

    override suspend fun complete(model: String, system: String, turns: List<Turn>, tools: List<ToolSpec>): ModelReply = withContext(Dispatchers.IO) {
        val m = model.ifBlank { config.model }
        if (m.isBlank()) throw ProviderException("Choose a model for ${config.label} in Settings → AI.")
        val msgs = JSONArray()
        for (t in turns) when (t) {
            is Turn.User -> msgs.put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", t.text))))
            is Turn.Assistant -> msgs.put(JSONObject().put("role", "assistant").put("content", JSONArray().also { a ->
                if (t.text.isNotBlank()) a.put(JSONObject().put("type", "text").put("text", t.text))
                t.calls.forEach { c -> a.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", c.args)) }
            }))
            is Turn.ToolResults -> msgs.put(JSONObject().put("role", "user").put("content", JSONArray().also { a ->
                t.results.forEach { r -> a.put(JSONObject().put("type", "tool_result").put("tool_use_id", r.callId).put("content", r.text).put("is_error", r.isError)) }
            }))
        }
        val body = JSONObject().put("model", m).put("max_tokens", 4096).put("system", system).put("messages", msgs)
        if (tools.isNotEmpty()) body.put("tools", JSONArray().also { a -> tools.forEach { t -> a.put(JSONObject().put("name", t.name).put("description", t.description).put("input_schema", JSONObject(t.schemaJson))) } })
        val resp = JSONObject(post("$base/v1/messages", headers(), body.toString()))
        val blocks = resp.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder(); val calls = mutableListOf<ToolCall>()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            when (b.optString("type")) { "text" -> text.append(b.optString("text")); "tool_use" -> calls += ToolCall(b.getString("id"), b.getString("name"), b.optJSONObject("input") ?: JSONObject()) }
        }
        val u = resp.optJSONObject("usage")
        ModelReply(text.toString(), calls, Usage(u?.optLong("input_tokens") ?: 0, u?.optLong("output_tokens") ?: 0))
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val arr = JSONObject(get("$base/v1/models?limit=100", headers())).optJSONArray("data") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }
}

/**
 * Multi-provider gateway: default provider, ordered fallbacks, retry on transient errors, usage accounting.
 * Keys are looked up per provider from secure storage; the gateway never logs them.
 */
class ModelGateway(
    private val configs: () -> List<ProviderConfig>,
    private val keyOf: (providerId: String) -> String?,
    private val defaultId: () -> String,
    private val fallbackIds: () -> List<String>,
    private val onUsage: (providerId: String, model: String, Usage) -> Unit = { _, _, _ -> },
    private val factory: ((ProviderConfig) -> ModelProvider?)? = null,
) {
    fun provider(id: String): ModelProvider? = configs().firstOrNull { it.id == id && it.enabled }?.let { c ->
        factory?.invoke(c) ?: when (c.kind) {
            "gemini" -> GeminiProvider(c) { keyOf(c.id) }
            "openai" -> OpenAiProvider(c) { keyOf(c.id) }
            "anthropic" -> AnthropicProvider(c) { keyOf(c.id) }
            else -> null
        }
    }

    fun chain(preferred: String?): List<ModelProvider> =
        (listOfNotNull(preferred?.takeIf { it.isNotBlank() }) + defaultId() + fallbackIds()).distinct().mapNotNull(::provider)

    suspend fun complete(preferred: String?, system: String, turns: List<Turn>, tools: List<ToolSpec>): ModelReply {
        val providers = chain(preferred)
        if (providers.isEmpty()) throw ProviderException("No AI provider is set up. Add one in Settings → AI.")
        var last: ProviderException? = null
        for (p in providers) {
            for (attempt in 0..1) {
                try {
                    val r = p.complete(p.config.model, system, turns, tools)
                    onUsage(p.config.id, p.config.model, r.usage)
                    return r
                } catch (e: ProviderException) {
                    last = e
                    if (!e.retryable) break // not transient: try the next provider
                    kotlinx.coroutines.delay(800L * (attempt + 1))
                }
            }
        }
        throw last ?: ProviderException("All providers failed.")
    }
}
