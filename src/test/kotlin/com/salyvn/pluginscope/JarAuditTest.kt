package com.salyvn.pluginscope

import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.jar.JarAuditOptions
import com.salyvn.pluginscope.jar.JarAuditor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JarAuditTest {

    private fun audit(path: java.nio.file.Path, options: JarAuditOptions = JarAuditOptions()) =
        JarAuditor(options).audit(path)

    /**
     * The guard that matters most. A correct jar has to come back silent, because a tool that
     * complains about clean builds gets switched off and never run again.
     */
    @Test
    fun `a clean plugin produces no findings`() {
        val report = audit(FixtureJars.cleanPlugin())
        assertEquals(emptyList<String>(), report.findings.map { "${it.ruleId} ${it.detail}" })
    }

    @Test
    fun `a messy jar is reported per problem`() {
        val report = audit(FixtureJars.messyPlugin())
        val ids = report.findings.map { it.ruleId }.toSet()

        assertTrue("J020" in ids) { "server namespace bundling not reported: $ids" }
        assertTrue("J021" in ids) { "provided library bundling not reported: $ids" }
        assertTrue("J023" in ids) { "unrelocated Kotlin runtime not reported: $ids" }
        assertTrue("J022" in ids) { "unrelocated third-party library not reported: $ids" }
        assertTrue("J030" in ids) { "packaging hygiene not reported: $ids" }
        assertTrue("J031" in ids) { "leftover signature files not reported: $ids" }
        assertTrue("J006" in ids) { "misspelled descriptor key not reported: $ids" }
        assertTrue("J007" in ids) { "missing api-version not reported: $ids" }
    }

    @Test
    fun `the misspelled dependency key is named in the evidence`() {
        val report = audit(FixtureJars.messyPlugin())
        val finding = report.findings.first { it.ruleId == "J006" }
        assertTrue(finding.detail.contains("depends")) { finding.detail }
        assertTrue(finding.evidence.any { it.contains("depend") }) { finding.evidence.toString() }
    }

    @Test
    fun `a descriptor pointing at an absent main class blocks`() {
        val report = audit(FixtureJars.brokenDescriptorPlugin())
        val ids = report.findings.map { it.ruleId }.toSet()
        assertTrue("J004" in ids) { "absent main class not reported: $ids" }
        assertTrue("J005" in ids) { "illegal plugin name not reported: $ids" }
        assertTrue("J008" in ids) { "unparseable api-version not reported: $ids" }
        assertEquals(Severity.BLOCKER, report.findings.first { it.ruleId == "J004" }.severity)
    }

    @Test
    fun `a space in the plugin name is accepted because the server accepts it`() {
        val jar = FixtureJars.pluginJar(
            jarName = "spaced-name",
            descriptor = FixtureJars.descriptor("My Plugin", "fixtures.clean.CleanPlugin", "26.2"),
            packagePaths = listOf("fixtures/clean/"),
        )
        assertTrue(audit(jar).findings.none { it.ruleId == "J005" })
    }

    @Test
    fun `classes newer than the target Java release are reported`() {
        val report = audit(FixtureJars.futureJavaPlugin(), JarAuditOptions(targetJava = 21))
        val finding = report.findings.firstOrNull { it.ruleId == "J010" }
        assertTrue(finding != null) { "no J010 in ${report.findings.map { it.ruleId }}" }
        assertTrue(finding!!.detail.contains("Java 21")) { finding.detail }
    }

    @Test
    fun `without a target no Java check is made`() {
        val report = audit(FixtureJars.futureJavaPlugin())
        assertTrue(report.findings.none { it.ruleId == "J010" })
    }

    @Test
    fun `a main class inside the server namespace blocks`() {
        val jar = FixtureJars.pluginJar(
            jarName = "bukkit-main",
            descriptor = FixtureJars.descriptor("Sneaky", "org.bukkit.plugin.Main", "26.2"),
            packagePaths = listOf("fixtures/clean/"),
        )
        assertTrue(audit(jar).findings.any { it.ruleId == "J012" })
    }

    @Test
    fun `an unquoted api-version that YAML rewrites is reported with both values`() {
        val jar = FixtureJars.pluginJar(
            jarName = "unquoted-api-version",
            descriptor = "name: Trailing\nversion: 1.0.0\nmain: fixtures.clean.CleanPlugin\napi-version: 1.20\n",
            packagePaths = listOf("fixtures/clean/"),
        )
        val finding = audit(jar).findings.first { it.ruleId == "J013" }
        assertTrue(finding.detail.contains("1.20") && finding.detail.contains("reads 1.2")) { finding.detail }
        assertTrue(finding.evidence.any { it.contains("'1.20'") }) { finding.evidence.toString() }
    }

    @Test
    fun `an unquoted api-version that survives YAML is left alone`() {
        // 1.21 loads as a float that prints back as "1.21", so the server reads what was written.
        // Nearly every real plugin writes it unquoted, so reporting this would be noise.
        val jar = FixtureJars.pluginJar(
            jarName = "unquoted-but-intact",
            descriptor = "name: Intact\nversion: 1.0.0\nmain: fixtures.clean.CleanPlugin\napi-version: 1.21\n",
            packagePaths = listOf("fixtures/clean/"),
        )
        assertEquals(emptyList<String>(), audit(jar).findings.map { "${it.ruleId} ${it.detail}" })
    }

    @Test
    fun `a jar without a descriptor is treated as a library`() {
        val jar = FixtureJars.pluginJar(jarName = "library", descriptor = null, packagePaths = listOf("fixtures/clean/"))
        val report = audit(jar)
        assertEquals(listOf("J001"), report.findings.map { it.ruleId })
        assertEquals(Severity.INFO, report.findings.single().severity)
    }

    @Test
    fun `both descriptors present is reported because the legacy one stops applying`() {
        val jar = FixtureJars.pluginJar(
            jarName = "two-descriptors",
            descriptor = FixtureJars.descriptor("Twin", "fixtures.clean.CleanPlugin", "26.2"),
            packagePaths = listOf("fixtures/clean/"),
            extraEntries = mapOf(
                "paper-plugin.yml" to FixtureJars.descriptor("Twin", "fixtures.clean.CleanPlugin", "26.2").toByteArray(),
            ),
        )
        assertTrue(audit(jar).findings.any { it.ruleId == "J009" })
    }

    @Test
    fun `a disabled rule disappears from the report`() {
        val withRule = audit(FixtureJars.messyPlugin())
        val without = audit(FixtureJars.messyPlugin(), JarAuditOptions(disabledRules = setOf("J021")))
        assertTrue(withRule.findings.any { it.ruleId == "J021" })
        assertTrue(without.findings.none { it.ruleId == "J021" })
    }

    @Test
    fun `facts describe the jar even when nothing is wrong`() {
        val facts = audit(FixtureJars.cleanPlugin()).facts
        assertEquals("CleanPlugin", facts["plugin"])
        assertEquals("26.2", facts["api-version"])
        assertEquals("plugin.yml", facts["descriptor"])
        assertTrue(facts.getValue("needs java").toInt() >= 8) { facts.toString() }
    }

    @Test
    fun `unrelocated bStats Metrics is reported as J024 even for a single class`() {
        val jar = FixtureJars.pluginJar(
            jarName = "unrelocated-bstats",
            descriptor = FixtureJars.descriptor("StatsPlugin", "fixtures.clean.CleanPlugin", "26.2"),
            packagePaths = listOf("fixtures/clean/"),
            generatedClasses = listOf("org/bstats/bukkit/Metrics"),
        )
        val report = audit(jar)
        val hit = report.findings.first { it.ruleId == "J024" }
        assertEquals(Severity.CRITICAL, hit.severity)
        assertEquals("org.bstats", hit.location)
        assertTrue(report.findings.none { it.ruleId == "J022" && it.location.startsWith("org.bstats") })
    }

    @Test
    fun `dual descriptors report dependencies omitted from paper-plugin yml as J042`() {
        val legacyYml = FixtureJars.descriptor(
            "DualPlugin",
            "fixtures.clean.CleanPlugin",
            "26.2",
            extraLines = listOf("depend: [Vault]", "softdepend: [PlaceholderAPI]"),
        )
        val paperYml = FixtureJars.descriptor("DualPlugin", "fixtures.clean.CleanPlugin", "26.2")
        val jar = FixtureJars.pluginJar(
            jarName = "dual-drift",
            descriptor = legacyYml,
            packagePaths = listOf("fixtures/clean/"),
            extraEntries = mapOf("paper-plugin.yml" to paperYml.toByteArray()),
        )
        val report = audit(jar)
        val drift = report.findings.first { it.ruleId == "J042" }
        assertEquals(Severity.CRITICAL, drift.severity)
        assertTrue(drift.detail.contains("Vault") && drift.detail.contains("PlaceholderAPI")) { drift.detail }
    }

    @Test
    fun `paper-plugin yml with missing bootstrapper class blocks as J042`() {
        val paperYml = FixtureJars.descriptor(
            "BrokenBoot",
            "fixtures.clean.CleanPlugin",
            "26.2",
            extraLines = listOf("bootstrapper: com.example.absent.PluginBoot"),
        )
        val jar = FixtureJars.pluginJar(
            jarName = "missing-bootstrapper",
            descriptor = paperYml,
            descriptorEntry = "paper-plugin.yml",
            packagePaths = listOf("fixtures/clean/"),
        )
        val report = audit(jar)
        val hit = report.findings.first { it.ruleId == "J042" }
        assertEquals(Severity.BLOCKER, hit.severity)
        assertTrue(hit.detail.contains("PluginBoot")) { hit.detail }
    }

    @Test
    fun `paper-plugin yml declaring a dependency only under bootstrap warns as J042`() {
        val paperYml = FixtureJars.descriptor(
            "BootstrapOnly",
            "fixtures.clean.CleanPlugin",
            "26.2",
            extraLines = listOf(
                "dependencies:",
                "  bootstrap:",
                "    Vault:",
                "      required: true",
            ),
        )
        val jar = FixtureJars.pluginJar(
            jarName = "bootstrap-only-dep",
            descriptor = paperYml,
            descriptorEntry = "paper-plugin.yml",
            packagePaths = listOf("fixtures/clean/"),
        )
        val report = audit(jar)
        val hit = report.findings.first { it.ruleId == "J042" }
        assertEquals(Severity.WARNING, hit.severity)
        assertTrue(hit.detail.contains("Vault")) { hit.detail }
    }
}
