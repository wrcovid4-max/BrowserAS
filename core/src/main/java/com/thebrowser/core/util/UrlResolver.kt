package com.thebrowser.core.util

import java.net.URLEncoder

object SearchEngines {
    val all: Map<String, Pair<String, String>> = linkedMapOf(
        "duckduckgo" to ("DuckDuckGo" to "https://duckduckgo.com/?q=%s"),
        "google" to ("Google" to "https://www.google.com/search?q=%s"),
        "bing" to ("Bing" to "https://www.bing.com/search?q=%s"),
        "brave" to ("Brave" to "https://search.brave.com/search?q=%s"),
    )

    fun template(id: String): String = (all[id] ?: all.getValue("duckduckgo")).second
}

object UrlResolver {
    /** Turns address-bar input into either a URL or a search URL. */
    fun resolve(input: String, searchEngineId: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("data:")
        ) return t
        if (!t.contains(' ') && (t.contains('.') || lower.startsWith("localhost"))) {
            return "https://$t" // HTTPS-first
        }
        return SearchEngines.template(searchEngineId).replace("%s", URLEncoder.encode(t, "UTF-8"))
    }
}
