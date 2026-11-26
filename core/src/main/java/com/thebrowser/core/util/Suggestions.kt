package com.thebrowser.core.util

import com.thebrowser.core.state.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Opt-in search suggestions. Typing is sent to the chosen search engine, so this is off by
 * default. Only engines with a public OpenSearch-style suggest endpoint are supported.
 */
object Suggestions {
    private fun endpoint(engine: String): String? = when (engine) {
        "duckduckgo" -> "https://duckduckgo.com/ac/?type=list&q="
        "google" -> "https://suggestqueries.google.com/complete/search?client=firefox&q="
        else -> null
    }

    suspend fun fetch(engine: String, query: String): List<String> = withContext(Dispatchers.IO) {
        val base = endpoint(engine) ?: return@withContext emptyList()
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val conn = URL(base + URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            try {
                val root = AppJson.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonArray
                root[1].jsonArray.map { it.jsonPrimitive.content }.take(6)
            } finally { conn.disconnect() }
        }.getOrDefault(emptyList())
    }
}
