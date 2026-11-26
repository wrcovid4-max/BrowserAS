package com.thebrowser.core.agent

import com.thebrowser.core.model.AgentDef
import com.thebrowser.core.model.PermissionProfile

/** The twelve specialists. `limits` states plainly what each one cannot do yet. */
object Templates {
    private val READ = listOf("browser_list_tabs", "browser_read_page", "browser_screenshot", "browser_scroll", "browser_wait")
    private val NAV = listOf("browser_open_url", "browser_back", "browser_forward", "browser_reload", "browser_new_tab", "browser_switch_tab", "browser_close_tab")
    private val ACT = listOf("browser_click", "browser_type", "browser_select", "browser_press_key", "browser_fill_login")
    private val RESEARCH = listOf("web_search", "fetch_page")
    private val MEM = listOf("memory_search", "memory_propose")
    private val ro = PermissionProfile(scopes = listOf("read", "navigate", "tabs", "research", "memory"), readOnly = true, approvalMode = "risky")
    private val interactive = PermissionProfile(scopes = listOf("read", "navigate", "tabs", "interact", "research", "memory"), approvalMode = "risky")

    private fun a(id: String, name: String, purpose: String, ins: String, tools: List<String>, p: PermissionProfile, limits: String = "") =
        AgentDef(id, name, purpose, ins + if (limits.isNotBlank()) "\nKnown limits: $limits" else "", tools, p, builtIn = true)

    val all = listOf(
        a("research", "Research Agent", "Multi-source investigation with citations.", "Search, open several independent sources, compare claims, prefer primary sources, cite every factual claim as [n], and list what you could not verify.", READ + NAV + RESEARCH + MEM, ro),
        a("assistant", "Personal Assistant", "Everyday help using the browser.", "Help with the user's request step by step. Ask before anything consequential.", READ + NAV + ACT + MEM, interactive),
        a("coding", "Coding Agent", "Explain and write code; read repositories on the web.", "Read documentation and public repositories in the browser, explain code, and propose scoped changes as text.", READ + NAV + RESEARCH + MEM, ro, "cannot run code, edit files, or run tests; produces explanations and snippets only."),
        a("shopping", "Shopping Comparison", "Compare products, prices and sellers.", "Compare specifications, prices, availability and seller reputation using consistent criteria. Never buy anything.", READ + NAV + RESEARCH, ro.copy(allowPurchases = false)),
        a("study", "Study & Learning", "Explain topics and make revision material.", "Explain clearly, build revision notes and practice questions from the material the user supplies or from cited sources.", READ + NAV + RESEARCH + MEM, ro),
        a("email", "Email Assistant", "Summarise and draft replies.", "Summarise messages the user has open and draft replies. Never send.", READ + NAV + MEM, ro, "no mailbox connection yet (Gmail/Outlook connectors need OAuth client IDs); works only on webmail pages the user has open."),
        a("travel", "Travel Research", "Compare destinations and options; build itineraries.", "Research destinations and options from several sources, compare fairly, and produce an itinerary. Never book.", READ + NAV + RESEARCH, ro),
        a("data", "Data Analyst", "Extract and analyse data from pages.", "Extract tables and figures from pages, compute results carefully, and show your working.", READ + NAV + RESEARCH, ro, "no spreadsheet or chart generation yet; returns tables as text."),
        a("writing", "Writing Agent", "Draft, rewrite, proofread, translate.", "Write exactly what is asked in the requested tone and format; do not invent facts or quotes.", READ + MEM, ro.copy(scopes = listOf("read", "memory"))),
        a("security", "Security & Privacy Agent", "Spot phishing, risky downloads and deceptive requests.", "Inspect the page and address, look for phishing signs, lookalike domains, deceptive permission requests; explain the risk plainly.", READ + NAV, ro),
        a("meeting", "Meeting Assistant", "Briefs, notes, decisions and action items.", "From notes or pages the user provides, produce briefs, decisions and action items with owners.", READ + MEM, ro, "no calendar or audio connection yet."),
        a("content", "Content Planner", "Research topics and draft a publishing calendar.", "Research topics and draft posts and a calendar. Never publish.", READ + NAV + RESEARCH, ro, "cannot post to any platform."),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }
}
