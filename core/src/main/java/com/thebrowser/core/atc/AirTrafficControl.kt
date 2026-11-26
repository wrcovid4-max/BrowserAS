package com.thebrowser.core.atc

import com.thebrowser.core.model.AtcRule
import com.thebrowser.core.model.Space
import java.net.URI

sealed class AtcDecision {
    /** No rule applies (or an exception vetoed routing). */
    data object None : AtcDecision()

    /**
     * Route to [space]. [needsProfileConsent] is true when the target Space uses a different
     * Profile than the current one: the caller MUST ask the user before switching Profile.
     */
    data class Route(val rule: AtcRule, val space: Space, val needsProfileConsent: Boolean) : AtcDecision()
}

/**
 * Domain-based routing.
 *
 * Pattern semantics (host only; scheme/path are ignored):
 *  - `example.com`     matches `example.com` and `www.example.com`
 *  - `*.example.com`   matches any subdomain, NOT the apex
 *  - `*example.com`    matches the apex and any host ending in it
 *  - `docs.*.com`      `*` matches any run of characters
 * Exception rules (exception = true) win over routing rules.
 */
object AirTrafficControl {

    fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun normalise(pattern: String): String =
        pattern.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/')

    fun matches(pattern: String, host: String): Boolean {
        val p = normalise(pattern)
        val h = host.lowercase()
        if (p.isEmpty()) return false
        if (!p.contains('*')) return h == p || h == "www.$p"
        val regex = p.split("*").joinToString(".*") { Regex.escape(it) }
        return Regex(regex).matches(h)
    }

    fun decide(url: String, rules: List<AtcRule>, spaces: List<Space>, currentSpaceId: String): AtcDecision {
        val host = hostOf(url) ?: return AtcDecision.None
        val active = rules.filter { it.enabled }
        if (active.any { it.exception && matches(it.pattern, host) }) return AtcDecision.None
        val current = spaces.firstOrNull { it.id == currentSpaceId }
        for (rule in active) {
            if (rule.exception || !matches(rule.pattern, host)) continue
            val target = spaces.firstOrNull { it.id == rule.spaceId } ?: continue
            return AtcDecision.Route(rule, target, needsProfileConsent = target.profileId != current?.profileId)
        }
        return AtcDecision.None
    }
}
