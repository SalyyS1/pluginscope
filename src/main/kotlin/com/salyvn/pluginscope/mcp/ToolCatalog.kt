package com.salyvn.pluginscope.mcp

import com.salyvn.pluginscope.compat.ApiSurface
import com.salyvn.pluginscope.compat.CompatChecker
import com.salyvn.pluginscope.compat.PaperApiResolver
import com.salyvn.pluginscope.core.BuildInfo
import com.salyvn.pluginscope.data.McVersions
import com.salyvn.pluginscope.jar.JarAuditOptions
import com.salyvn.pluginscope.jar.JarAuditor
import com.salyvn.pluginscope.jar.JarRules
import com.salyvn.pluginscope.pack.PackAuditOptions
import com.salyvn.pluginscope.pack.PackRules
import com.salyvn.pluginscope.pack.PackValidator
import com.salyvn.pluginscope.report.JsonReporter
import com.salyvn.pluginscope.stack.StackAuditor
import com.salyvn.pluginscope.stack.StackRules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The analyses exposed to an agent. Each one is the same code path the CLI uses, so a finding an
 * agent sees is a finding a human would get from the terminal, byte for byte.
 */
object ToolCatalog {

    fun tools(): List<McpTool> = listOf(ScanJarTool, CompatTool, StackTool, PackTool, RulesTool)

    private object ScanJarTool : McpTool {
        override val name = "scan_jar"
        override val title = "Audit a plugin jar"
        override val description =
            "Static audit of one Minecraft plugin jar: descriptor correctness, shaded dependencies " +
                "that collide with what the server provides, packaging hygiene, and the Java " +
                "release the class files need. Returns JSON findings with rule ids."
        override val inputSchema = McpServer.objectSchema(
            required = listOf("path"),
            properties = mapOf(
                "path" to ("string" to "Absolute path to the plugin jar, or to a directory of jars"),
                "target" to ("string" to "Minecraft version the plugin must run on, e.g. 1.21.4 or 26.2"),
            ),
        )

        override fun call(arguments: JsonObject): ToolResult {
            val path = arguments.path("path") ?: return missing("path")
            val target = arguments.string("target")
            val targetJava = target?.let { McVersions.minimumJavaFor(it) }
            if (target != null && targetJava == null) {
                return ToolResult("Cannot map target '$target' to a Java release.", isError = true)
            }
            val jars = if (path.isDirectory()) {
                Files.list(path).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension.equals("jar", true) }.sorted().toList()
                }
            } else {
                listOf(path)
            }
            if (jars.isEmpty()) return ToolResult("No jars found at $path.", isError = true)
            val auditor = JarAuditor(JarAuditOptions(targetJava = targetJava))
            return ToolResult(JsonReporter.render(jars.map { auditor.audit(it) }))
        }
    }

    private object CompatTool : McpTool {
        override val name = "check_api_compat"
        override val title = "Check a jar against a server API"
        override val description =
            "Resolves every Bukkit/Paper reference a plugin jar makes against a target server API " +
                "and reports the classes and members that are not there, plus uses of server " +
                "internals. Either point it at API jars, or give a Minecraft version to fetch " +
                "paper-api for (requires network the first time; downloads are cached)."
        override val inputSchema = McpServer.objectSchema(
            required = listOf("jar"),
            properties = mapOf(
                "jar" to ("string" to "Absolute path to the plugin jar"),
                "mc" to ("string" to "Minecraft version to fetch paper-api for, e.g. 1.21.4, 26.2, latest"),
                "api_jar" to ("string" to "Absolute path to an API jar to check against instead of downloading"),
            ),
        )

        override fun call(arguments: JsonObject): ToolResult {
            val jar = arguments.path("jar") ?: return missing("jar")
            val apiJar = arguments.path("api_jar")
            val mc = arguments.string("mc")
            if (apiJar == null && mc == null) {
                return ToolResult("Give either mc or api_jar so there is something to check against.", isError = true)
            }

            val jars = mutableListOf<Path>()
            var label = ""
            if (apiJar != null) {
                jars.add(apiJar)
                label = apiJar.fileName.toString()
            }
            if (mc != null) {
                val resolved = PaperApiResolver.resolve(mc)
                jars.add(resolved.jar)
                label = "paper-api ${resolved.version}"
            }
            val surface = ApiSurface.fromJars(jars, label)
            return ToolResult(JsonReporter.render(CompatChecker().check(jar, surface)))
        }
    }

    private object StackTool : McpTool {
        override val name = "audit_plugins_dir"
        override val title = "Audit a plugins directory"
        override val description =
            "Audits a whole server plugins folder as one install set: missing hard dependencies, " +
                "dependency cycles, duplicate plugins, command and alias clashes, the same library " +
                "shipped by several plugins in different builds, and the Java release the set needs."
        override val inputSchema = McpServer.objectSchema(
            required = listOf("directory"),
            properties = mapOf(
                "directory" to ("string" to "Absolute path to a server's plugins directory"),
                "folia" to ("boolean" to "Treat the target server as Folia"),
            ),
        )

        override fun call(arguments: JsonObject): ToolResult {
            val directory = arguments.path("directory") ?: return missing("directory")
            if (!directory.isDirectory()) return ToolResult("$directory is not a directory.", isError = true)
            val jars = Files.list(directory).use { stream ->
                stream.filter { it.isRegularFile() && it.extension.equals("jar", true) }.sorted().toList()
            }
            if (jars.isEmpty()) return ToolResult("No jars in $directory.", isError = true)
            val folia = arguments.boolean("folia") ?: false
            return ToolResult(JsonReporter.render(StackAuditor(foliaTarget = folia).audit(directory, jars)))
        }
    }

    private object PackTool : McpTool {
        override val name = "validate_pack"
        override val title = "Validate a resource pack"
        override val description =
            "Validates a resource pack directory or zip against a target Minecraft version: the " +
                "pack format it declares, references that point at files the pack does not " +
                "contain, item definitions that collide, and paths the game will not accept. " +
                "Give pack_format instead of mc to run without network access."
        override val inputSchema = McpServer.objectSchema(
            required = listOf("path"),
            properties = mapOf(
                "path" to ("string" to "Absolute path to a pack directory or .zip"),
                "mc" to ("string" to "Minecraft version to check the pack format against, e.g. 26.2"),
                "pack_format" to ("integer" to "Target pack format number, if you already know it"),
            ),
        )

        override fun call(arguments: JsonObject): ToolResult {
            val path = arguments.path("path") ?: return missing("path")
            val packFormat = (arguments["pack_format"] as? JsonPrimitive)?.content?.toIntOrNull()
            val report = PackValidator(
                PackAuditOptions(
                    mcVersion = arguments.string("mc"),
                    packFormat = packFormat,
                )
            ).validate(path)
            return ToolResult(JsonReporter.render(report))
        }
    }

    private object RulesTool : McpTool {
        override val name = "list_rules"
        override val title = "List the rules"
        override val description =
            "Every check pluginscope can report, with its severity, the reason it matters and the " +
                "source the underlying fact was taken from. Useful before deciding whether a " +
                "finding is worth acting on."
        override val inputSchema = McpServer.objectSchema(required = emptyList(), properties = emptyMap())

        override fun call(arguments: JsonObject): ToolResult {
            val text = buildString {
                appendLine("pluginscope ${BuildInfo.version}")
                for ((command, rules) in listOf(
                    "scan_jar" to JarRules.all,
                    "audit_plugins_dir" to StackRules.all,
                    "validate_pack" to PackRules.all,
                )) {
                    appendLine()
                    appendLine("$command:")
                    for (rule in rules.sortedBy { it.id }) {
                        appendLine("  ${rule.id} [${rule.severity.label}] ${rule.title}")
                        appendLine("    why: ${rule.rationale}")
                        appendLine("    src: ${rule.provenance}")
                    }
                }
            }
            return ToolResult(text)
        }
    }

    private fun missing(argument: String) = ToolResult("Required argument '$argument' is missing.", isError = true)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.path(key: String): Path? = string(key)?.let { Path.of(it) }
}
