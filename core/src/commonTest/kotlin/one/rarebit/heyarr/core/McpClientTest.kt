package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.mcp.McpClient
import one.rarebit.heyarr.core.mcp.McpOutcome
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JSON-RPC envelope heyarr's MCP endpoint speaks, and how answers and refusals come apart. */
class McpClientTest {

    private class Capture(val status: Int = 200, val body: String) : HttpTransport {
        var url: String? = null
        var sent: String? = null
        var headers: Map<String, String> = emptyMap()
        override fun get(url: String, headers: Map<String, String>) = HttpResponse(405, "")
        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
            this.url = url
            this.sent = body
            this.headers = headers
            return HttpResponse(status, this.body)
        }
    }

    @Test
    fun postsAToolsCallEnvelopeWithBearerToTheMcpRoute() {
        val http = Capture(body = """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"count\":0}"}]}}""")
        val client = McpClient(http, "https://node.example:7777/", Credential.Bearer("heyarr_x_y"))
        val out = client.call("search_content", linkedMapOf("query" to "sintel", "limit" to 5, "content_type" to null))
        assertEquals("https://node.example:7777/api/v1/mcp", http.url)
        assertEquals("Bearer heyarr_x_y", http.headers["Authorization"])
        val root = JsonScan.rootObject(http.sent!!)!!
        assertEquals("tools/call", JsonScan.stringField(root, "method"))
        val params = JsonScan.objectAt(root, "params")!!
        assertEquals("search_content", JsonScan.stringField(params, "name"))
        val args = JsonScan.objectAt(params, "arguments")!!
        assertEquals("sintel", JsonScan.stringField(args, "query"))
        assertEquals(5, JsonScan.intField(args, "limit"))
        // A null argument is ABSENT, never sent as null.
        assertTrue(!args.contains("content_type"), args)
        assertEquals("""{"count":0}""", (out as McpOutcome.Ok).json)
    }

    @Test
    fun aRefusalCarriesTheServersOwnWordingAndTheTool() {
        val body = """{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"no candidate with that id for this want — run search_releases and look again","data":{"tool":"acquire_release"}}}"""
        val out = McpClient(Capture(body = body), "https://n", Credential.Bearer("t")).parse("acquire_release", 200, body)
        val refused = assertIs<McpOutcome.Refused>(out)
        assertEquals(-32602, refused.error.code)
        assertEquals("acquire_release", refused.error.tool)
        assertTrue(refused.error.message.startsWith("no candidate with that id"))
    }

    @Test
    fun isErrorContentIsARefusalToo() {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"isError":true,"content":[{"type":"text","text":"rejected by rule source.nin"}]}}"""
        val out = McpClient(Capture(body = body), "https://n", Credential.Bearer("t")).parse("acquire_release", 200, body)
        assertEquals("rejected by rule source.nin", assertIs<McpOutcome.Refused>(out).error.message)
    }

    @Test
    fun authAndTransportFailuresAreNotAnswers() {
        val c = McpClient(Capture(body = ""), "https://n", Credential.Bearer("t"))
        assertEquals(401, assertFailsWith<McpTransportException> { c.parse("x", 401, "") }.status)
        assertFailsWith<McpTransportException> { c.parse("x", 502, "<html>bad gateway</html>") }
        assertFailsWith<McpTransportException> { c.parse("x", 200, "not json") }
    }

    @Test
    fun jsonWriteDropsNullsEscapesAndNests() {
        val s = JsonWrite.obj(linkedMapOf("a" to "q\"uo\nte", "b" to null, "c" to listOf(1, true, mapOf("d" to 2.5)), "e" to 9_000_000_000L))
        assertEquals("""{"a":"q\"uo\nte","c":[1,true,{"d":2.5}],"e":9000000000}""", s)
        assertNull(JsonScan.valueStart(s, "b"))
    }
}
