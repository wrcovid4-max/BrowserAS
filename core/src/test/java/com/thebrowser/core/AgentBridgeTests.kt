package com.thebrowser.core

import com.thebrowser.core.agent.*
import com.thebrowser.core.model.AgentGrant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AgentBridgeTests {
    private class Fake : ToolBackend {
        var called: String? = null
        override suspend fun execute(name: String, args: JSONObject, caller: AgentCaller): AgentResult { called = name; return AgentResult("did $name", listOf("QUJD")) }
    }
    private val reader = AgentCaller("1", "Claude", setOf("read"))
    private fun rpc(method: String, params: String = "{}", id: Int? = 1) = """{"jsonrpc":"2.0",${if (id != null) "\"id\":$id," else ""}"method":"$method","params":$params}"""

    @Test fun initializeNegotiatesVersionAndAdvertisesTools() = runBlocking {
        val r = JSONObject(McpServer(Fake()).handle(rpc("initialize", """{"protocolVersion":"2025-06-18"}"""), reader)!!).getJSONObject("result")
        assertEquals("2025-06-18", r.getString("protocolVersion")); assertTrue(r.getJSONObject("capabilities").has("tools"))
        val unknown = JSONObject(McpServer(Fake()).handle(rpc("initialize", """{"protocolVersion":"1999-01-01"}"""), reader)!!).getJSONObject("result")
        assertEquals(McpServer.SUPPORTED_VERSIONS.first(), unknown.getString("protocolVersion"))
    }

    @Test fun toolsListIsFilteredByGrantedScopes() = runBlocking {
        val tools = JSONObject(McpServer(Fake()).handle(rpc("tools/list"), reader)!!).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue("browser_read_page" in names); assertFalse("browser_click" in names)
    }

    @Test fun callWithoutScopeIsRefusedAndNeverReachesTheBackend() = runBlocking {
        val b = Fake()
        val r = JSONObject(McpServer(b).handle(rpc("tools/call", """{"name":"browser_click","arguments":{"ref":"e1"}}"""), reader)!!).getJSONObject("result")
        assertTrue(r.getBoolean("isError")); assertNull(b.called)
    }

    @Test fun permittedCallReturnsTextAndImageContent() = runBlocking {
        val b = Fake()
        val r = JSONObject(McpServer(b).handle(rpc("tools/call", """{"name":"browser_screenshot","arguments":{}}"""), reader)!!).getJSONObject("result")
        assertFalse(r.getBoolean("isError")); assertEquals("browser_screenshot", b.called)
        assertEquals("image", r.getJSONArray("content").getJSONObject(1).getString("type"))
    }

    @Test fun notificationsGetNoBodyAndBadInputGetsErrors() = runBlocking {
        val s = McpServer(Fake())
        assertNull(s.handle(rpc("notifications/initialized", id = null), reader))
        assertEquals(-32700, JSONObject(s.handle("not json", reader)!!).getJSONObject("error").getInt("code"))
        assertEquals(-32601, JSONObject(s.handle(rpc("nope"), reader)!!).getJSONObject("error").getInt("code"))
        assertEquals(-32602, JSONObject(s.handle(rpc("tools/call", """{"name":"rm_rf"}"""), reader)!!).getJSONObject("error").getInt("code"))
    }

    @Test fun backendExceptionsBecomeToolErrors() = runBlocking {
        val boom = object : ToolBackend { override suspend fun execute(name: String, args: JSONObject, caller: AgentCaller): AgentResult = error("kaput") }
        val r = JSONObject(McpServer(boom).handle(rpc("tools/call", """{"name":"browser_list_tabs"}"""), reader)!!).getJSONObject("result")
        assertTrue(r.getBoolean("isError"))
    }

    @Test fun tokensAreHashedExpiringAndRevocable() {
        val t = AgentAuth.newToken(); assertTrue(t.startsWith("hy_")); assertNotEquals(t, AgentAuth.newToken())
        val g = AgentGrant("1", "x", AgentAuth.hash(t), listOf("read"), 0L, expiresAt = 1000L)
        assertNotNull(AgentAuth.verify(listOf(g), t, 500L)); assertNull(AgentAuth.verify(listOf(g), t, 2000L))
        assertNull(AgentAuth.verify(emptyList(), t, 0L)); assertNull(AgentAuth.verify(listOf(g), "hy_wrong", 0L)); assertFalse(g.tokenHash.contains(t))
    }

    @Test fun httpParserReadsBodyAndRejectsOversize() {
        val raw = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:8765\r\nContent-Length: 5\r\nAuthorization: Bearer hy_a\r\n\r\nhello"
        val r = HttpParser.read(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("POST", r.method); assertEquals("/mcp", r.path); assertEquals("hello", r.body); assertEquals("Bearer hy_a", r.headers["authorization"])
        assertNull(HttpParser.read(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n".toByteArray())))
        assertNull(HttpParser.read(ByteArrayInputStream("garbage".toByteArray())))
        assertTrue(String(HttpParser.write(HttpReply(401, "{}"))).startsWith("HTTP/1.1 401 Unauthorized"))
    }

    @Test fun pageHelperFormatsWithoutValuesOfProtectedFieldsAndFlagsRisk() {
        val json = """{"url":"https://a.test","title":"T","scrollY":0,"pageHeight":10,"elements":[{"ref":"e1","tag":"input","type":"password","name":"Password","protected":true},{"ref":"e2","tag":"button","name":"Place order","payment":true,"submit":true}],"text":"hi"}"""
        val t = AgentJs.format(json)
        assertTrue(t.contains("[protected")); assertTrue(t.contains("needs approval: payment/submit"))
        assertTrue(AgentJs.risk("""{"host":"a.test","payment":true,"label":"Pay"}""").payment)
        assertTrue(AgentJs.call("click", "e3").contains("click(\"e3\")"))
        try { AgentJs.call("eval", "x"); fail() } catch (e: IllegalArgumentException) { }
    }
}
