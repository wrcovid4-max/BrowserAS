package com.thebrowser.core.agent

import com.thebrowser.core.model.AgentGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Pairing tokens: random 256-bit, shown once, only the SHA-256 hash is stored, compared in constant time. */
object AgentAuth {
    fun newToken(): String = "hy_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    fun verify(grants: List<AgentGrant>, token: String, now: Long): AgentGrant? {
        if (!token.startsWith("hy_")) return null
        val h = hash(token).toByteArray()
        return grants.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), h) && (it.expiresAt == 0L || it.expiresAt > now) }
    }
}

data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)
data class HttpReply(val status: Int, val body: String = "", val contentType: String = "application/json", val headers: Map<String, String> = emptyMap())

object HttpParser {
    const val MAX_HEADER = 16 * 1024
    const val MAX_BODY = 1024 * 1024
    private val reasons = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 413 to "Payload Too Large", 429 to "Too Many Requests", 500 to "Internal Server Error")

    /** Returns null for malformed or oversized requests. */
    fun read(input: InputStream): HttpRequest? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() <= MAX_HEADER) {
            val b = input.read(); if (b < 0) return null
            head.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        if (last4 != 0x0D0A0D0A) return null
        val lines = head.toString(Charsets.ISO_8859_1).split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size < 3) return null
        val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) return null
        val body = ByteArray(len); var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) return null; read += n }
        return HttpRequest(parts[0].uppercase(), parts[1], headers, String(body, Charsets.UTF_8))
    }

    fun write(r: HttpReply): ByteArray {
        val body = r.body.toByteArray()
        val h = StringBuilder("HTTP/1.1 ${r.status} ${reasons[r.status] ?: "OK"}\r\nContent-Type: ${r.contentType}\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
        r.headers.forEach { (k, v) -> h.append("$k: $v\r\n") }
        return h.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1) + body
    }
}

/** Tiny HTTP/1.1 server (one request per connection). Binds to loopback unless LAN access was explicitly enabled. */
class AgentHttpServer(private val scope: CoroutineScope, private val handler: suspend (HttpRequest) -> HttpReply) {
    private var server: ServerSocket? = null
    val running get() = server?.isClosed == false

    fun start(port: Int, lan: Boolean): Boolean = runCatching {
        stop()
        val s = ServerSocket(); s.reuseAddress = true
        s.bind(InetSocketAddress(if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress(), port), 8)
        server = s
        scope.launch(Dispatchers.IO) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                scope.launch(Dispatchers.IO) {
                    c.use { sock ->
                        sock.soTimeout = 15_000
                        val reply = runCatching { HttpParser.read(sock.getInputStream())?.let { handler(it) } ?: HttpReply(400, "{\"error\":\"bad request\"}") }
                            .getOrElse { HttpReply(500, "{\"error\":\"server error\"}") }
                        runCatching { sock.getOutputStream().apply { write(HttpParser.write(reply)); flush() } }
                    }
                }
            }
        }
        true
    }.getOrDefault(false)

    fun stop() { runCatching { server?.close() }; server = null }
}
