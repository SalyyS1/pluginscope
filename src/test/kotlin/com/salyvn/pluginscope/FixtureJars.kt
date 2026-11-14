package com.salyvn.pluginscope

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Builds throwaway jars for the tests.
 *
 * Fixture plugins are packed from classes the normal test compilation produced, so the analysers
 * are exercised against the instruction shapes kotlinc and javac actually emit rather than against
 * hand-assembled bytecode. Padding classes for the bundling checks are generated with ASM, which
 * keeps a jar that needs "forty classes under com.google.gson" from needing forty source files.
 */
object FixtureJars {

    /** A plugin whose jar contains nothing but its own code and a correct descriptor. */
    fun cleanPlugin(): Path = pluginJar(
        jarName = "clean-plugin",
        descriptor = descriptor("CleanPlugin", "fixtures.clean.CleanPlugin", "26.2"),
        packagePaths = listOf("fixtures/clean/"),
    )

    /** The plugin that touches removed, deprecated and internal API. */
    fun breakingPlugin(): Path = pluginJar(
        jarName = "breaking-plugin",
        descriptor = descriptor("BreakingPlugin", "fixtures.breaking.BreakingPlugin", "26.2"),
        packagePaths = listOf("fixtures/breaking/"),
    )

    /**
     * Everything a release jar should not be: server classes and provided libraries shipped
     * unrelocated, the Kotlin runtime under its own name, leftover build and signature metadata,
     * a nested jar, sources, and a descriptor with a misspelled dependency key and no api-version.
     */
    fun messyPlugin(): Path = pluginJar(
        jarName = "messy-plugin",
        descriptor = buildString {
            appendLine("name: MessyPlugin")
            appendLine("version: 1.0.0")
            appendLine("main: fixtures.clean.CleanPlugin")
            appendLine("depends: [Vault]")
        },
        packagePaths = listOf("fixtures/clean/"),
        generatedClasses = buildList {
            addAll((1..4).map { "org/bukkit/Shadowed$it" })
            addAll((1..6).map { "com/google/gson/Gson$it" })
            addAll((1..8).map { "kotlin/jdk7/AutoCloseableKt$it" })
            addAll((1..5).map { "org/apache/commons/lang3/StringUtils$it" })
        },
        extraEntries = mapOf(
            "META-INF/maven/com.google.code.gson/gson/pom.xml" to "<project/>".toByteArray(),
            "META-INF/SIGNED.SF" to "Signature-Version: 1.0\n".toByteArray(),
            "fixtures/clean/CleanPlugin.kt" to "// shipped by accident\n".toByteArray(),
            "libs/embedded.jar" to ByteArray(64),
            ".DS_Store" to ByteArray(8),
        ),
    )

    /** A descriptor pointing at a class the jar does not contain, plus an illegal plugin name. */
    fun brokenDescriptorPlugin(): Path = pluginJar(
        jarName = "broken-descriptor",
        descriptor = buildString {
            appendLine("name: 'Bad/Name!'")
            appendLine("version: 1.0.0")
            appendLine("main: com.example.absent.Main")
            appendLine("api-version: yesterday")
        },
        packagePaths = listOf("fixtures/clean/"),
    )

    /** Own classes compiled for a newer Java release than a target server would run. */
    fun futureJavaPlugin(): Path = pluginJar(
        jarName = "future-java",
        descriptor = descriptor("FutureJava", "fixtures/clean/CleanPlugin".replace('/', '.'), "26.2"),
        packagePaths = listOf("fixtures/clean/"),
        generatedClasses = listOf("fixtures/clean/Tomorrow"),
        generatedMajorVersion = 69,
    )

    /**
     * @param removeMethods `owner` to `name` pairs stripped from the packed classes, which is how a
     *   "the next server release deleted this method" jar is produced from one set of sources.
     */
    fun apiJar(
        jarName: String,
        includeLegacyOnly: Boolean = true,
        removeMethods: Set<Pair<String, String>> = emptySet(),
    ): Path {
        val classes = listOf("org/bukkit/Bukkit.class", "org/bukkit/Server.class") +
            if (includeLegacyOnly) listOf("org/bukkit/LegacyOnly.class") else emptyList()
        val target = Files.createTempFile(jarName, ".jar")
        JarOutputStream(Files.newOutputStream(target)).use { out ->
            for (resource in classes) {
                val owner = resource.removeSuffix(".class")
                val strip = removeMethods.filter { it.first == owner }.map { it.second }.toSet()
                out.putNextEntry(JarEntry(resource))
                out.write(if (strip.isEmpty()) readResource(resource) else without(readResource(resource), strip))
                out.closeEntry()
            }
        }
        target.toFile().deleteOnExit()
        return target
    }

    fun pluginJar(
        jarName: String,
        descriptor: String?,
        packagePaths: List<String> = emptyList(),
        descriptorEntry: String = "plugin.yml",
        generatedClasses: List<String> = emptyList(),
        generatedMajorVersion: Int = 65,
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): Path {
        val target = Files.createTempFile(jarName, ".jar")
        JarOutputStream(Files.newOutputStream(target)).use { out ->
            if (descriptor != null) {
                out.putNextEntry(JarEntry(descriptorEntry))
                out.write(descriptor.toByteArray())
                out.closeEntry()
            }
            for (resource in packagePaths.flatMap { classResources(it) }) {
                out.putNextEntry(JarEntry(resource))
                out.write(readResource(resource))
                out.closeEntry()
            }
            for (internalName in generatedClasses) {
                out.putNextEntry(JarEntry("$internalName.class"))
                out.write(emptyClass(internalName, generatedMajorVersion))
                out.closeEntry()
            }
            for ((name, bytes) in extraEntries) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        target.toFile().deleteOnExit()
        return target
    }

    fun descriptor(
        name: String,
        main: String,
        apiVersion: String?,
        extraLines: List<String> = emptyList(),
    ): String = buildString {
        appendLine("name: $name")
        appendLine("version: 1.0.0")
        appendLine("main: $main")
        if (apiVersion != null) appendLine("api-version: '$apiVersion'")
        extraLines.forEach { appendLine(it) }
    }

    fun emptyClass(
        internalName: String,
        majorVersion: Int,
        extraMethodName: String? = null,
        callOwner: String? = null,
        callMethod: String = "apiCall",
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(majorVersion, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            if (callOwner != null) {
                val label = org.objectweb.asm.Label()
                visitLabel(label)
                visitLineNumber(42, label)
                visitMethodInsn(Opcodes.INVOKESTATIC, callOwner, callMethod, "()V", false)
            }
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        if (extraMethodName != null) {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, extraMethodName, "()V", null, null).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun without(bytes: ByteArray, methodNames: Set<String>): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? = if (name in methodNames) {
                    null
                } else {
                    super.visitMethod(access, name, descriptor, signature, exceptions)
                }
            },
            0,
        )
        return writer.toByteArray()
    }

    private fun classResources(packagePath: String): List<String> {
        val url = requireNotNull(javaClass.classLoader.getResource(packagePath)) {
            "Fixture package $packagePath is not on the test classpath"
        }
        return Files.list(Paths.get(url.toURI())).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith(".class") }
                .sorted()
                .toList()
                .map { packagePath + it }
        }
    }

    private fun readResource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "Missing compiled fixture $name" }
            .use { it.readBytes() }
}
