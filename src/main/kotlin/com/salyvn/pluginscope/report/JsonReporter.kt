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
        val rules = report.findings
            .distinctBy { it.ruleId }
            .sortedBy { it.ruleId }
            .map { finding ->
                buildJsonObject {
                    put("id", finding.ruleId)
                    put("name", finding.ruleId)
                    putJsonObject("shortDescription") { put("text", finding.title) }
                    putJsonObject("fullDescription") {
                        put(
                            "text",
                            listOfNotNull(finding.title, finding.provenance?.let { "Source: $it" })
                                .joinToString(" "),
                        )
                    }
                    putJsonObject("defaultConfiguration") { put("level", level(finding.severity)) }
                    finding.fix?.let { putJsonObject("help") { put("text", it) } }
                }
            }

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
                        put("level", level(finding.severity))
                        putJsonObject("message") {
                            put("text", (listOf(finding.detail) + finding.evidence).joinToString(" | "))
                        }
                        putJsonArray("locations") {
                            add(
                                buildJsonObject {
                                    putJsonObject("physicalLocation") {
                                        putJsonObject("artifactLocation") {
                                            put("uri", report.subject.replace('\\', '/'))
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
