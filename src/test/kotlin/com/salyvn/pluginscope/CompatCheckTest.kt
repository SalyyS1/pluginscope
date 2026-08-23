package com.salyvn.pluginscope

import com.salyvn.pluginscope.compat.ApiSurface
import com.salyvn.pluginscope.compat.CompatChecker
import com.salyvn.pluginscope.compat.CompatOptions
import com.salyvn.pluginscope.compat.PaperApiResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompatCheckTest {

    private val current = ApiSurface.fromJars(
        listOf(
            FixtureJars.apiJar(
                jarName = "current-api",
                includeLegacyOnly = false,
                removeMethods = setOf("org/bukkit/Server" to "getMotd"),
            )
        ),
        label = "current API",
    )

    private val legacy = ApiSurface.fromJars(listOf(FixtureJars.apiJar("legacy-api")), label = "legacy API")

    @Test
    fun `a removed class and a removed member are both reported against the newer API`() {
        val report = CompatChecker().check(FixtureJars.breakingPlugin(), current)
        val ids = report.findings.map { it.ruleId }.toSet()
        assertTrue("C001" in ids) { "removed class not reported: ${report.findings.map { it.location }}" }
        assertTrue("C002" in ids) { "removed member not reported: ${report.findings.map { it.location }}" }

        val absentClass = report.findings.first { it.ruleId == "C001" }
        assertEquals("org.bukkit.LegacyOnly", absentClass.location)
        assertTrue(report.findings.count { it.ruleId == "C001" } == 1) {
            "a missing class should be reported once, not once per member touched on it"
        }
        assertTrue(report.findings.first { it.ruleId == "C002" }.location.contains("getMotd"))
    }

    @Test
    fun `the same jar is clean against the API it was written for`() {
        val report = CompatChecker().check(FixtureJars.breakingPlugin(), legacy)
        assertTrue(report.findings.none { it.ruleId == "C001" || it.ruleId == "C002" }) {
            report.findings.map { "${it.ruleId} ${it.location}" }.toString()
        }
    }

    @Test
    fun `deprecated members are reported as information, not as breakage`() {
        val report = CompatChecker().check(FixtureJars.breakingPlugin(), legacy)
        val deprecated = report.findings.filter { it.ruleId == "C010" }
        assertTrue(deprecated.isNotEmpty()) { "no C010 in ${report.findings.map { it.ruleId }}" }
        assertTrue(deprecated.all { it.severity.rank == 1 })
        assertTrue(deprecated.any { it.location.contains("getOnlineMode") })
    }

    @Test
    fun `deprecation reporting can be turned off`() {
        val report = CompatChecker(CompatOptions(reportDeprecated = false))
            .check(FixtureJars.breakingPlugin(), legacy)
        assertTrue(report.findings.none { it.ruleId == "C010" })
    }

    @Test
    fun `server internals are separated from API breakage`() {
        val report = CompatChecker().check(FixtureJars.breakingPlugin(), current)
        val internals = report.findings.filter { it.ruleId == "C020" }
        assertEquals(1, internals.size)
        assertEquals("net.minecraft.server.MinecraftServer", internals.single().location)
    }

    @Test
    fun `a plugin using only surviving API reports nothing`() {
        val report = CompatChecker().check(FixtureJars.cleanPlugin(), current)
        assertEquals(emptyList<String>(), report.findings.map { "${it.ruleId} ${it.location}" })
    }

    @Test
    fun `the version selector prefers the highest build of a matching release`() {
        val versions = listOf(
            "1.21.4-R0.1-SNAPSHOT",
            "26.2.build.9-stable",
            "26.2.build.116-stable",
            "26.3.build.1-stable",
        )
        assertEquals("26.2.build.116-stable", PaperApiResolver.select("26.2", versions))
        assertEquals("1.21.4-R0.1-SNAPSHOT", PaperApiResolver.select("1.21.4", versions))
        assertEquals("26.3.build.1-stable", PaperApiResolver.select("latest", versions))
        assertNull(PaperApiResolver.select("1.7.10", versions))
    }

    @Test
    fun `snapshot metadata resolves to the plain jar rather than sources or javadoc`() {
        val metadata = """
            <metadata>
              <versioning>
                <snapshotVersions>
                  <snapshotVersion>
                    <classifier>javadoc</classifier><extension>jar</extension>
                    <value>1.21.4-R0.1-20250925.065901-231</value>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>1.21.4-R0.1-20250925.065901-231</value>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
        """.trimIndent()
        assertEquals("1.21.4-R0.1-20250925.065901-231", PaperApiResolver.plainJarSnapshotValue(metadata))
    }
}
