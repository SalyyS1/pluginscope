package com.salyvn.pluginscope

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.salyvn.pluginscope.cli.CompatCommand
import com.salyvn.pluginscope.cli.JarCommand
import com.salyvn.pluginscope.cli.McpCommand
import com.salyvn.pluginscope.cli.PackCommand
import com.salyvn.pluginscope.cli.RulesCommand
import com.salyvn.pluginscope.cli.StackCommand
import com.salyvn.pluginscope.core.BuildInfo

/**
 * Entry point. Every subcommand reads build output only: jars, packs, directories. No plugin code
 * is loaded or executed at any point, which is what makes it safe to point at a jar somebody else
 * compiled.
 */
class PluginScopeCommand : CliktCommand(name = "pluginscope") {

    override fun help(context: Context) =
        "Static audits for Minecraft server plugins: packaging, API compatibility and install sets."

    override fun run() = Unit
}

fun main(args: Array<String>) = PluginScopeCommand()
    .versionOption(BuildInfo.version, names = setOf("--version"))
    .subcommands(
        JarCommand(),
        CompatCommand(),
        StackCommand(),
        PackCommand(),
        McpCommand(),
        RulesCommand(),
    )
    .main(args)
