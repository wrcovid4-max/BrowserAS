package com.thebrowser.core.agent

import org.json.JSONObject

/** One browser capability exposed to agents. [scope] is what a paired agent must have been granted. */
data class ToolDef(val name: String, val description: String, val scope: String, val readOnly: Boolean, val schema: String)

data class AgentCaller(val id: String, val name: String, val scopes: Set<String>, val profile: com.thebrowser.core.model.PermissionProfile? = null)

/** [images] are base64 PNGs. */
data class AgentResult(val text: String, val images: List<String> = emptyList(), val isError: Boolean = false)

/**
 * The browser's tool set, shared by the external MCP server (Claude, ChatGPT, Muse, any MCP client)
 * and the built-in Gemini agent (function calling). Descriptions are written for the model.
 */
object AgentCatalog {
    private fun obj(props: String, required: String = "") =
        """{"type":"object","properties":{$props},"required":[$required]}"""
    private const val TAB = """"tabId":{"type":"string","description":"Tab id from browser_list_tabs. Omit to use the agent's own tab."}"""

    val tools = listOf(
        ToolDef("browser_list_tabs", "List the tabs this agent may use (id, title, url).", "read", true, obj("")),
        ToolDef("browser_read_page", "Read the page: visible text plus numbered interactive elements (ref e1, e2…) with their role, label and state. Call this before clicking or typing, and again after the page changes. Password and card fields are never shown.", "read", true, obj(TAB)),
        ToolDef("browser_screenshot", "Take a screenshot of the visible part of the page.", "read", true, obj(TAB)),
        ToolDef("browser_open_url", "Navigate to a URL (http or https). Security warnings stop the load and must be resolved by the user.", "navigate", false, obj(""""url":{"type":"string"},$TAB,"newTab":{"type":"boolean","description":"Open in a new agent tab"}""", """"url"""")),
        ToolDef("browser_click", "Click an element by ref from browser_read_page. Risky clicks (submit, buy, delete, send) may need the user's approval; purchases are blocked unless the user allowed them.", "interact", false, obj(""""ref":{"type":"string"},$TAB""", """"ref"""")),
        ToolDef("browser_type", "Type text into an input by ref. Never use for passwords or card numbers (blocked). Set submit=true to press Enter afterwards.", "interact", false, obj(""""ref":{"type":"string"},"text":{"type":"string"},"submit":{"type":"boolean"},$TAB""", """"ref","text"""")),
        ToolDef("browser_select", "Choose an option in a <select> by value or visible text.", "interact", false, obj(""""ref":{"type":"string"},"value":{"type":"string"},$TAB""", """"ref","value"""")),
        ToolDef("browser_press_key", "Press a key (Enter, Tab, Escape, ArrowDown, …) on the focused element.", "interact", false, obj(""""key":{"type":"string"},$TAB""", """"key"""")),
        ToolDef("browser_scroll", "Scroll the page. direction: up, down, top, bottom.", "interact", false, obj(""""direction":{"type":"string","enum":["up","down","top","bottom"]},$TAB""", """"direction"""")),
        ToolDef("browser_wait", "Wait up to 10 seconds for the page to settle.", "interact", true, obj(""""seconds":{"type":"number"}""")),
        ToolDef("browser_fill_login", "Ask the USER to unlock the password manager and fill the saved login for this site. The agent never sees the password.", "interact", false, obj(TAB)),
        ToolDef("browser_back", "Go back in history.", "navigate", false, obj(TAB)),
        ToolDef("browser_forward", "Go forward in history.", "navigate", false, obj(TAB)),
        ToolDef("browser_reload", "Reload the page.", "navigate", false, obj(TAB)),
        ToolDef("browser_new_tab", "Open a new agent tab (optionally at a URL).", "tabs", false, obj(""""url":{"type":"string"}""")),
        ToolDef("browser_switch_tab", "Bring a tab to the front.", "tabs", false, obj(""""tabId":{"type":"string"}""", """"tabId"""")),
        ToolDef("browser_close_tab", "Close a tab the agent opened.", "tabs", false, obj(""""tabId":{"type":"string"}""", """"tabId"""")),
    )

    val allScopes = listOf("read", "interact", "navigate", "tabs")

    fun toolsFor(scopes: Set<String>) = tools.filter { it.scope in scopes }

    fun toMcp(t: ToolDef): JSONObject = JSONObject()
        .put("name", t.name).put("description", t.description).put("inputSchema", JSONObject(t.schema))
        .put("annotations", JSONObject().put("readOnlyHint", t.readOnly).put("destructiveHint", false).put("openWorldHint", true))

    /** Provider-independent specs for the model gateway (each provider adapter converts them to its own format). */
    fun specs(names: Collection<String>): List<com.thebrowser.core.ai.ToolSpec> =
        tools.filter { it.name in names }.map { com.thebrowser.core.ai.ToolSpec(it.name, it.description, it.schema) }
}
