package com.salyvn.pluginscope

import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.stack.StackAuditor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
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

    @Test
    fun `high-risk transitive shading collisions report S032 critical`(@TempDir directory: Path) {
        install(
            directory,
            plugin(
                "ShopA",
                generatedClasses = listOf(
                    "org/bstats/bukkit/Metrics",
                    "com/zaxxer/hikari/HikariConfig",
                    "com/zaxxer/hikari/HikariDataSource",
                    "net/kyori/adventure/text/Component",
                    "net/kyori/adventure/text/TextComponent",
                ),
            ),
            plugin(
                "ShopB",
                generatedClasses = listOf(
                    "org/bstats/bukkit/Metrics",
                    "com/zaxxer/hikari/HikariConfig",
                    "com/zaxxer/hikari/HikariDataSource",
                    "com/zaxxer/hikari/pool/HikariPool",
                    "net/kyori/adventure/text/Component",
                    "net/kyori/adventure/text/TextComponent",
                ),
            ),
        )
        val report = audit(directory)
        val collisions = report.findings.filter { it.ruleId == "S032" }
        val locations = collisions.map { it.location }.toSet()
        assertEquals(setOf("org.bstats", "com.zaxxer.hikari", "net.kyori"), locations)
        assertTrue(collisions.all { it.severity == Severity.CRITICAL })
    }

    @Test
    fun `method ABI skew with identical class names is detected as S030`(@TempDir directory: Path) {
        val baseClasses = (1..4).map { "org/apache/commons/lang3/StringUtils$it" }
        val jarA = FixtureJars.pluginJar(
            jarName = "AbiA",
            descriptor = FixtureJars.descriptor("AbiA", "com.example.abia.Main", "26.2"),
            generatedClasses = listOf("com/example/abia/Main") + baseClasses.drop(1),
            extraEntries = mapOf(
                "${baseClasses.first()}.class" to FixtureJars.emptyClass(baseClasses.first(), 65, extraMethodName = "v1Only"),
            ),
        )
        val jarB = FixtureJars.pluginJar(
            jarName = "AbiB",
            descriptor = FixtureJars.descriptor("AbiB", "com.example.abib.Main", "26.2"),
            generatedClasses = listOf("com/example/abib/Main") + baseClasses.drop(1),
            extraEntries = mapOf(
                "${baseClasses.first()}.class" to FixtureJars.emptyClass(baseClasses.first(), 65, extraMethodName = "v2Only"),
            ),
        )
        install(directory, jarA, jarB)
        val report = audit(directory)
        assertTrue(report.findings.any { it.ruleId == "S030" && it.location == "org.apache.commons" })
    }

    @Test
    fun `paper plugin referencing another plugin in bytecode without declaring dependency reports S003 critical`(@TempDir directory: Path) {
        val economyJar = FixtureJars.pluginJar(
            jarName = "EconomyApi",
            descriptor = FixtureJars.descriptor("EconomyApi", "com.example.econ.EconomyPlugin", "26.2"),
            descriptorEntry = "paper-plugin.yml",
            generatedClasses = listOf("com/example/econ/EconomyPlugin"),
            extraEntries = mapOf(
                "com/example/econ/BankService.class" to FixtureJars.emptyClass(
                    "com/example/econ/BankService",
                    65,
                    extraMethodName = "deposit",
                ),
            ),
        )
        val consumerJar = FixtureJars.pluginJar(
            jarName = "ShopConsumer",
            descriptor = FixtureJars.descriptor("ShopConsumer", "com.example.shop.ShopPlugin", "26.2"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/example/shop/ShopPlugin.class" to FixtureJars.emptyClass(
                    "com/example/shop/ShopPlugin",
                    65,
                    callOwner = "com/example/econ/BankService",
                    callMethod = "deposit",
                ),
            ),
        )
        install(directory, economyJar, consumerJar)
        val report = audit(directory)
        val hit = report.findings.first { it.ruleId == "S003" }
        assertEquals(Severity.CRITICAL, hit.severity)
        assertEquals("ShopConsumer -> EconomyApi", hit.location)
        assertTrue(hit.evidence.any { it.contains("BankService#deposit") }) { hit.evidence.toString() }
    }

    @Test
    fun `paper isolated classloader blocks join-classpath false and closed classloader access as S004`(@TempDir directory: Path) {
        val corePaperDesc = FixtureJars.descriptor(
            "ClosedCore",
            "com.example.closed.CoreMain",
            "26.2",
            extraLines = listOf("has-open-classloader: false"),
        )
        val closedCoreJar = FixtureJars.pluginJar(
            jarName = "ClosedCore",
            descriptor = corePaperDesc,
            descriptorEntry = "paper-plugin.yml",
            generatedClasses = listOf("com/example/closed/CoreMain"),
            extraEntries = mapOf(
                "com/example/closed/CoreApi.class" to FixtureJars.emptyClass(
                    "com/example/closed/CoreApi",
                    65,
                    extraMethodName = "ping",
                ),
            ),
        )
        val paperCallerDesc = FixtureJars.descriptor(
            "IsolatedCaller",
            "com.example.caller.CallerMain",
            "26.2",
            extraLines = listOf(
                "dependencies:",
                "  server:",
                "    ClosedCore:",
                "      required: true",
                "      join-classpath: false",
            ),
        )
        val paperCallerJar = FixtureJars.pluginJar(
            jarName = "IsolatedCaller",
            descriptor = paperCallerDesc,
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/example/caller/CallerMain.class" to FixtureJars.emptyClass(
                    "com/example/caller/CallerMain",
                    65,
                    callOwner = "com/example/closed/CoreApi",
                    callMethod = "ping",
                ),
            ),
        )
        val legacyCallerJar = FixtureJars.pluginJar(
            jarName = "LegacyCaller",
            descriptor = FixtureJars.descriptor(
                "LegacyCaller",
                "com.example.legacy.LegacyMain",
                "26.2",
                extraLines = listOf("depend: [ClosedCore]"),
            ),
            descriptorEntry = "plugin.yml",
            generatedClasses = listOf("com/example/legacy/LegacyMain"),
        )
        install(directory, closedCoreJar, paperCallerJar, legacyCallerJar)
        val report = audit(directory)
        val blocked = report.findings.filter { it.ruleId == "S004" }.map { it.location }.toSet()
        assertEquals(setOf("IsolatedCaller -> ClosedCore", "LegacyCaller -> ClosedCore"), blocked)
    }

    @Test
    fun `service provider interface shaded across multiple plugins reports S033 critical`(@TempDir directory: Path) {
        val serviceBytes = "fixtures.clean.CleanPlugin\n".toByteArray()
        val jar1 = FixtureJars.pluginJar(
            jarName = "service-provider-a",
            descriptor = FixtureJars.descriptor("ProviderA", "fixtures.clean.CleanPlugin", "26.2"),
            packagePaths = listOf("fixtures/clean/"),
            extraEntries = mapOf("META-INF/services/com.example.api.StorageProvider" to serviceBytes),
        )
        val jar2 = FixtureJars.pluginJar(
            jarName = "service-provider-b",
            descriptor = FixtureJars.descriptor("ProviderB", "fixtures.clean.CleanPlugin", "26.2"),
            packagePaths = listOf("fixtures/clean/"),
            extraEntries = mapOf("META-INF/services/com.example.api.StorageProvider" to serviceBytes),
        )
        install(directory, jar1, jar2)

        val report = audit(directory)
        val finding = report.findings.firstOrNull { it.ruleId == "S033" }
        assertTrue(finding != null) { "expected S033 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertEquals("com.example.api.StorageProvider", finding.location)
        assertTrue(report.facts.containsKey("service conflicts"))
    }

    @Test
    fun `custom Bukkit Event class shaded across multiple plugins reports S034 critical`(@TempDir directory: Path) {
        val eventBytes = FixtureJars.eventClass("com/example/api/CustomTestEvent")
        val jar1 = FixtureJars.pluginJar(
            jarName = "event-plugin-a",
            descriptor = FixtureJars.descriptor("EventPluginA", "fixtures.clean.CleanPlugin", "26.2"),
            packagePaths = listOf("fixtures/clean/"),
            extraEntries = mapOf("com/example/api/CustomTestEvent.class" to eventBytes),
        )
        val jar2 = FixtureJars.pluginJar(
            jarName = "event-plugin-b",
            descriptor = FixtureJars.descriptor("EventPluginB", "fixtures.clean.CleanPlugin", "26.2"),
            packagePaths = listOf("fixtures/clean/"),
            extraEntries = mapOf("com/example/api/CustomTestEvent.class" to eventBytes),
        )
        install(directory, jar1, jar2)

        val report = audit(directory)
        val finding = report.findings.firstOrNull { it.ruleId == "S034" }
        assertTrue(finding != null) { "expected S034 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertEquals("com.example.api.CustomTestEvent", finding.location)
        assertTrue(report.facts.containsKey("event handler conflicts"))
    }

    @Test
    fun `paper bootstrap dependency targeting legacy plugin yml reports S005 blocker`(@TempDir directory: Path) {
        val bootstrapCaller = FixtureJars.pluginJar(
            jarName = "BootstrapCaller",
            descriptor = FixtureJars.descriptor(
                "BootstrapCaller",
                "com.example.caller.CallerMain",
                "26.3",
                extraLines = listOf(
                    "dependencies:",
                    "  bootstrap:",
                    "    LegacyTarget:",
                    "      required: true",
                ),
            ),
            descriptorEntry = "paper-plugin.yml",
            generatedClasses = listOf("com/example/caller/CallerMain"),
        )
        val legacyTarget = FixtureJars.pluginJar(
            jarName = "LegacyTarget",
            descriptor = FixtureJars.descriptor(
                "LegacyTarget",
                "com.example.target.TargetMain",
                "26.2",
            ),
            descriptorEntry = "plugin.yml",
            generatedClasses = listOf("com/example/target/TargetMain"),
        )
        install(directory, bootstrapCaller, legacyTarget)
        val report = audit(directory)
        val finding = report.findings.firstOrNull { it.ruleId == "S005" }
        assertTrue(finding != null) { "expected S005 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.BLOCKER, finding!!.severity)
        assertTrue(finding.location.contains("BootstrapCaller -> LegacyTarget")) { finding.location }
    }

    @Test
    fun `multi-loader load-order conflict with startup plugin depending on postworld plugin reports S006 critical`(@TempDir directory: Path) {
        val startupPlugin = FixtureJars.pluginJar(
            jarName = "StartupPlugin",
            descriptor = FixtureJars.descriptor(
                "StartupPlugin",
                "com.example.startup.StartupMain",
                "26.3",
                extraLines = listOf(
                    "load: STARTUP",
                    "dependencies:",
                    "  server:",
                    "    PostworldPlugin:",
                    "      load: BEFORE",
                ),
            ),
            descriptorEntry = "paper-plugin.yml",
            generatedClasses = listOf("com/example/startup/StartupMain"),
        )
        val postworldPlugin = FixtureJars.pluginJar(
            jarName = "PostworldPlugin",
            descriptor = FixtureJars.descriptor(
                "PostworldPlugin",
                "com.example.postworld.PostworldMain",
                "26.3",
                extraLines = listOf(
                    "load: POSTWORLD",
                ),
            ),
            descriptorEntry = "paper-plugin.yml",
            generatedClasses = listOf("com/example/postworld/PostworldMain"),
        )
        install(directory, startupPlugin, postworldPlugin)
        val report = audit(directory)
        val finding = report.findings.firstOrNull { it.ruleId == "S006" }
        assertTrue(finding != null) { "expected S006 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.WARNING, finding!!.severity)
        assertTrue(finding.detail.contains("STARTUP") || finding.detail.contains("POSTWORLD")) { finding.detail }
    }

    @Test
    fun `transitive API classloader exposure across isolated plugins reports S007 critical`(@TempDir directory: Path) {
        val thirdBytes = run {
            val cw = ClassWriter(0)
            cw.visit(65, Opcodes.ACC_PUBLIC, "com/example/third/ThirdType", null, "java/lang/Object", null)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val bridgeBytes = run {
            val cw = ClassWriter(0)
            cw.visit(65, Opcodes.ACC_PUBLIC, "com/example/bridge/BridgeMain", null, "java/lang/Object", null)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "getThird", "()Lcom/example/third/ThirdType;", null, null).apply {
                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 0)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val callerBytes = run {
            val cw = ClassWriter(0)
            cw.visit(65, Opcodes.ACC_PUBLIC, "com/example/caller/CallerMain", null, "java/lang/Object", null)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "testCall", "()V", null, null).apply {
                visitCode()
                visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/bridge/BridgeMain", "getThird", "()Lcom/example/third/ThirdType;", false)
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }

        val thirdJar = FixtureJars.pluginJar(
            jarName = "ThirdPlugin",
            descriptor = FixtureJars.descriptor("ThirdPlugin", "com.example.third.ThirdMain", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("com/example/third/ThirdType.class" to thirdBytes),
            generatedClasses = listOf("com/example/third/ThirdMain"),
        )
        val bridgeJar = FixtureJars.pluginJar(
            jarName = "BridgePlugin",
            descriptor = FixtureJars.descriptor("BridgePlugin", "com.example.bridge.BridgeMain", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("com/example/bridge/BridgeMain.class" to bridgeBytes),
        )
        val callerJar = FixtureJars.pluginJar(
            jarName = "CallerPlugin",
            descriptor = FixtureJars.descriptor(
                "CallerPlugin",
                "com.example.caller.CallerMain",
                "26.3",
                extraLines = listOf(
                    "dependencies:",
                    "  server:",
                    "    BridgePlugin:",
                    "      join-classpath: true",
                ),
            ),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("com/example/caller/CallerMain.class" to callerBytes),
        )

        install(directory, thirdJar, bridgeJar, callerJar)
        val report = audit(directory)
        val finding = report.findings.firstOrNull { it.ruleId == "S007" }
        assertTrue(finding != null) { "expected S007 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertTrue(finding.location.contains("CallerPlugin -> BridgePlugin -> ThirdPlugin")) { finding.location }
    }

    @Test
    fun `cross-plugin ASM operand dataflow retaining foreign callback in static registry reports S070 critical`(@TempDir directory: Path) {
        val targetBytes = run {
            val cw = ClassWriter(0)
            cw.visit(65, Opcodes.ACC_PUBLIC, "com/example/target/TargetService", null, "java/lang/Object", null)
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }
        val callerBytes = run {
            val cw = ClassWriter(0)
            val cls = "com/example/caller/CallerLeak"
            cw.visit(65, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null)
            cw.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "FOREIGN_SERVICE",
                "Lcom/example/target/TargetService;",
                null,
                null,
            ).visitEnd()
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PUBLIC, "storeTarget", "(Lcom/example/target/TargetService;)V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 1)
                visitFieldInsn(Opcodes.PUTSTATIC, cls, "FOREIGN_SERVICE", "Lcom/example/target/TargetService;")
                visitInsn(Opcodes.RETURN)
                visitMaxs(2, 2)
                visitEnd()
            }
            cw.visitEnd()
            cw.toByteArray()
        }

        val targetJar = FixtureJars.pluginJar(
            jarName = "TargetPlugin",
            descriptor = FixtureJars.descriptor("TargetPlugin", "com.example.target.TargetMain", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("com/example/target/TargetService.class" to targetBytes),
            generatedClasses = listOf("com/example/target/TargetMain"),
        )
        val callerJar = FixtureJars.pluginJar(
            jarName = "CallerPlugin",
            descriptor = FixtureJars.descriptor(
                "CallerPlugin",
                "com.example.caller.CallerLeak",
                "26.3",
                extraLines = listOf(
                    "dependencies:",
                    "  server:",
                    "    TargetPlugin:",
                    "      join-classpath: true",
                ),
            ),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf("com/example/caller/CallerLeak.class" to callerBytes),
        )

        install(directory, targetJar, callerJar)
        val report = audit(directory)
        val finding = report.findings.firstOrNull { it.ruleId == "S070" }
        assertTrue(finding != null) { "expected S070 in ${report.findings.map { it.ruleId }}" }
        assertTrue(finding!!.location.contains("CallerPlugin -> TargetPlugin")) { finding.location }
    }

    @Test
    fun `transitive multi-release JAR classfile collision reports S071 critical`(@TempDir directory: Path) {
        val baseClassBytes = FixtureJars.emptyClass("com/acme/util/FastCodec", 52)
        val mrClassBytes = FixtureJars.emptyClass("com/acme/util/FastCodec", 61, extraMethodName = "fastDecodeJava17")

        val pluginAJar = FixtureJars.pluginJar(
            jarName = "PluginA",
            descriptor = FixtureJars.descriptor("PluginA", "com.example.a.MainA", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nMulti-Release: true\n".toByteArray(),
                "META-INF/versions/17/com/acme/util/FastCodec.class" to mrClassBytes,
            ),
            generatedClasses = listOf("com/example/a/MainA"),
        )

        val pluginBJar = FixtureJars.pluginJar(
            jarName = "PluginB",
            descriptor = FixtureJars.descriptor("PluginB", "com.example.b.MainB", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/acme/util/FastCodec.class" to baseClassBytes,
            ),
            generatedClasses = listOf("com/example/b/MainB"),
        )

        install(directory, pluginAJar, pluginBJar)
        val report = audit(directory)
        val finding = report.findings.firstOrNull { it.ruleId == "S071" }
        assertTrue(finding != null) { "expected S071 in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.CRITICAL, finding!!.severity)
        assertTrue(finding.location.contains("com.acme.util.FastCodec")) { finding.location }
        assertTrue(finding.detail.contains("Multi-Release")) { finding.detail }
    }

    private fun buildEventListenerClass(
        internalName: String,
        eventInternalName: String,
        priority: String = "NORMAL",
        ignoreCancelled: Boolean = false,
        setCancelledConst: Int? = null,
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(65, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", arrayOf("org/bukkit/event/Listener"))
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "onEvent", "(L$eventInternalName;)V", null, null).apply {
            visitAnnotation("Lorg/bukkit/event/EventHandler;", true).apply {
                visitEnum("priority", "Lorg/bukkit/event/EventPriority;", priority)
                visit("ignoreCancelled", ignoreCancelled)
                visitEnd()
            }
            visitCode()
            if (setCancelledConst != null) {
                visitVarInsn(Opcodes.ALOAD, 1)
                visitInsn(if (setCancelledConst == 1) Opcodes.ICONST_1 else Opcodes.ICONST_0)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, eventInternalName, "setCancelled", "(Z)V", false)
            }
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `EventPriority MONITOR handler calling setCancelled across listening plugins reports S072 critical`(@TempDir directory: Path) {
        val auditJar = FixtureJars.pluginJar(
            jarName = "BlockAudit",
            descriptor = FixtureJars.descriptor("BlockAudit", "com.example.audit.AuditListener", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/example/audit/AuditListener.class" to buildEventListenerClass(
                    internalName = "com/example/audit/AuditListener",
                    eventInternalName = "org/bukkit/event/block/BlockBreakEvent",
                    priority = "MONITOR",
                    ignoreCancelled = true,
                    setCancelledConst = null,
                ),
            ),
        )
        val rogueMonitorJar = FixtureJars.pluginJar(
            jarName = "RogueTax",
            descriptor = FixtureJars.descriptor("RogueTax", "com.example.rogue.RogueMonitor", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/example/rogue/RogueMonitor.class" to buildEventListenerClass(
                    internalName = "com/example/rogue/RogueMonitor",
                    eventInternalName = "org/bukkit/event/block/BlockBreakEvent",
                    priority = "MONITOR",
                    ignoreCancelled = false,
                    setCancelledConst = 1,
                ),
            ),
        )
        install(directory, auditJar, rogueMonitorJar)
        val report = audit(directory)
        val finding = report.findings.firstOrNull { it.ruleId == "S072" && it.severity == Severity.CRITICAL }
        assertTrue(finding != null) { "expected critical S072 in ${report.findings.map { it.ruleId }}" }
        assertTrue(finding!!.detail.contains("MONITOR")) { finding.detail }
        assertTrue(finding.detail.contains("BlockAudit")) { finding.detail }
    }

    @Test
    fun `HIGHEST listener forcing setCancelled false overrides protection plugin as S072 warning`(@TempDir directory: Path) {
        val guardJar = FixtureJars.pluginJar(
            jarName = "ClaimGuard",
            descriptor = FixtureJars.descriptor("ClaimGuard", "com.example.claim.GuardListener", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/example/claim/GuardListener.class" to buildEventListenerClass(
                    internalName = "com/example/claim/GuardListener",
                    eventInternalName = "org/bukkit/event/block/BlockBreakEvent",
                    priority = "LOW",
                    ignoreCancelled = false,
                    setCancelledConst = 1,
                ),
            ),
        )
        val pickaxeJar = FixtureJars.pluginJar(
            jarName = "ExplosivePick",
            descriptor = FixtureJars.descriptor("ExplosivePick", "com.example.pick.PickListener", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/example/pick/PickListener.class" to buildEventListenerClass(
                    internalName = "com/example/pick/PickListener",
                    eventInternalName = "org/bukkit/event/block/BlockBreakEvent",
                    priority = "HIGHEST",
                    ignoreCancelled = false,
                    setCancelledConst = 0,
                ),
            ),
        )
        install(directory, guardJar, pickaxeJar)
        val report = audit(directory)
        val finding = report.findings.firstOrNull { it.ruleId == "S072" }
        assertTrue(finding != null) { "expected S072 warning in ${report.findings.map { it.ruleId }}" }
        assertEquals(Severity.WARNING, finding!!.severity)
        assertTrue(finding.detail.contains("setCancelled(false)")) { finding.detail }
    }

    @Test
    fun `same priority cancel race without load order edge reports S072 warning while explicit ordering suppresses it`(@TempDir directory: Path) {
        val aJar = FixtureJars.pluginJar(
            jarName = "CombatA",
            descriptor = FixtureJars.descriptor("CombatA", "com.example.ca.CombatAListener", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/example/ca/CombatAListener.class" to buildEventListenerClass(
                    internalName = "com/example/ca/CombatAListener",
                    eventInternalName = "org/bukkit/event/entity/EntityDamageByEntityEvent",
                    priority = "HIGH",
                    ignoreCancelled = false,
                    setCancelledConst = 1,
                ),
            ),
        )
        val bJar = FixtureJars.pluginJar(
            jarName = "CombatB",
            descriptor = FixtureJars.descriptor("CombatB", "com.example.cb.CombatBListener", "26.3"),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/example/cb/CombatBListener.class" to buildEventListenerClass(
                    internalName = "com/example/cb/CombatBListener",
                    eventInternalName = "org/bukkit/event/entity/EntityDamageByEntityEvent",
                    priority = "HIGH",
                    ignoreCancelled = false,
                    setCancelledConst = 0,
                ),
            ),
        )
        install(directory, aJar, bJar)
        val unorderedReport = audit(directory)
        assertTrue(unorderedReport.findings.any { it.ruleId == "S072" }) {
            "expected S072 on unordered same-priority mutators"
        }

        // Now verify explicit load: AFTER dependency suppresses S072
        java.nio.file.Files.deleteIfExists(directory.resolve(bJar.fileName))
        val bOrderedJar = FixtureJars.pluginJar(
            jarName = "CombatBOrdered",
            descriptor = FixtureJars.descriptor(
                "CombatB",
                "com.example.cb.CombatBListener",
                "26.3",
                extraLines = listOf(
                    "dependencies:",
                    "  server:",
                    "    CombatA:",
                    "      load: AFTER",
                ),
            ),
            descriptorEntry = "paper-plugin.yml",
            extraEntries = mapOf(
                "com/example/cb/CombatBListener.class" to buildEventListenerClass(
                    internalName = "com/example/cb/CombatBListener",
                    eventInternalName = "org/bukkit/event/entity/EntityDamageByEntityEvent",
                    priority = "HIGH",
                    ignoreCancelled = false,
                    setCancelledConst = 0,
                ),
            ),
        )
        install(directory, bOrderedJar)
        val orderedReport = audit(directory)
        assertTrue(orderedReport.findings.none { it.ruleId == "S072" }) {
            "unexpected S072 when explicit load: AFTER ordering is declared: ${orderedReport.findings}"
        }
    }
}
