package com.thebrowser.core.state

import android.content.Context
import com.thebrowser.core.model.BrowserState
import com.thebrowser.core.model.Conversation
import com.thebrowser.core.model.DownloadItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Atomic JSON persistence in app-private storage. Survives app updates. */
class StateStore(context: Context) {
    private val stateFile = File(context.filesDir, "state.json")
    private val convFile = File(context.filesDir, "gemini_conversations.json")
    private val downloadsFile = File(context.filesDir, "downloads.json")
    private val statsFile = File(context.filesDir, "protection_stats.json")
    private val filterDir = File(context.filesDir, "filters").apply { mkdirs() }

    fun loadStats(): com.thebrowser.core.stats.ProtectionStats = runCatching {
        if (statsFile.exists()) AppJson.decodeFromString(com.thebrowser.core.stats.ProtectionStats.serializer(), statsFile.readText()) else com.thebrowser.core.stats.ProtectionStats()
    }.getOrDefault(com.thebrowser.core.stats.ProtectionStats())

    @Synchronized
    fun saveStats(s: com.thebrowser.core.stats.ProtectionStats) = writeAtomic(statsFile, AppJson.encodeToString(com.thebrowser.core.stats.ProtectionStats.serializer(), s))

    fun filterFile(id: String) = File(filterDir, "$id.txt")

    fun loadState(): BrowserState = runCatching {
        if (stateFile.exists()) AppJson.decodeFromString(BrowserState.serializer(), stateFile.readText()) else BrowserState()
    }.getOrDefault(BrowserState())

    /** Private tabs are stripped here, so they can never reach disk. */
    @Synchronized
    fun saveState(state: BrowserState) {
        val tabs = state.tabs.filter { !it.isPrivate }
        val ids = tabs.map { it.id }.toSet()
        val clean = state.copy(
            tabs = tabs,
            activeTabId = state.activeTabId?.takeIf { it in ids },
            splitTabId = state.splitTabId?.takeIf { it in ids },
            closed = state.closed.filter { !it.tab.isPrivate },
        )
        writeAtomic(stateFile, AppJson.encodeToString(BrowserState.serializer(), clean))
    }

    fun loadDownloads(): List<DownloadItem> = runCatching {
        if (downloadsFile.exists()) AppJson.decodeFromString(ListSerializer(DownloadItem.serializer()), downloadsFile.readText()) else emptyList()
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveDownloads(list: List<DownloadItem>) =
        writeAtomic(downloadsFile, AppJson.encodeToString(ListSerializer(DownloadItem.serializer()), list.filter { !it.isPrivate }))

    private val articleDir = File(context.filesDir, "offline").apply { mkdirs() }
    fun saveArticleBody(id: String, json: String) = File(articleDir, "$id.json").writeText(json)
    fun loadArticleBody(id: String): String? = File(articleDir, "$id.json").takeIf { it.exists() }?.readText()
    fun deleteArticleBody(id: String) { File(articleDir, "$id.json").delete() }

    fun loadConversations(): List<Conversation> = runCatching {
        if (convFile.exists()) AppJson.decodeFromString(ListSerializer(Conversation.serializer()), convFile.readText()) else emptyList()
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveConversations(list: List<Conversation>) =
        writeAtomic(convFile, AppJson.encodeToString(ListSerializer(Conversation.serializer()), list))

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
