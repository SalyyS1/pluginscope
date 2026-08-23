package com.salyvn.pluginscope.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.salyvn.pluginscope.core.BuildInfo
import com.salyvn.pluginscope.mcp.McpServer
import com.salyvn.pluginscope.mcp.ToolCatalog

/**
 * `pluginscope mcp` - serve the same analyses to an AI coding agent over the Model Context Protocol.
 *
 * The transport is stdin and stdout, one JSON message per line. Nothing but protocol messages may
 * go to stdout, so progress and errors are written to stderr instead.
 */
class McpCommand : CliktCommand(name = "mcp") {

    override fun help(context: Context) =
        "Run as a Model Context Protocol server on stdio, exposing the audits as agent tools."

    override fun run() {
        val server = McpServer(
            tools = ToolCatalog.tools(),
            serverName = BuildInfo.NAME,
            serverVersion = BuildInfo.version,
        )
        val input = System.`in`.bufferedReader()
        val output = System.out.bufferedWriter()

        System.err.println("${BuildInfo.NAME} ${BuildInfo.version} MCP server ready on stdio")

        while (true) {
            val line = input.readLine() ?: break
            val response = server.handle(line) ?: continue
            output.write(response)
            output.write("\n")
            output.flush()
        }
    }
}
