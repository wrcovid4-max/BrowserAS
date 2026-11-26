package com.thebrowser

import com.thebrowser.core.shield.FilterEngine
import com.thebrowser.core.shield.FilterListRef
import com.thebrowser.core.shield.FilterLists
import com.thebrowser.core.state.StateStore
import java.net.HttpURLConnection
import java.net.URL

/**
 * Custom and downloadable filter lists (domain-level rules only). Updating a list sends one plain
 * HTTPS request to that list's host: no identifiers, no browsing data. Lists are stored on disk.
 */
class FilterListManager(private val store: StateStore, val engine: FilterEngine) {

    data class Preset(val name: String, val url: String, val category: String, val note: String)

    val presets = listOf(
        Preset("EasyList (ads)", "https://easylist.to/easylist/easylist.txt", "ads", "Large community ad list. Only its plain domain rules are used here."),
        Preset("EasyPrivacy (trackers)", "https://easylist.to/easylist/easyprivacy.txt", "trackers", "Community tracker list. Only its plain domain rules are used here."),
        Preset("Peter Lowe's ad & tracking servers", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", "ads", "Hosts-file format list of ad/tracking servers."),
    )

    /** Rebuilds the in-memory matcher from every enabled list on disk. */
    fun rebuild(lists: List<FilterListRef>) {
        val trackers = HashSet<String>(); val ads = HashSet<String>(); val allow = HashSet<String>()
        lists.filter { it.enabled }.forEach { l ->
            val f = store.filterFile(l.id)
            if (!f.exists()) return@forEach
            val p = FilterLists.parse(f.readText())
            if (l.category == "trackers") trackers += p.blocked else ads += p.blocked
            allow += p.allowed
        }
        engine.update(trackers, ads, allow)
    }

    /** Stores pasted text as a custom list and returns its reference (host count = what was actually understood). */
    fun addText(id: String, name: String, text: String, category: String): FilterListRef {
        store.filterFile(id).writeText(text)
        return FilterListRef(id, name, "", category, true, System.currentTimeMillis(), "", FilterLists.parse(text).blocked.size)
    }

    /** Downloads [ref].url (HTTPS only, ≤ 25 MB, honours ETag). Returns the updated ref, or null when unchanged. Throws on failure. */
    fun fetch(ref: FilterListRef): FilterListRef? {
        require(ref.url.startsWith("https://")) { "Lists must use HTTPS" }
        val c = URL(ref.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        if (ref.etag.isNotBlank() && store.filterFile(ref.id).exists()) c.setRequestProperty("If-None-Match", ref.etag)
        try {
            if (c.responseCode == 304) return ref.copy(lastUpdated = System.currentTimeMillis())
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { it.readNBytes(25 * 1024 * 1024 + 1) }
            if (bytes.size > 25 * 1024 * 1024) error("List is larger than 25 MB")
            val text = String(bytes, Charsets.UTF_8)
            store.filterFile(ref.id).writeText(text)
            return ref.copy(lastUpdated = System.currentTimeMillis(), etag = c.getHeaderField("ETag").orEmpty(), hostCount = FilterLists.parse(text).blocked.size)
        } finally { c.disconnect() }
    }

    fun delete(id: String) { store.filterFile(id).delete() }
}
