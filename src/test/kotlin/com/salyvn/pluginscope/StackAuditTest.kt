package com.salyvn.pluginscope

import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.stack.StackAuditor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class StackAuditTest {

    private fun install(directory: Path, vararg jars: Path) {
        for (jar in jars) Files.copy(jar, directory.resolve(jar.fileName.toString()))
    }

    private fun audit(directory: Path, folia: Boolean = false) = StackAuditor(foliaTarget = folia).audit(
        directory,
        Files.list(directory).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "jar" }.sorted().toList()
        },
    )

    /**
     * Each fixture gets its own main class and package, because two jars sharing a main class is
     * itself a finding and would drown out whatever the test is actually about.
     */
    private fun plugin(
        name: String,
        extraLines: List<String> = emptyList(),
        generatedClasses: List<String> = emptyList(),
    ): Path {
        val main = "com.example.${name.lowercase()}.Main"
        return FixtureJars.pluginJar(
            jarName = name,
            descriptor = FixtureJars.descriptor(name, main, "26.2", extraLines),
            generatedClasses = listOf(main.replace('.', '/')) + generatedClasses,
        )
    }

    @Test
    fun `a hard dependency that is not installed blocks the plugin`(@TempDir directory: Path) {
        install(directory, plugin("Needy", extraLines = listOf("depend: [Vault]")))
        val report = audit(directory)
        val finding = report.findings.first { it.ruleId == "S001" }
        assertEquals(Severity.BLOCKER, finding.severity)
        assertTrue(finding.location.contains("Vault"))
    }

    @Test
    fun `a soft dependency that is not installed is only information`(@TempDir directory: Path) {
        install(directory, plugin("Polite", extraLines = listOf("softdepend: [PlaceholderAPI]")))
        val report = audit(directory)
        assertTrue(report.findings.none { it.ruleId == "S001" })
        assertTrue(report.findings.any { it.ruleId == "S050" && it.location.contains("PlaceholderAPI") })
    }

    @Test
    fun `two jars claiming one plugin name is reported`(@TempDir directory: Path) {
        val first = plugin("Twin")
        val second = plugin("Twin")
        Files.copy(first, directory.resolve("twin-1.0.jar"))
        Files.copy(second, directory.resolve("twin-2.0.jar"))
        val report = audit(directory)
        assertTrue(report.findings.any { it.ruleId == "S010" })
        assertTrue(report.findings.any { it.ruleId == "S011" }) { "same main class in both jars should also be flagged" }
    }

    @Test
    fun `a dependency cycle is found`(@TempDir directory: Path) {
        install(
            directory,
            plugin("Alpha", extraLines = listOf("depend: [Beta]")),
            plugin("Beta", extraLines = listOf("depend: [Alpha]")),
        )
        val report = audit(directory)
        val cycle = report.findings.first { it.ruleId == "S002" }
        assertTrue(cycle.location.contains("Alpha") && cycle.location.contains("Beta")) { cycle.location }
    }

    @Test
    fun `commands claimed by two plugins are reported once`(@TempDir directory: Path) {
        install(
            directory,
            plugin("Warps", extraLines = listOf("commands:", "  warp:", "    aliases: [w]")),
            plugin("Homes", extraLines = listOf("commands:", "  warp:", "    aliases: [wp]")),
        )
        val report = audit(directory)
        val collisions = report.findings.filter { it.ruleId == "S020" }
        assertEquals(1, collisions.size)
        assertEquals("/warp", collisions.single().location)
    }

    @Test
    fun `an alias that is another plugin's real command is reported`(@TempDir directory: Path) {
        install(
            directory,
            plugin("Economy", extraLines = listOf("commands:", "  money:", "    aliases: [bal]")),
            plugin("Bank", extraLines = listOf("commands:", "  bal:")),
        )
        val report = audit(directory)
        val alias = report.findings.first { it.ruleId == "S021" }
        assertEquals("/bal", alias.location)
        assertTrue(alias.detail.contains("Economy") && alias.detail.contains("Bank")) { alias.detail }
        assertTrue(report.findings.none { it.ruleId == "S020" }) { "no plugin pair claims the same command name" }
    }

    @Test
    fun `the same library in two different builds is reported as skew`(@TempDir directory: Path) {
        install(
            directory,
            plugin("First", generatedClasses = (1..5).map { "org/apache/commons/lang3/StringUtils$it" }),
            plugin("Second", generatedClasses = (1..9).map { "org/apache/commons/lang3/StringUtils$it" }),
        )
        val report = audit(directory)
        val skew = report.findings.first { it.ruleId == "S030" }
        assertEquals("org.apache.commons", skew.location)
        assertTrue(skew.evidence.size == 2) { skew.evidence.toString() }
    }

    @Test
    fun `an identical library in two plugins is only counted as waste`(@TempDir directory: Path) {
        val classes = (1..6).map { "org/apache/commons/lang3/StringUtils$it" }
        install(
            directory,
            plugin("First", generatedClasses = classes),
            plugin("Second", generatedClasses = classes),
        )
        val report = audit(directory)
        assertTrue(report.findings.none { it.ruleId == "S030" })
        assertTrue(report.findings.any { it.ruleId == "S031" })
    }

    @Test
    fun `folia targets report plugins that do not declare support`(@TempDir directory: Path) {
        install(
            directory,
            plugin("Unaware"),
            plugin("Aware", extraLines = listOf("folia-supported: true")),
        )
        val report = audit(directory, folia = true)
        val flagged = report.findings.filter { it.ruleId == "S060" }.map { it.location }
        assertEquals(listOf("Unaware"), flagged)
    }

    @Test
    fun `a healthy install set raises nothing above information`(@TempDir directory: Path) {
        install(
            directory,
            plugin("Core"),
            plugin("Extra", extraLines = listOf("depend: [Core]")),
        )
        val report = audit(directory)
        val serious = report.findings.filter { it.severity.rank >= Severity.WARNING.rank }
        assertEquals(emptyList<String>(), serious.map { "${it.ruleId} ${it.location}" })
    }

    @Test
    fun `the plugin table is part of the facts`(@TempDir directory: Path) {
        install(directory, plugin("Core"))
        val facts = audit(directory).facts
        assertEquals("1", facts["plugins"])
        assertTrue(facts.keys.any { it.trim() == "Core" }) { facts.keys.toString() }
    }
}
