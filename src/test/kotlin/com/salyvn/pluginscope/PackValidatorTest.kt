package com.salyvn.pluginscope

import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.pack.PackAuditOptions
import com.salyvn.pluginscope.pack.PackValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PackValidatorTest {

    /**
     * Every test states the target format directly, so nothing here touches the network: the format
     * table is only fetched when a Minecraft version is given instead.
     */
    private fun validate(pack: Path, against: List<Path> = emptyList()) = PackValidator(
        PackAuditOptions(
            packFormat = FixturePacks.FORMAT,
            against = against,
            allowNetwork = false,
        )
    ).validate(pack)

    @Test
    fun `a correct pack produces nothing above information`(@TempDir root: Path) {
        val report = validate(FixturePacks.clean(root))
        val serious = report.findings.filter { it.severity.rank >= Severity.WARNING.rank }
        assertEquals(emptyList<String>(), serious.map { "${it.ruleId} ${it.location} ${it.detail}" })
    }

    @Test
    fun `a reference into vanilla is not treated as a broken reference`(@TempDir root: Path) {
        // The clean fixture inherits from minecraft:item/generated, which the pack does not and
        // should not contain. Reporting that would make the tool unusable on every real pack.
        val report = validate(FixturePacks.clean(root))
        assertTrue(report.findings.none { it.ruleId == "P020" }) { report.findings.map { it.detail }.toString() }
        assertTrue(report.facts.getValue("references").contains("expected from vanilla"))
    }

    @Test
    fun `the broken pack reports each defect once`(@TempDir root: Path) {
        val ids = validate(FixturePacks.broken(root)).findings.map { it.ruleId }.toSet()
        val expected = setOf(
            "P011", // supported_formats together with a modern min_format
            "P013", // pack_format given as a string
            "P020", // parent and texture that resolve to nothing
            "P021", // parent cycle
            "P022", // sounds.json naming an absent ogg
            "P023", // font provider naming an absent file
            "P024", // atlas source naming an absent sprite
            "P030", // two cases matching the same value
            "P032", // negative custom_model_data index
            "P040", // uppercase in a resource path
            "P041", // .DS_Store and a stray .psd
            "P042", // the removed overrides mechanism
            "P050", // overlay directory that is not in the pack
        )
        assertEquals(emptySet<String>(), expected - ids) { "missing from the report: ${expected - ids}, got $ids" }
    }

    @Test
    fun `png defects are reported from the header rather than guessed`(@TempDir root: Path) {
        val ids = validate(FixturePacks.broken(root)).findings.map { it.ruleId }.toSet()
        assertTrue("P060" in ids) { "12x12 texture not reported as non-power-of-two: $ids" }
        assertTrue("P061" in ids) { "a file that is not a PNG was not reported: $ids" }
        assertTrue("P062" in ids) { "animation frame height mismatch not reported: $ids" }
    }

    @Test
    fun `a zip and a directory of the same pack agree`(@TempDir root: Path) {
        val directory = FixturePacks.broken(root)
        val zipped = FixturePacks.zip(directory, root.resolve("out/broken.zip"))

        val fromDirectory = validate(directory).findings.map { "${it.ruleId}|${it.location}" }.sorted()
        val fromZip = validate(zipped).findings.map { "${it.ruleId}|${it.location}" }.sorted()
        val onlyInDirectory = fromDirectory - fromZip.toSet()
        val onlyInZip = fromZip - fromDirectory.toSet()
        assertEquals(fromDirectory, fromZip) {
            "only in the directory: $onlyInDirectory; only in the zip: $onlyInZip"
        }
    }

    @Test
    fun `a pack zipped one level too deep is called out`(@TempDir root: Path) {
        val report = validate(FixturePacks.nestedRoot(root))
        val finding = report.findings.first { it.ruleId == "P001" }
        assertEquals(Severity.BLOCKER, finding.severity)
        assertTrue(finding.detail.contains("MyPack") || finding.evidence.any { it.contains("MyPack") }) {
            "${finding.detail} ${finding.evidence}"
        }
    }

    @Test
    fun `two packs claiming the same item definition collide`(@TempDir root: Path) {
        val clean = FixturePacks.clean(root)
        val rival = FixturePacks.rival(root)
        val report = validate(clean, against = listOf(rival))
        val collision = report.findings.first { it.ruleId == "P031" }
        assertTrue(collision.evidence.any { it.contains("wand") }) { collision.evidence.toString() }
        assertTrue(collision.detail.contains("rival")) { collision.detail }
    }

    @Test
    fun `without a target the format checks are skipped and said to be skipped`(@TempDir root: Path) {
        val report = PackValidator(PackAuditOptions(allowNetwork = false)).validate(FixturePacks.clean(root))
        assertTrue(report.findings.any { it.ruleId == "P014" })
        assertTrue(report.findings.none { it.ruleId == "P010" || it.ruleId == "P012" })
    }

    @Test
    fun `facts describe the pack`(@TempDir root: Path) {
        val facts = validate(FixturePacks.clean(root)).facts
        assertEquals("directory", facts["container"])
        assertEquals("testpack", facts["asset namespaces"])
        assertTrue(facts.getValue("declared format").contains(FixturePacks.FORMAT.toString()))
        // Locale must not leak into the numbers: a comma here would break machine consumers.
        assertTrue(facts.getValue("entries").contains(".")) { facts.getValue("entries") }
    }
}
