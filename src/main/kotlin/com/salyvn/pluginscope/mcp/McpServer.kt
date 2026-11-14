package com.salyvn.pluginscope.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One tool the server exposes. Every tool here reads files and returns a report; none of them write. */
interface McpTool {
    val name: String
    val title: String
    val description: String
    val inputSchema: JsonObject

    fun call(arguments: JsonObject): ToolResult
}

/**
 * The outcome of a tool call. [isError] carries a failed *execution*: a missing file, an
 * unreadable jar: back to the model as content it can act on, which is what the specification
 * asks for. Protocol-level problems (unknown tool, bad request shape) are JSON-RPC errors instead
 * and never reach here.
 */
data class ToolResult(val text: String, val isError: Boolean = false)

/**
 * Serves pluginscope's analyses to an AI coding agent over MCP.
 *
 * Only the handshake era with an `initialize` exchange is implemented, which the specification
 * calls "legacy" and every shipping client still speaks; a client that probes for the newer
 * discovery method first receives METHOD_NOT_FOUND and falls back, which the versioning document
 * describes as the expected path for a legacy-only server.
 *
 * The whole surface is four requests and one notification. Anything else is answered with
 * METHOD_NOT_FOUND rather than guessed at.
 */
class McpServer(
    private val tools: List<McpTool>,
    private val serverName: String,
    private val serverVersion: String,
) {

    /** @return the response line, or null when the message was a notification. */
    fun handle(line: String): String? {
        if (line.isBlank()) return null
        val parsed = JsonRpc.parse(line)
        val incoming = parsed.getOrElse { failure ->
            return JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Malformed request: ${failure.message}")
        }

        return when (incoming.method) {
            "initialize" -> JsonRpc.result(incoming.id, initialize(incoming.params))
            "notifications/initialized", "notifications/cancelled" -> null
            "ping" -> JsonRpc.result(incoming.id, JsonObject(emptyMap()))
            "tools/list" -> JsonRpc.result(incoming.id, toolList())
            "tools/call" -> callTool(incoming)
            else -> if (incoming.isNotification) {
                null
            } else {
                JsonRpc.error(incoming.id, JsonRpc.METHOD_NOT_FOUND, "Unsupported method: ${incoming.method}")
            }
        }
    }

    /**
     * The specification requires the server to answer with the client's protocol version when it
     * supports it, and with one of its own otherwise. Echoing back keeps this working with clients
     * whose exact revision string is not knowable in advance.
     */
    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        val agreed = if (requested != null && requested in SUPPORTED_PROTOCOL_VERSIONS) requested else PROTOCOL_VERSION
        return buildJsonObject {
            put("protocolVersion", agreed)
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", serverName)
                put("version", serverVersion)
            }
            put(
                "instructions",
                "Static analysis of Minecraft server artifacts. Give absolute paths. Nothing is " +
                    "loaded or executed: jars are read as bytecode, packs as files. Every finding " +
                    "carries a rule id, a severity and the source the underlying fact came from.",
            )
        }
    }

    private fun toolList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            for (tool in tools) {
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("title", tool.title)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                        putJsonObject("annotations") {
                            put("readOnlyHint", true)
                        }
                    }
                )
            }
        }
    }

    private fun callTool(incoming: JsonRpc.Incoming): String {
        val name = (incoming.params["name"] as? JsonPrimitive)?.content
            ?: return JsonRpc.error(incoming.id, JsonRpc.INVALID_PARAMS, "No tool name in params")
        val tool = tools.firstOrNull { it.name == name }
            ?: return JsonRpc.error(incoming.id, JsonRpc.INVALID_PARAMS, "Unknown tool: $name")
        val arguments = incoming.params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        val outcome = try {
            tool.call(arguments)
        } catch (e: Exception) {
            ToolResult("${e::class.simpleName}: ${e.message ?: "no message"}", isError = true)
        }

        return JsonRpc.result(
            incoming.id,
            buildJsonObject {
                putJsonArray("content") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", outcome.text)
                        }
                    )
                }
                put("isError", outcome.isError)
            },
        )
    }

    companion object {
        /** Newest revision this server speaks, i.e. the last one with an initialize handshake. */
        const val PROTOCOL_VERSION = "2025-11-25"

        val SUPPORTED_PROTOCOL_VERSIONS = setOf(
            "2025-11-25",
            "2025-06-18",
            "2025-03-26",
            "2024-11-05",
        )

        /** Helper for tool schemas: an object schema with string properties. */
        fun objectSchema(
            required: List<String>,
            properties: Map<String, Pair<String, String>>,
        ): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for ((property, spec) in properties) {
                    val (type, description) = spec
                    putJsonObject(property) {
                        put("type", type)
                        put("description", description)
                    }
                }
            }
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }
}
