package com.thebrowser.core.vault

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.ln

@kotlinx.serialization.Serializable
data class Credential(
    val id: String,
    val origin: String,      // scheme://host[:port]
    val username: String,
    val password: String,
    val note: String = "",
    val created: Long = 0L,
    val updated: Long = 0L,
)

object PasswordTools {
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*-_=+?"
    private val COMMON = setOf("password", "123456", "12345678", "qwerty", "abc123", "111111", "123123", "letmein", "welcome", "admin", "iloveyou", "monkey", "dragon", "football", "password1", "pakistan", "qwerty123", "000000", "1q2w3e4r", "zaq12wsx")

    fun generate(length: Int = 20, upper: Boolean = true, lower: Boolean = true, digits: Boolean = true, symbols: Boolean = true): String {
        val rnd = SecureRandom()
        val pools = buildList { if (lower) add(LOWER); if (upper) add(UPPER); if (digits) add(DIGITS); if (symbols) add(SYMBOLS) }.ifEmpty { listOf(LOWER) }
        val len = length.coerceIn(8, 128)
        val chars = MutableList(len) { pools[rnd.nextInt(pools.size)].let { p -> p[rnd.nextInt(p.length)] } }
        pools.forEachIndexed { i, p -> chars[i] = p[rnd.nextInt(p.length)] } // guarantee every chosen class appears
        chars.shuffle(rnd)
        return chars.joinToString("")
    }

    /** Rough entropy-based strength 0..4. A heuristic, not a guarantee. */
    fun strength(pw: String): Int {
        if (pw.isEmpty() || pw.lowercase() in COMMON) return 0
        var pool = 0
        if (pw.any { it.isLowerCase() }) pool += 26
        if (pw.any { it.isUpperCase() }) pool += 26
        if (pw.any { it.isDigit() }) pool += 10
        if (pw.any { !it.isLetterOrDigit() }) pool += 32
        var bits = pw.length * ln(pool.coerceAtLeast(1).toDouble()) / ln(2.0)
        if (Regex("(.)\\1{2,}").containsMatchIn(pw)) bits -= 10
        if (Regex("(0123|1234|2345|abcd|qwer|asdf)", RegexOption.IGNORE_CASE).containsMatchIn(pw)) bits -= 15
        return when { bits < 28 -> 0; bits < 40 -> 1; bits < 60 -> 2; bits < 80 -> 3; else -> 4 }
    }

    fun isWeak(pw: String) = strength(pw) <= 1

    fun reused(all: List<Credential>): Set<String> =
        all.groupBy { it.password }.filter { it.value.size > 1 }.values.flatten().map { it.id }.toSet()

    /** First 5 hex chars of SHA-1 (k-anonymity range query to Have I Been Pwned: the password never leaves the device). */
    fun sha1(pw: String): String = MessageDigest.getInstance("SHA-1").digest(pw.toByteArray()).joinToString("") { "%02X".format(it) }

    /** Chrome/Firefox-compatible CSV (name,url,username,password,note). */
    fun toCsv(list: List<Credential>): String = buildString {
        appendLine("name,url,username,password,note")
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        list.forEach { appendLine(listOf(it.origin.substringAfter("://"), it.origin, it.username, it.password, it.note).joinToString(",") { v -> q(v) }) }
    }

    fun fromCsv(text: String): List<Credential> {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return emptyList()
        val head = rows[0].map { it.lowercase() }
        val iu = head.indexOf("url"); val iun = head.indexOf("username"); val ip = head.indexOf("password"); val inote = head.indexOf("note")
        if (iu < 0 || ip < 0) return emptyList()
        val now = System.currentTimeMillis()
        return rows.drop(1).mapNotNull { r ->
            val url = r.getOrNull(iu).orEmpty()
            val origin = runCatching { java.net.URI(url).let { "${it.scheme}://${it.authority}" } }.getOrNull()
            if (origin == null || !origin.startsWith("http")) null
            else Credential(java.util.UUID.randomUUID().toString(), origin, r.getOrNull(iun).orEmpty(), r.getOrNull(ip).orEmpty(), r.getOrNull(inote).orEmpty(), now, now)
        }
    }

    private fun parseCsv(t: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val f = StringBuilder(); var inQ = false; var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                inQ && c == '"' && t.getOrNull(i + 1) == '"' -> { f.append('"'); i++ }
                c == '"' -> inQ = !inQ
                !inQ && c == ',' -> { row.add(f.toString()); f.clear() }
                !inQ && (c == '\n' || c == '\r') -> { if (c == '\n' || t.getOrNull(i + 1) != '\n') { row.add(f.toString()); f.clear(); if (row.any { it.isNotEmpty() }) rows.add(row); row = mutableListOf() } }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows
    }
}
