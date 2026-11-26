package com.thebrowser.core.agent

import com.thebrowser.core.ai.ToolSpec
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal MCP client (Streamable HTTP, JSON or SSE replies) for user-approved external tool servers. Results are always treated as untrusted. */
class McpClient(private val url: String, private val bearer: String?) {
    private var session: String? = null
    private var nextId = 1
    private var ready = false

    private fun rpc(method: String, params: JSONObject, notify: Boolean = false): JSONObject? {
        require(url.startsWith("https://") || url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) { "MCP servers must use HTTPS (or localhost)." }
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params).also { if (!notify) it.put("id", nextId++) }
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 60_000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "application/json, text/event-stream")
        bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }; session?.let { c.setRequestProperty("Mcp-Session-Id", it) }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode == 202 || notify) return null
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.getHeaderField("Mcp-Session-Id")?.let { session = it }
            val text = c.inputStream.bufferedReader().readText()
            val json = if (c.contentType.orEmpty().contains("event-stream")) text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.lastOrNull { it.contains("\"result\"") || it.contains("\"error\"") } ?: error("Empty reply") else text
            val o = JSONObject(json)
            o.optJSONObject("error")?.let { error(it.optString("message")) }
            return o.optJSONObject("result")
        } finally { c.disconnect() }
    }

    private fun init() { if (ready) return; rpc("initialize", JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "hydrogen").put("version", "0.3"))); rpc("notifications/initialized", JSONObject(), notify = true); ready = true }

    fun listTools(): List<ToolSpec> { init(); val a = rpc("tools/list", JSONObject())!!.getJSONArray("tools"); return (0 until a.length()).map { val t = a.getJSONObject(it); ToolSpec(t.getString("name"), t.optString("description"), t.optJSONObject("inputSchema")?.toString() ?: "{\"type\":\"object\",\"properties\":{}}") } }

    fun call(name: String, args: JSONObject): String {
        init()
        val r = rpc("tools/call", JSONObject().put("name", name).put("arguments", args))!!
        val c = r.optJSONArray("content"); val sb = StringBuilder()
        if (c != null) for (i in 0 until c.length()) c.getJSONObject(i).let { if (it.optString("type") == "text") sb.append(it.optString("text")).append('\n') else sb.append("[${it.optString("type")} content omitted]\n") }
        return (if (r.optBoolean("isError")) "ERROR: " else "") + sb.toString().trim()
    }
}

object Webhook {
    /** Posts a job summary to a user-configured HTTPS URL. Nothing is sent unless the user added the hook. */
    fun send(url: String, payload: JSONObject): Boolean = runCatching {
        require(url.startsWith("https://")); Ssrf.check(url)?.let { error(it) }
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8_000; c.readTimeout = 8_000; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(payload.toString().toByteArray()) }; val ok = c.responseCode in 200..299; c.disconnect(); ok
    }.getOrDefault(false)
}
