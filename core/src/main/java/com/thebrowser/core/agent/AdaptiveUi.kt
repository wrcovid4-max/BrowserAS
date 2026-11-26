package com.thebrowser.core.agent

enum class AdaptiveState { HUMAN_BROWSING, AI_ASSISTANT_ACTIVE, AGENT_WORKING, MULTI_AGENT_WORKING, RESEARCH_ACTIVE, APPROVAL_REQUIRED, RESULTS_READY, BACKGROUND_AGENT_ACTIVITY }
enum class Panel { NONE, ASSISTANT, AGENT, MULTI, RESEARCH, APPROVAL, RESULTS }

data class JobUi(val id: String, val kind: String, val running: Boolean, val waitingApproval: Boolean, val visible: Boolean, val resultsUnseen: Boolean)

/** Separate inputs: execution (jobs), visibility, human focus/typing, approvals, notifications. No single "AI open" flag. */
data class UiInputs(
    val assistantOpen: Boolean = false,
    val jobs: List<JobUi> = emptyList(),
    val humanTyping: Boolean = false,
    val humanBusy: Boolean = false,      // touched/scrolled/typed within the last few seconds
    val externalApproval: Boolean = false, // an outside agent (MCP) is waiting for approval
)

data class AdaptiveSnapshot(
    val states: Set<AdaptiveState>,
    val panel: Panel,
    val indicator: Boolean,              // compact activity chip
    val approvalAsChip: Boolean,         // approval pending but the human is typing: never interrupt
    val notify: Boolean,                 // compact notification worth showing
)

object AdaptiveUiController {
    fun derive(i: UiInputs): AdaptiveSnapshot {
        val active = i.jobs.filter { it.running || it.waitingApproval }
        val approvals = active.any { it.waitingApproval } || i.externalApproval
        val unseen = i.jobs.any { it.resultsUnseen }
        val states = mutableSetOf<AdaptiveState>()
        if (i.assistantOpen) states += AdaptiveState.AI_ASSISTANT_ACTIVE
        val visibleActive = active.filter { it.visible }
        if (visibleActive.any { it.kind == "single" || it.kind == "worker" }) states += AdaptiveState.AGENT_WORKING
        if (visibleActive.any { it.kind == "multi" }) states += AdaptiveState.MULTI_AGENT_WORKING
        if (visibleActive.any { it.kind == "research" }) states += AdaptiveState.RESEARCH_ACTIVE
        if (active.any { !it.visible }) states += AdaptiveState.BACKGROUND_AGENT_ACTIVITY
        if (approvals) states += AdaptiveState.APPROVAL_REQUIRED
        if (unseen && active.isEmpty()) states += AdaptiveState.RESULTS_READY
        if (states.isEmpty()) states += AdaptiveState.HUMAN_BROWSING

        // Human priority: never open or force a panel while the person is typing or busy, and never stop anything.
        val interrupt = !i.humanTyping && !i.humanBusy
        val approvalAsChip = approvals && !interrupt
        val panel = when {
            approvals && interrupt -> Panel.APPROVAL
            visibleActive.any { it.kind == "multi" } && !i.humanBusy -> Panel.MULTI
            visibleActive.any { it.kind == "research" } && !i.humanBusy -> Panel.RESEARCH
            visibleActive.isNotEmpty() && !i.humanBusy -> Panel.AGENT
            i.assistantOpen -> Panel.ASSISTANT
            unseen && active.isEmpty() && i.jobs.any { it.visible && it.resultsUnseen } && interrupt -> Panel.RESULTS
            else -> Panel.NONE
        }
        val indicator = active.isNotEmpty() && (panel == Panel.NONE || panel == Panel.ASSISTANT || approvalAsChip)
        return AdaptiveSnapshot(states, panel, indicator, approvalAsChip, notify = approvals || (unseen && active.isEmpty()))
    }
}
