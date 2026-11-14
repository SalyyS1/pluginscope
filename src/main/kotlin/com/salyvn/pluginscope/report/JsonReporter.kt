package com.salyvn.pluginscope.report

import com.salyvn.pluginscope.core.BuildInfo
import com.salyvn.pluginscope.core.ScanReport
import com.salyvn.pluginscope.core.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Machine-readable output with stable field names: other tools are expected to parse this. */
object JsonReporter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun render(report: ScanReport): String = render(listOf(report))

    fun render(reports: List<ScanReport>): String {
        val element = buildJsonObject {
            put("tool", BuildInfo.NAME)
            put("toolVersion", BuildInfo.version)
            putJsonArray("reports") {
                reports.forEach { add(json.encodeToJsonElement(it)) }
            }
        }
        return json.encodeToString(element)
    }
}

/**
 * SARIF 2.1.0, the format GitHub code scanning ingests, so findings land on the pull request that
 * introduced them.
 *
 * Locations are jar entries, package names and file paths rather than source lines, which SARIF
 * permits: the artifact being audited is the build output, not the source tree. Rules are declared
 * in `tool.driver.rules` because GitHub silently drops results whose ruleId is not declared.
 */
object SarifReporter {
    private val json = Json { prettyPrint = true }

    fun render(report: ScanReport): String = render(listOf(report))

    fun render(reports: List<ScanReport>): String {
        val sarif = buildJsonObject {
            put("version", "2.1.0")
            put("\$schema", "https://json.schemastore.org/sarif-2.1.0.json")
            putJsonArray("runs") {
                reports.forEach { add(run(it)) }
            }
        }
        return json.encodeToString(sarif)
    }

    private fun run(report: ScanReport) = buildJsonObject {
        val uniqueFindings = report.findings
            .distinctBy { it.ruleId }
            .sortedBy { it.ruleId }
        val ruleIndexById = uniqueFindings.mapIndexed { idx, f -> f.ruleId to idx }.toMap()
        val rules = uniqueFindings.map { finding ->
            buildJsonObject {
                put("id", finding.ruleId)
                put("name", pascalCase(finding.title, finding.ruleId))
                putJsonObject("shortDescription") { put("text", finding.title) }
                putJsonObject("fullDescription") {
                    put(
                        "text",
                        listOfNotNull(finding.title, finding.provenance?.let { "Source: $it" })
                            .joinToString(" "),
                    )
                }
                putJsonObject("defaultConfiguration") { put("level", level(finding.severity)) }
                finding.fix?.let { fixText ->
                    putJsonObject("help") {
                        put("text", fixText)
                        put(
                            "markdown",
                            buildString {
                                append("**").append(finding.ruleId).append(": ").append(finding.title).append("**\n\n")
                                append("Remediation: ").append(fixText)
                                finding.provenance?.let { append("\n\nSource: ").append(it) }
                            },
                        )
                    }
                }
                putJsonObject("properties") {
                    put("precision", "high")
                    putJsonArray("tags") {
                        add("minecraft")
                        add("paper")
                        add("classloader")
                        add("shading")
                    }
                }
            }
        }

        val normalizedSubjectUri = normalizeUri(report.subject)

        putJsonObject("tool") {
            putJsonObject("driver") {
                put("name", "${BuildInfo.NAME} (${report.tool.substringAfterLast(' ')})")
                put("version", BuildInfo.version)
                put("informationUri", BuildInfo.HOMEPAGE)
                put("rules", JsonArray(rules))
            }
        }
        putJsonArray("results") {
            report.findings.forEach { finding ->
                add(
                    buildJsonObject {
                        put("ruleId", finding.ruleId)
                        ruleIndexById[finding.ruleId]?.let { put("ruleIndex", it) }
                        put("level", level(finding.severity))
                        putJsonObject("message") {
                            put(
                                "text",
                                (listOf("[${finding.location}] ${finding.detail}") + finding.evidence).joinToString(" | "),
                            )
                        }
                        putJsonArray("locations") {
                            add(
                                buildJsonObject {
                                    putJsonObject("physicalLocation") {
                                        putJsonObject("artifactLocation") {
                                            put("uri", normalizedSubjectUri)
                                            put("uriBaseId", "%SRCROOT%")
                                        }
                                        putJsonObject("region") {
                                            put("startLine", extractLine(finding.location))
                                        }
                                    }
                                    putJsonObject("message") { put("text", finding.location) }
                                }
                            )
                        }
                        putJsonObject("partialFingerprints") {
                            put("pluginscope/v1", fingerprint(finding.ruleId, finding.location))
                        }
                    }
                )
            }
        }
    }

    private fun normalizeUri(subject: String): String {
        val cwd = runCatching { java.nio.file.Path.of("").toAbsolutePath().normalize() }.getOrNull()
        val path = runCatching { java.nio.file.Path.of(subject).toAbsolutePath().normalize() }.getOrNull()
        if (cwd != null && path != null && path.startsWith(cwd) && path != cwd) {
            return cwd.relativize(path).toString().replace('\\', '/')
        }
        val forward = subject.replace('\\', '/')
            .replace(Regex("^[A-Za-z]:/"), "")
            .trimStart('/')
        return forward.ifEmpty { "plugins" }
    }

    private fun extractLine(location: String): Int {
        val tail = location.substringAfterLast(':', "")
        val parsed = tail.toIntOrNull()
        return if (parsed != null && parsed >= 1) parsed else 1
    }

    private fun pascalCase(title: String, fallback: String): String {
        val words = title.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return fallback
        return words.joinToString("") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }

    /** Location-based rather than line-based, so reformatting a file does not invent new alerts. */
    private fun fingerprint(ruleId: String, location: String): String {
        var hash = 0x811C9DC5u
        for (char in "$ruleId|$location") {
            hash = hash xor char.code.toUInt()
            hash *= 0x01000193u
        }
        return hash.toString(16).padStart(8, '0')
    }

    private fun level(severity: Severity) = when (severity) {
        Severity.BLOCKER, Severity.CRITICAL -> "error"
        Severity.WARNING -> "warning"
        Severity.INFO -> "note"
    }
}
