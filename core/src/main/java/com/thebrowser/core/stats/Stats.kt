package com.thebrowser.core.stats

import com.thebrowser.core.shield.ShieldEventKind
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Real counters only. Nothing is estimated; a counter moves only when the browser actually acted. */
@Serializable
data class ProtectionStats(
    val since: Long = System.currentTimeMillis(),
    val total: Map<String, Long> = emptyMap(),
    val days: Map<String, Map<String, Long>> = emptyMap(), // yyyy-MM-dd → kind → count
) {
    fun record(kind: ShieldEventKind, n: Long = 1): ProtectionStats {
        val day = today()
        val d = days[day].orEmpty().toMutableMap()
        d[kind.name] = (d[kind.name] ?: 0) + n
        val t = total.toMutableMap()
        t[kind.name] = (t[kind.name] ?: 0) + n
        // keep 90 days
        val keep = days.toSortedMap().entries.toList().takeLast(89).associate { it.key to it.value }
        return copy(total = t, days = keep + (day to d))
    }

    fun totalOf(kind: ShieldEventKind) = total[kind.name] ?: 0L
    fun todayOf(kind: ShieldEventKind) = days[today()]?.get(kind.name) ?: 0L
    fun totalAll(vararg kinds: ShieldEventKind) = kinds.sumOf { totalOf(it) }
    fun todayAll(vararg kinds: ShieldEventKind) = kinds.sumOf { todayOf(it) }

    companion object {
        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}

/** Per-page counters shown in the current-website panel; reset on every top-level navigation. */
data class PageShield(
    val counts: Map<ShieldEventKind, Int> = emptyMap(),
    val upgradedToHttps: Boolean = false,
    val blockedHosts: Set<String> = emptySet(),
) {
    fun with(kind: ShieldEventKind, host: String): PageShield =
        copy(counts = counts + (kind to ((counts[kind] ?: 0) + 1)), blockedHosts = if (blockedHosts.size < 200) blockedHosts + host else blockedHosts)

    fun of(kind: ShieldEventKind) = counts[kind] ?: 0
}
