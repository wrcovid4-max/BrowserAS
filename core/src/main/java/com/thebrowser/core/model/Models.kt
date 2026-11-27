package com.thebrowser.core.model

import kotlinx.serialization.Serializable

const val DEFAULT_PROFILE_ID = "default"

@Serializable
data class Profile(val id: String, val name: String)

@Serializable
data class Space(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val emoji: String,
    val profileId: String = DEFAULT_PROFILE_ID,
)

@Serializable
data class TabGroup(
    val id: String,
    val spaceId: String,
    val name: String,
    val colorArgb: Long,
    val collapsed: Boolean = false,
)

@Serializable
data class Tab(
    val id: String,
    val spaceId: String,
    val url: String,
    val title: String = "",
    val pinned: Boolean = false,
    val favourite: Boolean = false,
    val temporary: Boolean = false,
    val groupId: String? = null,
    val lastActive: Long = 0L,
    /** Private tabs use a throw-away engine Profile and are never written to disk. */
    val isPrivate: Boolean = false,
) {
    val displayTitle: String
        get() = title.ifBlank {
            com.thebrowser.core.atc.AirTrafficControl.hostOf(url) ?: url.ifBlank { "New Tab" }
        }
}

/** A domain rule. [exception] rules veto routing for matching hosts. */
@Serializable
data class AtcRule(
    val id: String,
    val pattern: String,
    val spaceId: String,
    val enabled: Boolean = true,
    val exception: Boolean = false,
)

@Serializable
data class Bookmark(
    val id: String,
    val profileId: String,
    val url: String,
    val title: String,
    val folder: String = "",
)

@Serializable
data class HistoryEntry(
    val id: String,
    val profileId: String,
    val url: String,
    val title: String,
    val time: Long,
)

@Serializable
data class ClosedTab(val tab: Tab, val closedAt: Long)

@Serializable
data class ChatMessage(
    val role: String, // "user" | "model"
    val text: String,
    val attachmentLabel: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val updated: Long = 0L,
)

@Serializable
data class Clip(val url: String, val title: String, val text: String = "", val time: Long = 0L)

/** A note; clips turn a note into a simple research board. */
@Serializable
data class Note(
    val id: String,
    val profileId: String,
    val title: String,
    val body: String = "",
    val clips: List<Clip> = emptyList(),
    val updated: Long = 0L,
)

/** Offline-readable article. The HTML body lives in a file named after [id]. */
@Serializable
data class SavedArticle(
    val id: String,
    val profileId: String,
    val title: String,
    val url: String,
    val savedAt: Long,
    val wordCount: Int,
)

/** mode: allow | block | ask. expiresAt > 0 means "temporarily allowed until then". */
@Serializable
data class SitePermission(
    val profileId: String,
    val origin: String,
    val kind: String,
    val mode: String,
    val expiresAt: Long = 0L,
    val lastUsed: Long = 0L,
)

@Serializable
data class PermissionLogEntry(
    val time: Long,
    val profileId: String,
    val origin: String,
    val kind: String,
    val decision: String, // allowed | blocked | expired | revoked-inactive | reset
    val source: String,   // prompt | saved rule | default | maintenance
)

@Serializable
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val mime: String? = null,
    val profileId: String,
    val userAgent: String = "",
    val status: String = "running", // running | paused | done | failed | cancelled
    val total: Long = -1L,
    val received: Long = 0L,
    val savedUri: String? = null,
    val error: String? = null,
    val time: Long = 0L,
    /** Private downloads are never written to the downloads list on disk. */
    val isPrivate: Boolean = false,
)

@Serializable
data class InstalledExtension(
    val id: String,
    val name: String,
    val version: String,
    val manifestVersion: Int,
    val enabled: Boolean = false,
    val granted: Boolean = false,
    val permissions: List<String> = emptyList(),
    val optionalPermissions: List<String> = emptyList(),
    val hostPatterns: List<String> = emptyList(),
    /** Match patterns the USER approved. Anything not listed never runs. Broad patterns are OFF by default. */
    val allowedPatterns: List<String> = emptyList(),
    val unsupported: List<String> = emptyList(),
    val findings: List<String> = emptyList(),
    val publisher: String = "",
    val homepage: String = "",
    val contentScriptCount: Int = 0,
)

/** An agent (Claude, ChatGPT, Muse, any MCP client…) the user has paired. The raw token is shown once and only its hash is kept. */
@Serializable
data class AgentGrant(
    val id: String,
    val name: String,
    val tokenHash: String,
    val scopes: List<String>,       // read | interact | navigate | tabs
    val createdAt: Long,
    val expiresAt: Long = 0L,       // 0 = until revoked
    val lastUsed: Long = 0L,
)

@Serializable
data class AgentLogEntry(val time: Long, val agent: String, val tool: String, val host: String, val outcome: String)

/** mode: ask (confirm every action) | risky (confirm risky actions) | auto. Everything is OFF until the user turns it on. */
@Serializable
data class AgentSettings(
    val enabled: Boolean = false,
    val port: Int = 8765,
    val allowLan: Boolean = false,
    val mode: String = "risky",
    val allowAllTabs: Boolean = false,       // false = agents only see their own Agent Space
    val allowPurchases: Boolean = false,
    val blockedDomains: List<String> = emptyList(),
    val autoStopMinutes: Int = 15,
    val geminiAgentSteps: Int = 20,
)

/** Look & feel. mode: default | color | gradient | image. */
@Serializable
data class ThemeSettings(
    val mode: String = "default",
    val colorArgb: Long = 0xFFF5F5F8,
    val gradientA: Long = 0xFF6C5CE7,
    val gradientB: Long = 0xFF0984E3,
    val imageUri: String? = null,
    val imageDim: Float = 0.25f,
    val applyToSidebar: Boolean = true,
    val useSpaceColour: Boolean = false,
    val accentArgb: Long = 0L,        // 0 = follow Space / wallpaper colours
)

/** Network speed chip in the toolbar. mode: off | link (Wi-Fi link rate, Android) | measured (active test). */
@Serializable
data class NetSpeedSettings(
    val mode: String = "off",
    val testUrl: String = "https://speed.cloudflare.com/__down?bytes=2000000",
    val intervalSeconds: Int = 60,
    val wifiOnly: Boolean = true,
)

// ───────── AI platform: providers, agents, jobs, memory, integrations ─────────

/** kind: gemini | openai (any OpenAI-compatible endpoint: OpenAI, OpenRouter, Ollama, LM Studio…) | anthropic. API keys live in the Keystore, never here. */
@Serializable
data class ProviderConfig(
    val id: String,
    val kind: String,
    val label: String,
    val baseUrl: String = "",
    val model: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class UsageRecord(val day: String, val providerId: String, val model: String, val calls: Long = 0, val inputTokens: Long = 0, val outputTokens: Long = 0)

/** A permission profile is enforced in code before every tool call, not only in the prompt. */
@Serializable
data class PermissionProfile(
    val scopes: List<String> = listOf("read", "navigate"),     // read | interact | navigate | tabs | research | memory
    val readOnly: Boolean = false,
    val allowedDomains: List<String> = emptyList(),           // empty = any (except blocked)
    val blockedDomains: List<String> = emptyList(),
    val allowPurchases: Boolean = false,
    val approvalMode: String = "risky",                       // ask | risky | auto
    val usePrivateTabs: Boolean = false,                      // never true: private tabs are off-limits
)

@Serializable
data class AgentDef(
    val id: String,
    val name: String,
    val purpose: String,
    val instructions: String,
    val tools: List<String>,                  // tool names from AgentCatalog / research / memory
    val profile: PermissionProfile = PermissionProfile(),
    val providerId: String = "",              // "" = gateway default
    val maxSteps: Int = 25,
    val verify: Boolean = true,
    val builtIn: Boolean = false,
    val trigger: String = "manual",           // manual | schedule (schedule is not implemented yet)
)

@Serializable
data class JobStepRecord(val time: Long, val kind: String, val title: String, val detail: String = "", val approval: String = "")

@Serializable
data class JobSource(val n: Int, val url: String, val title: String, val fetchedAt: Long, val excerpt: String = "")

/** Persisted summary of a finished (or interrupted) run. */
@Serializable
data class JobRecord(
    val id: String,
    val agentId: String,
    val agentName: String,
    val goal: String,
    val kind: String = "single",              // single | multi | research
    val state: String,                        // DONE | FAILED | CANCELLED
    val startedAt: Long,
    val finishedAt: Long,
    val result: String = "",
    val steps: List<JobStepRecord> = emptyList(),
    val sources: List<JobSource> = emptyList(),
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

/** scope: session | project | long. Only approved items are ever read back; sensitive-looking text is refused. */
@Serializable
data class MemoryItem(
    val id: String,
    val scope: String,
    val project: String = "",
    val text: String,
    val source: String = "user",              // user | agent (agent proposals need approval)
    val createdAt: Long,
    val approved: Boolean = true,
)

/** Connected tool servers. kind: mcp | webhook. Secrets are in the Keystore. */
@Serializable
data class IntegrationConfig(
    val id: String,
    val kind: String,
    val name: String,
    val url: String,
    val enabled: Boolean = false,
    val scopes: List<String> = listOf("read"),
    val allowedTools: List<String> = emptyList(),   // MCP: tools the user approved; empty = none
)

@Serializable
data class AiSettings(
    val defaultProviderId: String = "gemini",
    val fallbackProviderIds: List<String> = emptyList(),
    val memoryRetentionDays: Int = 0,
    val keepJobHistoryDays: Int = 90,
    val adaptiveUi: Boolean = true,
    val notifyOnComplete: Boolean = true,
    val agentKeepAlive: Boolean = true,
)

const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"

@Serializable
data class Settings(
    val searchEngine: String = "duckduckgo",
    val blockPopups: Boolean = true,
    val thirdPartyCookies: Boolean = false,
    val sidebarCompact: Boolean = false,
    val themeMode: String = "system", // system | light | dark
    // Latest Flash model per Google's Gemini API docs (checked 14 Nov 2026).
    // "Refresh models" in Settings lists what your key can use.
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val translateTarget: String = "English",
    val readerFontSp: Int = 18,
    val readerTheme: String = "light", // light | sepia | dark
    val readerLineHeight: Float = 1.6f,
    val readerMarginDp: Int = 16,
    val temporaryTabHours: Int = 12,
    val trackingProtection: Boolean = true,
    val searchSuggestions: Boolean = false,
    val tabLayout: String = "vertical",      // vertical (sidebar) | horizontal (tab strip)
    val tabHoverPreview: Boolean = true,      // page preview when the pointer rests on a tab
    val tabHoverMemory: Boolean = true,       // memory figure in that preview
    val passkeys: Boolean = true,
    val dynamicColor: Boolean = true,
    val shield: com.thebrowser.core.shield.ShieldSettings = com.thebrowser.core.shield.ShieldSettings(),
    val agent: AgentSettings = AgentSettings(),
    val theme: ThemeSettings = ThemeSettings(),
    val netSpeed: NetSpeedSettings = NetSpeedSettings(),
    val ai: AiSettings = AiSettings(),
    val openPdfInViewer: Boolean = true,
    val paymentsEnabled: Boolean = true,
    val handoffEnabled: Boolean = true,
    val privateAutoLock: Boolean = true,
    val permissionDefaults: Map<String, String> = emptyMap(),
    val permissionExpiryHours: Int = 24,
    val inactivityRevokeDays: Int = 90,
    val secureDnsAcknowledged: Boolean = false,
    val passwordManager: Boolean = true,
    val neverSavePasswords: List<String> = emptyList(),
    val downloadTreeUri: String? = null,
    val littleWindowForExternal: Boolean = false,
)

@Serializable
data class BrowserState(
    val profiles: List<Profile> = listOf(Profile(DEFAULT_PROFILE_ID, "Personal")),
    val spaces: List<Space> = listOf(Space("space-home", "Home", 0xFF6C5CE7, "🏠", DEFAULT_PROFILE_ID)),
    val groups: List<TabGroup> = emptyList(),
    val tabs: List<Tab> = emptyList(),
    val rules: List<AtcRule> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val closed: List<ClosedTab> = emptyList(),
    val notes: List<Note> = emptyList(),
    val articles: List<SavedArticle> = emptyList(),
    val permissions: List<SitePermission> = emptyList(),
    val permissionLog: List<PermissionLogEntry> = emptyList(),
    val extensions: List<InstalledExtension> = emptyList(),
    val agentGrants: List<AgentGrant> = emptyList(),
    val providers: List<ProviderConfig> = listOf(ProviderConfig("gemini", "gemini", "Google Gemini", "https://generativelanguage.googleapis.com", DEFAULT_GEMINI_MODEL)),
    val usage: List<UsageRecord> = emptyList(),
    val agents: List<AgentDef> = emptyList(),
    val jobs: List<JobRecord> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val integrations: List<IntegrationConfig> = emptyList(),
    val agentLog: List<AgentLogEntry> = emptyList(),
    /** Hide Distracting Items: CSS selectors the user hid, per site (host). Never written for private tabs. */
    val hiddenElements: Map<String, List<String>> = emptyMap(),
    val activeSpaceId: String = "space-home",
    val activeTabId: String? = null,
    val splitTabId: String? = null,
    val settings: Settings = Settings(),
) {
    val activeSpace: Space get() = spaces.firstOrNull { it.id == activeSpaceId } ?: spaces.first()
    val activeTab: Tab? get() = tabs.firstOrNull { it.id == activeTabId }
    fun profileIdOfSpace(spaceId: String): String =
        spaces.firstOrNull { it.id == spaceId }?.profileId ?: DEFAULT_PROFILE_ID
}

/** A saved/exported Space (tabs + groups) for the Save/Restore Space feature. */
@Serializable
data class SpaceSnapshot(val space: Space, val groups: List<TabGroup>, val tabs: List<Tab>)


/** One website permission the Permission Centre knows about, with an honest per-platform support level. */
data class PermissionKind(
    val id: String,
    val label: String,
    val reason: String,          // plain-language explanation shown in prompts
    val support: String,         // prompted | policy | unsupported
    val defaultMode: String,     // ask | block | allow
    val note: String = "",
)

object PermissionKinds {
    val all = listOf(
        PermissionKind("camera", "Camera", "lets the site see through your camera (video calls, photos).", "prompted", "ask"),
        PermissionKind("microphone", "Microphone", "lets the site hear you (calls, voice input).", "prompted", "ask"),
        PermissionKind("location", "Location", "lets the site know where you are.", "prompted", "ask"),
        PermissionKind("midi", "MIDI devices", "lets the site control music hardware connected to your device.", "prompted", "ask"),
        PermissionKind("popups", "Pop-ups", "lets the site open extra windows without you tapping a link.", "policy", "block"),
        PermissionKind("javascript", "JavaScript", "lets the site run code. Many sites break without it.", "policy", "allow", "Blocking applies to the next page load."),
        PermissionKind("third_party_cookies", "Third-party cookies", "lets other companies embedded in the page set cookies.", "policy", "block", "Follows the protection level unless you override it here."),
        PermissionKind("notifications", "Notifications", "would let the site send you notifications.", "unsupported", "block", "Android WebView has no web-notification support, so sites can never send them."),
        PermissionKind("clipboard", "Clipboard", "would let the site read what you copied.", "unsupported", "block", "Engine-managed: sites may write on a tap, reading is not exposed to apps."),
        PermissionKind("auto_downloads", "Automatic downloads", "would let the site start several downloads.", "unsupported", "ask", "Every download is shown to you and risky ones are warned."),
        PermissionKind("background_sync", "Background sync", "would let the site work in the background.", "unsupported", "block", "WebView does not run service-worker background sync."),
        PermissionKind("motion", "Motion sensors", "would let the site read device motion.", "unsupported", "ask", "Engine-managed; WebView exposes no switch."),
        PermissionKind("usb", "USB", "would let the site talk to USB devices.", "unsupported", "block", "WebUSB is not available in WebView."),
        PermissionKind("bluetooth", "Bluetooth", "would let the site connect to Bluetooth devices.", "unsupported", "block", "Web Bluetooth is not available in WebView."),
        PermissionKind("serial", "Serial devices", "would let the site talk to serial devices.", "unsupported", "block", "Web Serial is not available in WebView."),
        PermissionKind("screen_capture", "Screen capture", "would let the site record your screen.", "unsupported", "block", "getDisplayMedia is not available in WebView."),
    )
    fun of(id: String) = all.firstOrNull { it.id == id }
    fun defaultMode(id: String, overrides: Map<String, String>) = overrides[id] ?: of(id)?.defaultMode ?: "ask"
}
