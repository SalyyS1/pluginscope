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

    @Test
    fun `plugin yml declaring api-version 26_3 without paper-plugin yml warns as J014`() {
        val jar = FixtureJars.pluginJar(
            jarName = "modern-in-legacy",
            descriptor = FixtureJars.descriptor("LegacyModern", "fixtures.clean.CleanPlugin", "26.3"),
            packagePaths = listOf("fixtures/clean/"),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J014" }
        assertTrue(finding != null) { "expected J014 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.WARNING, finding!!.severity)
        assertTrue(finding.detail.contains("26.3")) { finding.detail }
    }

    @Test
    fun `bytecode referencing versioned NMS on Paper 26_3 reports J025 critical`() {
        val jar = FixtureJars.pluginJar(
            jarName = "versioned-nms",
            descriptor = FixtureJars.descriptor("VersionedNms", "fixtures.clean.CleanPlugin", "26.3"),
            packagePaths = listOf("fixtures/clean/"),
            generatedClasses = listOf("net/minecraft/server/v1_20_R3/EntityPlayer"),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J025" }
        assertTrue(finding != null) { "expected J025 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertTrue(finding.detail.contains("Paper 26.x+")) { finding.detail }
    }

    @Test
    fun `legacy plugin referencing unversioned Mojang mappings reports J026 critical`() {
        val jar = FixtureJars.pluginJar(
            jarName = "mojang-in-legacy",
            descriptor = FixtureJars.descriptor("MojangLegacy", "fixtures.clean.CleanPlugin", "1.20.4"),
            packagePaths = listOf("fixtures/clean/"),
            generatedClasses = listOf("net/minecraft/world/entity/player/Player"),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J026" }
        assertTrue(finding != null) { "expected J026 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertTrue(finding.detail.contains("Mojang-mapped")) { finding.detail }
    }

    @Test
    fun `paper-plugin yml with colliding entrypoints reports J043 critical`() {
        val paperYml = FixtureJars.descriptor(
            "CollidingEntrypoints",
            "fixtures.clean.CleanPlugin",
            "26.3",
            extraLines = listOf(
                "bootstrapper: fixtures.clean.CleanPlugin",
            ),
        )
        val jar = FixtureJars.pluginJar(
            jarName = "colliding-entrypoints",
            descriptor = paperYml,
            descriptorEntry = "paper-plugin.yml",
            packagePaths = listOf("fixtures/clean/"),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J043" }
        assertTrue(finding != null) { "expected J043 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertTrue(finding.detail.contains("Entrypoint classes collide")) { finding.detail }
    }

    @Test
    fun `paper-plugin yml with invalid dependency load mode reports J043 blocker`() {
        val paperYml = FixtureJars.descriptor(
            "InvalidDepLoad",
            "fixtures.clean.CleanPlugin",
            "26.3",
            extraLines = listOf(
                "dependencies:",
                "  server:",
                "    Vault:",
                "      load: SOMETHING_WRONG",
            ),
        )
        val jar = FixtureJars.pluginJar(
            jarName = "invalid-dep-load",
            descriptor = paperYml,
            descriptorEntry = "paper-plugin.yml",
            packagePaths = listOf("fixtures/clean/"),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J043" }
        assertTrue(finding != null) { "expected J043 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.BLOCKER, finding!!.severity)
        assertTrue(finding.detail.contains("Invalid dependency load mode")) { finding.detail }
    }

    @Test
    fun `paper-plugin yml with contradictory bootstrap and server load order reports J043`() {
        val paperYml = FixtureJars.descriptor(
            "ContradictoryDepLoad",
            "fixtures.clean.CleanPlugin",
            "26.3",
            extraLines = listOf(
                "dependencies:",
                "  bootstrap:",
                "    Vault:",
                "      load: BEFORE",
                "  server:",
                "    Vault:",
                "      load: AFTER",
            ),
        )
        val jar = FixtureJars.pluginJar(
            jarName = "contradictory-dep-load",
            descriptor = paperYml,
            descriptorEntry = "paper-plugin.yml",
            packagePaths = listOf("fixtures/clean/"),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J043" }
        assertTrue(finding != null) { "expected J043 in ${report.findings.map { it.ruleId }}" }
        assertTrue(finding!!.detail.contains("Contradictory load order")) { finding.detail }
    }

    @Test
    fun `dual descriptors with contradictory load orders report J043`() {
        val paperYml = FixtureJars.descriptor(
            "DualContradictory",
            "fixtures.clean.CleanPlugin",
            "26.3",
            extraLines = listOf(
                "dependencies:",
                "  server:",
                "    Vault:",
                "      load: AFTER",
            ),
        )
        val legacyYml = FixtureJars.descriptor(
            "DualContradictory",
            "fixtures.clean.CleanPlugin",
            "26.3",
            extraLines = listOf(
                "depend: [Vault]",
            ),
        )
        val jar = FixtureJars.pluginJar(
            jarName = "dual-contradictory-load",
            descriptor = paperYml,
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("plugin.yml" to legacyYml.toByteArray()),
            packagePaths = listOf("fixtures/clean/"),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J043" }
        assertTrue(finding != null) { "expected J043 in ${report.findings.map { it.ruleId }}" }
        assertTrue(finding!!.detail.contains("Dual descriptors declare opposite")) { finding.detail }
    }

    @Test
    fun `paper-plugin yml referencing CraftBukkit reports J075 critical`() {
        val paperYml = FixtureJars.descriptor(
            "PaperRemapperHazard",
            "fixtures.clean.CleanPlugin",
            "26.3",
        )
        val jar = FixtureJars.pluginJar(
            jarName = "paper-remapper-hazard",
            descriptor = paperYml,
            descriptorEntry = "paper-plugin.yml",
            packagePaths = listOf("fixtures/clean/"),
            generatedClasses = listOf("org/bukkit/craftbukkit/entity/CraftPlayer"),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J075" }
        assertTrue(finding != null) { "expected J075 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertTrue(finding.detail.contains("bypass PluginRemapper")) { finding.detail }
    }

    @Test
    fun `bytecode containing NMS or CraftBukkit reflection string literal reports J076 warning`() {
        val legacyYml = FixtureJars.descriptor(
            "ReflectionLiteralPlugin",
            "com.example.reflect.ReflectMain",
            "1.20.4",
        )
        val ldcClassBytes = FixtureJars.classWithLdc(
            internalName = "com/example/reflect/ReflectMain",
            majorVersion = 65,
            ldcConstant = "org.bukkit.craftbukkit.v1_20_R3.CraftServer",
        )
        val jar = FixtureJars.pluginJar(
            jarName = "reflection-literal",
            descriptor = legacyYml,
            descriptorEntry = "plugin.yml",
            extraEntries = mapOf("com/example/reflect/ReflectMain.class" to ldcClassBytes),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J076" }
        assertTrue(finding != null) { "expected J076 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.WARNING, finding!!.severity)
        assertTrue(finding.detail.contains("unrelocated NMS or CraftBukkit reflection string literal")) { finding.detail }
    }

    @Test
    fun `runtime MappedRegistry mutation reports J077 critical`() {
        val clsName = "com/example/codec/FrozenRegMutator"
        val classBytes = run {
            val cw = org.objectweb.asm.ClassWriter(0)
            cw.visit(65, org.objectweb.asm.Opcodes.ACC_PUBLIC, clsName, null, "java/lang/Object", null)
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "onEnable", "()V", null, null).apply {
                visitCode()
                visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                visitLdcInsn("custom:comp")
                visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                visitMethodInsn(
                    org.objectweb.asm.Opcodes.INVOKESTATIC,
                    "net/minecraft/core/Registry",
                    "register",
                    "(Lnet/minecraft/core/Registry;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
                    true,
                )
                visitInsn(org.objectweb.asm.Opcodes.POP)
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(3, 1)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val jar = FixtureJars.pluginJar(
            jarName = "frozen-registry-mutator",
            descriptor = FixtureJars.descriptor("FrozenRegPlugin", "com.example.codec.FrozenRegMutator", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("$clsName.class" to classBytes),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J077" && it.severity == Severity.CRITICAL }
        assertTrue(finding != null) { "expected critical J077 in ${report.findings.map { it.ruleId }}" }
        assertTrue(finding!!.detail.contains("MappedRegistry")) { finding.detail }
    }

    @Test
    fun `legacy BlockState setRawData on Paper 26_3 reports J077 critical`() {
        val clsName = "com/example/codec/LegacyRawBlockPlugin"
        val classBytes = run {
            val cw = org.objectweb.asm.ClassWriter(0)
            cw.visit(65, org.objectweb.asm.Opcodes.ACC_PUBLIC, clsName, null, "java/lang/Object", null)
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "mutateRaw", "(Lorg/bukkit/block/BlockState;)V", null, null).apply {
                visitCode()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1)
                visitInsn(org.objectweb.asm.Opcodes.ICONST_3)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "org/bukkit/block/BlockState", "setRawData", "(B)V", true)
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(2, 2)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val jar = FixtureJars.pluginJar(
            jarName = "legacy-raw-block",
            descriptor = FixtureJars.descriptor("LegacyRawBlock", "com.example.codec.LegacyRawBlockPlugin", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("$clsName.class" to classBytes),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J077" && it.severity == Severity.CRITICAL }
        assertTrue(finding != null) { "expected critical J077 in ${report.findings.map { it.ruleId }}" }
        assertTrue(finding!!.detail.contains("MaterialData")) { finding.detail }
    }

    @Test
    fun `detached Block getState TileState PDC mutation without update reports J077 warning`() {
        val clsName = "com/example/codec/DetachedTilePlugin"
        val classBytes = run {
            val cw = org.objectweb.asm.ClassWriter(0)
            cw.visit(65, org.objectweb.asm.Opcodes.ACC_PUBLIC, clsName, null, "java/lang/Object", null)
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "writeTagBuggy", "(Lorg/bukkit/block/Block;)V", null, null).apply {
                visitCode()
                val lbl = org.objectweb.asm.Label()
                visitLabel(lbl)
                visitLineNumber(55, lbl)
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "org/bukkit/block/Block", "getState", "()Lorg/bukkit/block/BlockState;", true)
                visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "org/bukkit/block/TileState")
                visitVarInsn(org.objectweb.asm.Opcodes.ASTORE, 2)
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2)
                visitMethodInsn(
                    org.objectweb.asm.Opcodes.INVOKEINTERFACE,
                    "org/bukkit/block/TileState",
                    "getPersistentDataContainer",
                    "()Lorg/bukkit/persistence/PersistentDataContainer;",
                    true,
                )
                visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                visitLdcInsn("tier-3")
                visitMethodInsn(
                    org.objectweb.asm.Opcodes.INVOKEINTERFACE,
                    "org/bukkit/persistence/PersistentDataContainer",
                    "set",
                    "(Lorg/bukkit/NamespacedKey;Lorg/bukkit/persistence/PersistentDataType;Ljava/lang/Object;)V",
                    true,
                )
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(4, 3)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val jar = FixtureJars.pluginJar(
            jarName = "detached-tile-bug",
            descriptor = FixtureJars.descriptor("DetachedTile", "com.example.codec.DetachedTilePlugin", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("$clsName.class" to classBytes),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J077" }
        assertTrue(finding != null) { "expected J077 warning in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.WARNING, finding!!.severity)
        assertTrue(finding.detail.contains("PersistentDataContainer#set")) { finding.detail }
    }

    @Test
    fun `detached Block getState calling update or live getState false produces no J077`() {
        val clsName = "com/example/codec/CleanTilePlugin"
        val classBytes = run {
            val cw = org.objectweb.asm.ClassWriter(0)
            cw.visit(65, org.objectweb.asm.Opcodes.ACC_PUBLIC, clsName, null, "java/lang/Object", null)
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "writeWithUpdate", "(Lorg/bukkit/block/Block;)V", null, null).apply {
                visitCode()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "org/bukkit/block/Block", "getState", "()Lorg/bukkit/block/BlockState;", true)
                visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "org/bukkit/block/TileState")
                visitVarInsn(org.objectweb.asm.Opcodes.ASTORE, 2)
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2)
                visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "org/bukkit/block/BlockState", "setBlockData", "(Lorg/bukkit/block/data/BlockData;)V", true)
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "org/bukkit/block/BlockState", "update", "()Z", true)
                visitInsn(org.objectweb.asm.Opcodes.POP)
                // Also verify Paper live getState(false) needs no update()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1)
                visitInsn(org.objectweb.asm.Opcodes.ICONST_0)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "org/bukkit/block/Block", "getState", "(Z)Lorg/bukkit/block/BlockState;", true)
                visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "org/bukkit/block/BlockState", "setBlockData", "(Lorg/bukkit/block/data/BlockData;)V", true)
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(3, 3)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val jar = FixtureJars.pluginJar(
            jarName = "clean-tile-plugin",
            descriptor = FixtureJars.descriptor("CleanTile", "com.example.codec.CleanTilePlugin", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("$clsName.class" to classBytes),
        )
        val report = audit(jar)
        assertTrue(report.findings.none { it.ruleId == "J077" }) {
            "unexpected J077 in ${report.findings}"
        }
    }

    @Test
    fun `ASM ClassWriter instantiated with COMPUTE_FRAMES reports J078 critical`() {
        val clsName = "com/example/weaver/BrokenWeaver"
        val classBytes = run {
            val cw = org.objectweb.asm.ClassWriter(0)
            cw.visit(65, org.objectweb.asm.Opcodes.ACC_PUBLIC, clsName, null, "java/lang/Object", null)
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "generateProxy", "()[B", null, null).apply {
                visitCode()
                visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "com/example/weaver/libs/asm/ClassWriter")
                visitInsn(org.objectweb.asm.Opcodes.DUP)
                // COMPUTE_FRAMES (2) | COMPUTE_MAXS (1) = 3
                visitInsn(org.objectweb.asm.Opcodes.ICONST_3)
                visitMethodInsn(
                    org.objectweb.asm.Opcodes.INVOKESPECIAL,
                    "com/example/weaver/libs/asm/ClassWriter",
                    "<init>",
                    "(I)V",
                    false,
                )
                visitInsn(org.objectweb.asm.Opcodes.POP)
                visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                visitInsn(org.objectweb.asm.Opcodes.ARETURN)
                visitMaxs(3, 1)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val jar = FixtureJars.pluginJar(
            jarName = "broken-asm-weaver",
            descriptor = FixtureJars.descriptor("BrokenWeaver", "com.example.weaver.BrokenWeaver", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("$clsName.class" to classBytes),
        )
        val report = audit(jar)
        val finding = report.findings.firstOrNull { it.ruleId == "J078" }
        assertTrue(finding != null) { "expected J078 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertTrue(finding.detail.contains("COMPUTE_FRAMES")) { finding.detail }
    }

    @Test
    fun `custom ClassWriter subclass overriding getCommonSuperClass with COMPUTE_FRAMES is safe`() {
        val safeSubName = "com/example/weaver/PluginSafeClassWriter"
        val mainName = "com/example/weaver/SafeWeaverMain"
        val safeSubBytes = run {
            val cw = org.objectweb.asm.ClassWriter(0)
            cw.visit(65, org.objectweb.asm.Opcodes.ACC_PUBLIC, safeSubName, null, "com/example/weaver/libs/asm/ClassWriter", null)
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                visitInsn(org.objectweb.asm.Opcodes.ICONST_2)
                visitMethodInsn(
                    org.objectweb.asm.Opcodes.INVOKESPECIAL,
                    "com/example/weaver/libs/asm/ClassWriter",
                    "<init>",
                    "(I)V",
                    false,
                )
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(2, 1)
                visitEnd()
            }
            cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PROTECTED,
                "getCommonSuperClass",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                null,
                null,
            ).apply {
                visitCode()
                visitLdcInsn("java/lang/Object")
                visitInsn(org.objectweb.asm.Opcodes.ARETURN)
                visitMaxs(1, 3)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val mainBytes = run {
            val cw = org.objectweb.asm.ClassWriter(0)
            cw.visit(65, org.objectweb.asm.Opcodes.ACC_PUBLIC, mainName, null, "java/lang/Object", null)
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitTypeInsn(org.objectweb.asm.Opcodes.NEW, safeSubName)
                visitInsn(org.objectweb.asm.Opcodes.DUP)
                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, safeSubName, "<init>", "()V", false)
                visitInsn(org.objectweb.asm.Opcodes.POP)
                visitInsn(org.objectweb.asm.Opcodes.RETURN)
                visitMaxs(2, 1)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val jar = FixtureJars.pluginJar(
            jarName = "safe-asm-weaver",
            descriptor = FixtureJars.descriptor("SafeWeaver", "com.example.weaver.SafeWeaverMain", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "$safeSubName.class" to safeSubBytes,
                "$mainName.class" to mainBytes,
            ),
        )
        val report = audit(jar)
        assertTrue(report.findings.none { it.ruleId == "J078" }) {
            "unexpected J078 on safe ClassWriter subclass: ${report.findings}"
        }
    }
}
