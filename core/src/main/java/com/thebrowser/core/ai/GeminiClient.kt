package com.thebrowser.core.ai

import com.thebrowser.core.model.ChatMessage
import com.thebrowser.core.state.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(message: String, val httpCode: Int = 0) : Exception(message)

/**
 * Minimal Gemini REST client (Google AI Studio API).
 *
 * Verified against Google's API reference (14 Nov 2026):
 *  - header `x-goog-api-key` carries the key
 *  - `POST {base}/v1beta/models/{model}:streamGenerateContent` streams Server-Sent Events
 *  - `generateContent` is the non-streaming variant
 * Note: the `alt=sse` query flag and the `GET /v1beta/models` list call follow the public
 * REST convention; confirm both on first run.
 *
 * [baseUrl] may point at your own proxy (BuildConfig.GEMINI_PROXY_URL). When a proxy is set
 * and no local key exists, no key header is sent and the proxy supplies credentials.
 */
class GeminiClient(
    private val keyProvider: () -> String?,
    private val baseUrl: String = DEFAULT_BASE,
) {
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var cancelled = false

    /** Stops the in-flight request (Stop generation). Safe from any thread. */
    fun cancel() {
        cancelled = true
        runCatching { active?.disconnect() }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val key = keyProvider()?.trim().orEmpty()
        val usingProxy = baseUrl != DEFAULT_BASE
        if (key.isEmpty() && !usingProxy) {
            throw GeminiException("No Gemini API key set. Add yours in Settings → Gemini.")
        }
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        if (key.isNotEmpty()) conn.setRequestProperty("x-goog-api-key", key)
        conn.setRequestProperty("Content-Type", "application/json")
        return conn
    }

    private fun errorFrom(conn: HttpURLConnection): GeminiException {
        val code = conn.responseCode
        val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        val msg = runCatching {
            AppJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return GeminiException(msg ?: "Gemini request failed (HTTP $code)", code)
    }

    /** Streams the reply; [onChunk] receives text fragments on the IO thread. Returns full text. */
    suspend fun stream(
        model: String,
        system: String?,
        messages: List<ChatMessage>,
        onChunk: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            if (!system.isNullOrBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
                }
            }
            put("contents", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", if (m.role == "model") "model" else "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", m.text) }) }
                    })
                }
            })
        }.toString()

        cancelled = false
        val conn = open("/v1beta/models/$model:streamGenerateContent?alt=sse", "POST")
        active = conn
        val full = StringBuilder()
        try {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw errorFrom(conn)
            conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val text = extractText(payload)
                    if (text.isNotEmpty()) {
                        full.append(text)
                        onChunk(text)
                    }
                }
            }
            full.toString()
        } catch (e: GeminiException) {
            throw e
        } catch (e: java.io.IOException) {
            // disconnect() from cancel() surfaces here: return what we have.
            if (cancelled || full.isNotEmpty()) full.toString()
            else throw GeminiException("Network error: ${e.message ?: "connection lost"}")
        } finally {
            active = null
            runCatching { conn.disconnect() }
        }
    }

    private fun extractText(payload: String): String = runCatching {
        val root = AppJson.parseToJsonElement(payload).jsonObject
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts") as? JsonArray ?: return@runCatching ""
        parts.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
    }.getOrDefault("")

    /** Lists model ids that support generateContent for this key. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val conn = open("/v1beta/models?pageSize=200", "GET")
        try {
            if (conn.responseCode !in 200..299) throw errorFrom(conn)
            val text = conn.inputStream.bufferedReader().readText()
            val models = AppJson.parseToJsonElement(text).jsonObject["models"]?.jsonArray ?: JsonArray(emptyList())
            models.mapNotNull { el ->
                val o = el.jsonObject
                val methods = o["supportedGenerationMethods"]?.jsonArray?.map { (it as JsonPrimitive).content }.orEmpty()
                if ("generateContent" !in methods) null
                else o["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
            }.filter { it.startsWith("gemini") }.sortedDescending()
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://generativelanguage.googleapis.com"

        const val SYSTEM_PROMPT =
            "You are Gemini inside TheBrowser, a web browser. Be accurate and concise. " +
                "Only use page content the user attached to this message; if none is attached, say so " +
                "rather than guessing what the page says."

        fun translatePrompt(target: String) =
            "Translate the following text into $target. Keep meaning, tone and formatting. " +
                "Return only the translation."

        const val SUMMARISE_PROMPT = "Summarise this page in a short paragraph followed by 3-6 key points."
        const val EXPLAIN_PROMPT = "Explain the selected text clearly, adding context from the page if it helps."
        const val COMPARE_PROMPT =
            "Compare the attached pages: what they agree on, where they differ, and which is more reliable and why."
    }
}
