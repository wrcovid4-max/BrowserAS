package com.thebrowser.core.util

import com.thebrowser.core.model.Bookmark
import java.util.UUID

/** Netscape bookmark HTML: the format Chrome, Firefox and Safari all import and export. */
object BookmarksIO {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    fun export(bookmarks: List<Bookmark>): String = buildString {
        appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        appendLine("<TITLE>Bookmarks</TITLE><H1>Bookmarks</H1><DL><p>")
        bookmarks.groupBy { it.folder }.forEach { (folder, list) ->
            if (folder.isNotBlank()) appendLine("<DT><H3>${esc(folder)}</H3><DL><p>")
            list.forEach { appendLine("<DT><A HREF=\"${esc(it.url)}\">${esc(it.title.ifBlank { it.url })}</A>") }
            if (folder.isNotBlank()) appendLine("</DL><p>")
        }
        appendLine("</DL><p>")
    }

    fun import(html: String, profileId: String): List<Bookmark> {
        val out = mutableListOf<Bookmark>()
        val folders = ArrayDeque<String>()
        val token = Regex("""<H3[^>]*>(.*?)</H3>|<A\s[^>]*?HREF="([^"]*)"[^>]*>(.*?)</A>|</DL>""", RegexOption.IGNORE_CASE)
        var pendingFolder: String? = null
        for (m in token.findAll(html)) {
            val whole = m.value.uppercase()
            when {
                whole.startsWith("<H3") -> pendingFolder = unesc(m.groupValues[1])
                whole.startsWith("<A") -> {
                    val url = unesc(m.groupValues[2])
                    if (url.startsWith("http")) out += Bookmark(UUID.randomUUID().toString(), profileId, url, unesc(m.groupValues[3]), folders.lastOrNull().orEmpty())
                }
                whole.startsWith("</DL") -> if (folders.isNotEmpty()) folders.removeLast()
            }
            if (pendingFolder != null && whole.startsWith("<H3")) { folders.addLast(pendingFolder); pendingFolder = null }
        }
        return out
    }
}
