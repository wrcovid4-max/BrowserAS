package com.thebrowser.engine.chromium

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import com.thebrowser.core.shield.FilterEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CNAME-cloaking detection: a first-party-looking subdomain (metrics.example.com) whose DNS
 * CNAME points at a known tracker. Uses Android's DnsResolver to read the CNAME chain.
 * Limits: the first request to each host waits up to ~1.2 s for DNS; results are cached; it
 * depends on the system resolver; WebKit has no equivalent hook, so Apple builds cannot do this.
 */
internal class CnameChecker(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val cache = ConcurrentHashMap<String, Boolean>()

    fun isCloaked(host: String, filters: FilterEngine): Boolean {
        cache[host]?.let { return it }
        val cloaked = runCatching { cnames(host).any { filters.categoryOf(it) != null } }.getOrDefault(false)
        cache[host] = cloaked
        return cloaked
    }

    private fun cnames(host: String): List<String> {
        val net = cm.activeNetwork ?: return emptyList()
        val latch = CountDownLatch(1)
        var answer: ByteArray? = null
        DnsResolver.getInstance().rawQuery(
            net, host, DnsResolver.CLASS_IN, 5 /* CNAME */, DnsResolver.FLAG_EMPTY, { it.run() }, null,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(a: ByteArray, rcode: Int) { answer = a; latch.countDown() }
                override fun onError(error: DnsResolver.DnsException) { latch.countDown() }
            },
        )
        latch.await(1200, TimeUnit.MILLISECONDS)
        return parse(answer ?: return emptyList())
    }

    private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder(); var i = start; var end = -1; var hops = 0
        while (i < b.size && hops++ < 20) {
            val len = b[i].toInt() and 0xFF
            if (len == 0) { if (end < 0) end = i + 1; break }
            if (len and 0xC0 == 0xC0) { if (end < 0) end = i + 2; i = ((len and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF); continue }
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, i + 1, len, Charsets.US_ASCII)); i += len + 1
        }
        return sb.toString().lowercase() to (if (end < 0) i else end)
    }

    private fun parse(b: ByteArray): List<String> {
        if (b.size < 12) return emptyList()
        val qd = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        val an = ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
        var off = 12
        repeat(qd) { off = readName(b, off).second + 4 }
        val out = mutableListOf<String>()
        repeat(an) {
            if (off + 10 > b.size) return out
            off = readName(b, off).second
            val type = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
            val rdlen = ((b[off + 8].toInt() and 0xFF) shl 8) or (b[off + 9].toInt() and 0xFF)
            off += 10
            if (type == 5) out += readName(b, off).first
            off += rdlen
        }
        return out
    }
}
