package com.thebrowser.core.ai

import com.thebrowser.core.model.UsageRecord

/** Token and call accounting per provider, model and day. Counts come from the provider's own response; nothing is estimated. */
object UsageTracker {
    fun add(list: List<UsageRecord>, day: String, providerId: String, model: String, u: Usage, keepDays: Int = 90): List<UsageRecord> {
        val i = list.indexOfFirst { it.day == day && it.providerId == providerId && it.model == model }
        val merged = if (i >= 0) list.toMutableList().also { it[i] = it[i].copy(calls = it[i].calls + 1, inputTokens = it[i].inputTokens + u.inputTokens, outputTokens = it[i].outputTokens + u.outputTokens) }
        else list + UsageRecord(day, providerId, model, 1, u.inputTokens, u.outputTokens)
        val days = merged.map { it.day }.distinct().sortedDescending().take(keepDays).toSet()
        return merged.filter { it.day in days }
    }

    fun totals(list: List<UsageRecord>): Map<String, Triple<Long, Long, Long>> =
        list.groupBy { it.providerId }.mapValues { (_, v) -> Triple(v.sumOf { it.calls }, v.sumOf { it.inputTokens }, v.sumOf { it.outputTokens }) }
}
