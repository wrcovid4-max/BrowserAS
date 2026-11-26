package com.thebrowser.core.net

import java.net.HttpURLConnection
import java.net.URL

data class SpeedResult(val mbps: Double, val latencyMs: Long, val bytes: Long)

/**
 * Measured download throughput: time to fetch a small file over HTTPS from the test URL (default: Cloudflare's
 * public speed endpoint). It measures the path to THAT server right now, not your ISP plan. Only when enabled.
 */
object NetSpeed {
    fun mbps(bytes: Long, ms: Long): Double = if (ms <= 0) 0.0 else bytes * 8.0 / 1_000_000.0 / (ms / 1000.0)

    fun format(mbps: Double): String = when {
        mbps >= 100 -> "${mbps.toInt()} Mbps"
        mbps >= 10 -> "%.0f Mbps".format(mbps)
        mbps >= 0.1 -> "%.1f Mbps".format(mbps)
        else -> "<0.1 Mbps"
    }

    /** Throws on failure. Reads at most [maxBytes] and stops after [timeoutMs]. */
    fun measure(url: String, maxBytes: Long = 3_000_000, timeoutMs: Int = 8_000): SpeedResult {
        require(url.startsWith("https://")) { "The speed-test URL must use HTTPS." }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000; c.readTimeout = timeoutMs; c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            val t0 = System.nanoTime()
            val code = c.responseCode
            val latency = (System.nanoTime() - t0) / 1_000_000
            if (code !in 200..299) error("HTTP $code")
            val start = System.nanoTime(); var total = 0L
            val buf = ByteArray(32 * 1024)
            c.inputStream.use { while (total < maxBytes && (System.nanoTime() - start) / 1_000_000 < timeoutMs) { val n = it.read(buf); if (n < 0) break; total += n } }
            return SpeedResult(mbps(total, (System.nanoTime() - start) / 1_000_000), latency, total)
        } finally { c.disconnect() }
    }
}
