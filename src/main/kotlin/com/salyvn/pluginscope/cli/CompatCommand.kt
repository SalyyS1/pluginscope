package com.salyvn.pluginscope.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.pluginscope.compat.ApiSurface
import com.salyvn.pluginscope.compat.CompatChecker
import com.salyvn.pluginscope.compat.CompatOptions
import com.salyvn.pluginscope.compat.PaperApiResolver
import com.salyvn.pluginscope.data.McVersions
import java.io.IOException
import java.nio.file.Path

/** `pluginscope compat` - resolve a jar's API references against the API of a target server. */
class CompatCommand : ReportCommand("compat") {

    override fun help(context: Context) =
        "Check a plugin jar against a server API: which classes and members it uses that are not there."

    private val jar by argument(name = "JAR", help = "The plugin jar to check")
        .path(mustExist = true, mustBeReadable = true)

    private val apiJars by option(
        "--api",
        help = "API jar to check against (repeatable), e.g. a paper-api jar you already have",
    ).path(mustExist = true, mustBeReadable = true).multiple()

    private val mc by option(
        "--mc",
        help = "Download and use paper-api for this version, e.g. 1.21.4, 26.2 or latest",
    )

    private val cache by option("--cache", help = "Directory for downloaded API jars").path()

    private val includePrefix by option(
        "--include-prefix",
        help = "Additional package to check, dotted (repeatable), e.g. net.kyori.adventure",
    ).multiple()

    private val noDeprecated by option(
        "--no-deprecated",
        help = "Do not report references to members that are deprecated in the target API",
    ).flag()

    override fun run() {
        if (apiJars.isEmpty() && mc == null) {
            echo("Pass --api <jar> or --mc <version> so there is something to check against.", err = true)
            throw ProgramResult(2)
        }

        val jars = ArrayList<Path>(apiJars)
        var label = apiJars.joinToString(", ") { it.fileName.toString() }

        mc?.let { requested ->
            val resolved = try {
                PaperApiResolver.resolve(
                    request = requested,
                    cacheDir = cache ?: PaperApiResolver.defaultCacheDir(),
                    log = { echo(it, err = true) },
                )
            } catch (e: IOException) {
                echo("Could not fetch paper-api for '$requested': ${e.message}", err = true)
                throw ProgramResult(2)
            }
            jars.add(resolved.jar)
            val apiVersion = McVersions.currentApiVersionOf(resolved.jar)
            label = listOfNotNull(
                "paper-api ${resolved.version}",
                apiVersion?.let { "api-version $it" },
                if (resolved.fromCache) "cached" else null,
            ).joinToString(", ")
        }

        val surface = ApiSurface.fromJars(jars, label.ifEmpty { "the given API jars" })
        if (surface.size == 0) {
            echo("The API jar(s) contain no classes.", err = true)
            throw ProgramResult(2)
        }

        val options = CompatOptions(
            includePrefixes = CompatOptions.DEFAULT_PREFIXES + includePrefix.map { normalise(it) },
            reportDeprecated = !noDeprecated,
        )
        emit(listOf(CompatChecker(options).check(jar, surface)))
    }

    private fun normalise(prefix: String): String {
        val internal = prefix.replace('.', '/')
        return if (internal.endsWith('/')) internal else "$internal/"
    }
}

/** `pluginscope stack` - audit a whole plugins directory as one install set. */
class StackCommand : ReportCommand("stack") {

    override fun help(context: Context) =
        "Audit a plugins directory: missing dependencies, duplicate plugins, command clashes, shared libraries."

    private val directory by argument(name = "DIRECTORY", help = "A server's plugins directory")
        .path(mustExist = true, mustBeReadable = true)

    private val folia by option(
        "--folia",
        help = "Treat the target server as Folia, so plugins without folia-supported are reported",
    ).flag()

    override fun run() {
        val jars = JarCommand.expand(listOf(directory))
        if (jars.isEmpty()) {
            echo("No .jar files in $directory.", err = true)
            throw ProgramResult(2)
        }
        emit(listOf(com.salyvn.pluginscope.stack.StackAuditor(foliaTarget = folia).audit(directory, jars)))
    }
}

/** `pluginscope rules` - print the catalogue, including where each fact came from. */
class RulesCommand : com.github.ajalt.clikt.core.CliktCommand(name = "rules") {

    override fun help(context: Context) = "Print every rule with its rationale and provenance."

    private val markdown by option("--markdown", help = "Render as Markdown suitable for docs/rules.md").flag()

    private val output by option("-o", "--output", help = "Write the rule catalogue to a file").path()

    override fun run() {
        val catalogues = listOf(
            "jar" to com.salyvn.pluginscope.jar.JarRules.all,
            "compat" to com.salyvn.pluginscope.compat.CompatRules.all,
            "stack" to com.salyvn.pluginscope.stack.StackRules.all,
            "pack" to com.salyvn.pluginscope.pack.PackRules.all,
        )
        val text = if (markdown) {
            buildString {
                appendLine("# Rules")
                appendLine()
                appendLine("Every check pluginscope can report, as printed by `pluginscope rules` in version ${com.salyvn.pluginscope.core.BuildInfo.version}.")
                appendLine("Each rule carries the source of the fact behind it, so a finding can be argued with.")
                appendLine()
                appendLine("Severities: `blocker` means the artifact does not load, `critical` means it loads and then")
                appendLine("fails at runtime, `warning` means it works but depends on luck, `info` means no defect is")
                appendLine("being claimed.")
                for ((command, rules) in catalogues) {
                    appendLine()
                    appendLine("## pluginscope $command")
                    appendLine()
                    appendLine("${rules.size} rules.")
                    for (rule in rules.sortedBy { it.id }) {
                        appendLine()
                        appendLine("### ${rule.id} - ${rule.title}")
                        appendLine()
                        appendLine("`${rule.severity.label}`")
                        appendLine()
                        appendLine("**Why it matters.** ${rule.rationale}")
                        rule.fix?.let {
                            appendLine()
                            appendLine("**Fix.** $it")
                        }
                        appendLine()
                        appendLine("**Source.** ${rule.provenance}")
                    }
                }
            }
        } else {
            buildString {
                for ((command, rules) in catalogues) {
                    appendLine("pluginscope $command  (${rules.size} rules)")
                    for (rule in rules.sortedBy { it.id }) {
                        appendLine()
                        appendLine("  ${rule.id}  [${rule.severity.label}]  ${rule.title}")
                        appendLine("    why: ${rule.rationale}")
                        rule.fix?.let { appendLine("    fix: $it") }
                        appendLine("    src: ${rule.provenance}")
                    }
                    appendLine()
                }
            }
        }
        output?.let { target ->
            target.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(target, text)
            echo("Wrote rule catalogue to $target")
        } ?: echo(text)
    }
}
