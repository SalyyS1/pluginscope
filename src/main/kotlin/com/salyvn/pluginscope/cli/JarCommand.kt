package com.salyvn.pluginscope.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.pluginscope.data.McVersions
import com.salyvn.pluginscope.jar.JarAuditOptions
import com.salyvn.pluginscope.jar.JarAuditor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** `pluginscope jar` - audit one plugin jar, or every jar in a directory, one at a time. */
class JarCommand : ReportCommand("jar") {

    override fun help(context: Context) =
        "Audit a plugin jar: descriptor, shaded dependencies, packaging hygiene, Java target."

    private val targets by argument(name = "TARGET", help = "Plugin jar(s) or a directory of jars")
        .path(mustExist = true, mustBeReadable = true)
        .multiple(required = true)

    private val target by option(
        "--target",
        help = "Minecraft version the plugin must run on, e.g. 1.21.4 or 26.2. Enables the Java check.",
    )

    private val targetJava by option(
        "--target-java",
        help = "Java release the server runs, if you would rather state it directly (e.g. 21)",
    ).int()

    private val noReachability by option(
        "--no-reachability",
        help = "Skip the second pass that estimates how much bundled code is unused",
    ).flag()

    private val disable by option("--disable", help = "Disable a rule id (repeatable)").multiple()

    override fun run() {
        val jars = expand(targets)
        if (jars.isEmpty()) {
            echo("No .jar files found in the given target(s).", err = true)
            throw ProgramResult(2)
        }

        val resolvedJava = targetJava ?: target?.let { mc ->
            McVersions.minimumJavaFor(mc) ?: run {
                echo("Cannot map '$mc' to a Java release; pass --target-java instead.", err = true)
                throw ProgramResult(2)
            }
        }

        val auditor = JarAuditor(
            JarAuditOptions(
                targetJava = resolvedJava,
                reachability = !noReachability,
                disabledRules = disable.map { it.uppercase() }.toSet(),
            )
        )
        emit(jars.map { auditor.audit(it) })
    }

    internal companion object {
        fun expand(targets: List<Path>): List<Path> = targets.flatMap { target ->
            if (target.isDirectory()) {
                Files.list(target).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension.equals("jar", true) }.sorted().toList()
                }
            } else {
                listOf(target)
            }
        }.distinct()
    }
}
