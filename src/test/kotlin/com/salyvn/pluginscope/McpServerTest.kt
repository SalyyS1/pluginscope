package com.salyvn.pluginscope

import com.salyvn.pluginscope.mcp.McpServer
import com.salyvn.pluginscope.mcp.ToolCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpServerTest {

    private val server = McpServer(ToolCatalog.tools(), serverName = "pluginscope", serverVersion = "test")

    private fun call(line: String): JsonObject =
        Json.parseToJsonElement(requireNotNull(server.handle(line)) { "expected a response to $line" }).jsonObject

    @Test
    fun `initialize answers with the protocol version the client asked for`() {
        val response = call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        )
        val result = response["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))
        assertEquals("pluginscope", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown protocol version falls back to the newest supported one`() {
        val response = call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}"""
        )
        assertEquals(
            McpServer.PROTOCOL_VERSION,
            response["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `notifications are not answered`() {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `every tool declares an object input schema`() {
        val tools = call("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["result"]!!
            .jsonObject["tools"]!!.jsonArray
        assertTrue(tools.size >= 4) { "only ${tools.size} tools registered" }
        for (tool in tools) {
            val entry = tool.jsonObject
            assertTrue(entry["name"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals("object", entry["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(entry["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.content == "true")
        }
    }

    @Test
    fun `an unknown tool is a protocol error, not a tool result`() {
        val response = call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"nope"}}""")
        assertEquals(-32602, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `an unknown method is method not found`() {
        val response = call("""{"jsonrpc":"2.0","id":4,"method":"resources/list"}""")
        assertEquals(-32601, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `malformed input is a parse error rather than a crash`() {
        val response = call("""{"jsonrpc":"2.0","id":5,"method":""")
        assertEquals(-32700, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `ping is answered with an empty result`() {
        val response = call("""{"jsonrpc":"2.0","id":6,"method":"ping"}""")
        assertTrue(response["result"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `scanning a jar returns the same report the CLI would print`() {
        val jar = FixtureJars.messyPlugin().toString().replace("\\", "\\\\")
        val response = call(
            """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"scan_jar","arguments":{"path":"$jar"}}}"""
        )
        val result = response["result"]!!.jsonObject
        assertEquals("false", result["isError"]!!.jsonPrimitive.content)
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        val payload = Json.parseToJsonElement(text).jsonObject
        val findings = payload["reports"]!!.jsonArray.single().jsonObject["findings"]!!.jsonArray
        assertTrue(findings.isNotEmpty())
        assertTrue(findings.any { it.jsonObject["ruleId"]!!.jsonPrimitive.content == "J021" })
    }

    @Test
    fun `a missing file comes back as a tool error the model can read`() {
        val response = call(
            """{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"scan_jar","arguments":{"path":"/nope/absent.jar"}}}"""
        )
        val result = response["result"]!!.jsonObject
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a required argument that is missing is reported as a tool error`() {
        val response = call("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"scan_jar","arguments":{}}}""")
        val result = response["result"]!!.jsonObject
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("path")) { text }
    }
}
