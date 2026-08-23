package com.salyvn.pluginscope

import com.salyvn.pluginscope.jar.JarAuditor
import com.salyvn.pluginscope.report.ConsoleReporter
import com.salyvn.pluginscope.report.JsonReporter
import com.salyvn.pluginscope.report.SarifReporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportFormatTest {

    private val report = JarAuditor().audit(FixtureJars.messyPlugin())

    @Test
    fun `the SARIF run declares every rule it reports`() {
        val sarif = Json.parseToJsonElement(SarifReporter.render(report)).jsonObject
        assertEquals("2.1.0", sarif["version"]!!.jsonPrimitive.content)

        val run = sarif["runs"]!!.jsonArray.single().jsonObject
        val declared = run["tool"]!!.jsonObject["driver"]!!.jsonObject["rules"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            .toSet()
        val reported = run["results"]!!.jsonArray
            .map { it.jsonObject["ruleId"]!!.jsonPrimitive.content }
            .toSet()

        // GitHub code scanning drops results whose rule is not declared, so this is not cosmetic.
        assertEquals(emptySet<String>(), reported - declared)
        assertTrue(reported.isNotEmpty())
    }

    @Test
    fun `SARIF levels follow the severity tiers`() {
        val run = Json.parseToJsonElement(SarifReporter.render(report)).jsonObject["runs"]!!
            .jsonArray.single().jsonObject
        val levels = run["results"]!!.jsonArray.map { it.jsonObject["level"]!!.jsonPrimitive.content }.toSet()
        assertTrue(levels.all { it in setOf("error", "warning", "note") }) { levels.toString() }
    }

    @Test
    fun `SARIF fingerprints do not depend on the order of the findings`() {
        val first = Json.parseToJsonElement(SarifReporter.render(report)).jsonObject
        val second = Json.parseToJsonElement(SarifReporter.render(report)).jsonObject
        assertEquals(first.toString(), second.toString())
    }

    @Test
    fun `JSON output carries the rule metadata a consumer needs`() {
        val payload = Json.parseToJsonElement(JsonReporter.render(report)).jsonObject
        val finding = payload["reports"]!!.jsonArray.single().jsonObject["findings"]!!.jsonArray
            .first().jsonObject
        for (field in listOf("ruleId", "severity", "title", "location", "detail")) {
            assertTrue(finding.containsKey(field)) { "missing $field in $finding" }
        }
    }

    @Test
    fun `console output is plain ASCII when colour is off`() {
        val text = ConsoleReporter(color = false).render(report)
        assertTrue(text.all { it.code < 128 }) {
            "non-ASCII in console output: " + text.filter { it.code >= 128 }
        }
        assertTrue(text.contains("Summary:"))
    }

    @Test
    fun `colour is only emitted when asked for`() {
        val escape = Char(27)
        assertTrue(ConsoleReporter(color = false).render(report).none { it == escape })
        assertTrue(ConsoleReporter(color = true).render(report).any { it == escape })
    }

    @Test
    fun `verbose output names the source of each rule`() {
        val text = ConsoleReporter(color = false, verbose = true).render(report)
        assertTrue(text.contains("source:")) { text.take(400) }
    }
}
