package com.salyvn.pluginscope.core

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Reads a jar in one pass: class metadata, resources, descriptors, manifest, and optionally every
 * reference the code makes to a chosen set of owners.
 *
 * Nothing is loaded into the JVM and no plugin code runs, which is the point — these jars are
 * routinely third-party binaries a server owner has not built themselves.
 */
object JarReader {

    /**
     * @param collectUsesFor when non-null, method and field references whose owner matches the
     *   predicate are recorded in [JarInspection.memberUses]. When null the bytecode of method
     *   bodies is skipped entirely, which is roughly three times faster.
     */
    fun read(path: Path, collectUsesFor: ((owner: String) -> Boolean)? = null): JarInspection {
        val classes = mutableListOf<ClassEntry>()
        val resources = mutableListOf<ResourceEntry>()
        val descriptors = mutableMapOf<DescriptorKind, PluginDescriptor>()
        val uses = mutableListOf<MemberUse>()
        val errors = mutableListOf<String>()
        var manifest = emptyMap<String, String>()

        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().toList()
            for (entry in entries) {
                if (entry.isDirectory) {
                    resources += ResourceEntry(entry.name, 0, 0, isDirectory = true)
                    continue
                }
                when {
                    entry.name.endsWith(".class") -> {
                        try {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            classes += readClass(bytes, entry.name, entry.size, collectUsesFor, uses)
                        } catch (e: Exception) {
                            errors += "${entry.name}: unreadable class (${e::class.simpleName}: ${e.message})"
                            resources += ResourceEntry(entry.name, entry.size, entry.compressedSize, false)
                        }
                    }

                    entry.name == DescriptorKind.LEGACY.entryName || entry.name == DescriptorKind.PAPER.entryName -> {
                        val kind = if (entry.name == DescriptorKind.PAPER.entryName) DescriptorKind.PAPER else DescriptorKind.LEGACY
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        descriptors[kind] = DescriptorParser.parse(kind, bytes)
                        resources += ResourceEntry(entry.name, entry.size, entry.compressedSize, false)
                    }

                    entry.name == "META-INF/MANIFEST.MF" -> {
                        manifest = readManifest(zip.getInputStream(entry).use { it.readBytes() })
                        resources += ResourceEntry(entry.name, entry.size, entry.compressedSize, false)
                    }

                    else -> resources += ResourceEntry(entry.name, entry.size, entry.compressedSize, false)
                }
            }
        }

        return JarInspection(
            path = path.toAbsolutePath().normalize().toString(),
            fileName = path.fileName.toString(),
            sizeBytes = path.toFile().length(),
            classes = classes,
            resources = resources,
            descriptors = descriptors,
            memberUses = uses,
            readErrors = errors,
            manifest = manifest,
        )
    }

    private fun readManifest(bytes: ByteArray): Map<String, String> =
        bytes.decodeToString().lineSequence()
            .filter { it.contains(':') && !it.startsWith(" ") }
            .associate { line -> line.substringBefore(':').trim() to line.substringAfter(':').trim() }

    private fun readClass(
        bytes: ByteArray,
        entryName: String,
        sizeBytes: Long,
        collectUsesFor: ((String) -> Boolean)?,
        sink: MutableList<MemberUse>,
    ): ClassEntry {
        val collector = ClassCollector(entryName, sizeBytes, collectUsesFor, sink)
        val parsingOptions = if (collectUsesFor == null) {
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
        } else {
            ClassReader.SKIP_FRAMES
        }
        ClassReader(bytes).accept(collector, parsingOptions)
        return collector.build()
    }

    private class ClassCollector(
        private val entryName: String,
        private val sizeBytes: Long,
        private val collectUsesFor: ((String) -> Boolean)?,
        private val sink: MutableList<MemberUse>,
    ) : ClassVisitor(Opcodes.ASM9) {

        private lateinit var name: String
        private var majorVersion = 0
        private var superName: String? = null
        private var interfaces: List<String> = emptyList()
        private var access = 0
        private var sourceFile: String? = null
        private var isKotlin = false
        private var deprecated = false
        private val methods = mutableSetOf<String>()
        private val fields = mutableSetOf<String>()
        private val deprecatedMembers = mutableSetOf<String>()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            this.majorVersion = version and 0xFFFF
            this.access = access
            this.name = name
            this.superName = superName
            this.interfaces = interfaces?.toList() ?: emptyList()
            this.deprecated = access and Opcodes.ACC_DEPRECATED != 0
        }

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean) = null.also {
            when (descriptor) {
                "Lkotlin/Metadata;" -> isKotlin = true
                "Ljava/lang/Deprecated;" -> deprecated = true
            }
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            val key = "$name:$descriptor"
            fields += key
            if (access and Opcodes.ACC_DEPRECATED != 0) deprecatedMembers += key
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(desc: String, visible: Boolean) = null.also {
                    if (desc == "Ljava/lang/Deprecated;") deprecatedMembers += key
                }
            }
        }

        override fun visitMethod(
            access: Int,
            methodName: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val key = methodName + descriptor
            methods += key
            if (access and Opcodes.ACC_DEPRECATED != 0) deprecatedMembers += key
            val predicate = collectUsesFor
            return object : MethodVisitor(Opcodes.ASM9) {
                private var line: Int? = null

                override fun visitAnnotation(desc: String, visible: Boolean) = null.also {
                    if (desc == "Ljava/lang/Deprecated;") deprecatedMembers += key
                }

                override fun visitLineNumber(newLine: Int, start: org.objectweb.asm.Label?) {
                    line = newLine
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    insnName: String,
                    insnDescriptor: String,
                    isInterface: Boolean,
                ) {
                    if (predicate != null && predicate(owner)) {
                        sink += MemberUse(owner, insnName, insnDescriptor, UseKind.METHOD, name, key, line)
                    }
                }

                override fun visitFieldInsn(opcode: Int, owner: String, insnName: String, insnDescriptor: String) {
                    if (predicate != null && predicate(owner)) {
                        sink += MemberUse(owner, insnName, insnDescriptor, UseKind.FIELD, name, key, line)
                    }
                }

                override fun visitTypeInsn(opcode: Int, type: String) {
                    if (predicate != null && predicate(type)) {
                        sink += MemberUse(type, type, "", UseKind.TYPE, name, key, line)
                    }
                }

                /**
                 * Kotlin lambdas, method references and `invokedynamic`-desugared calls carry the
                 * real target in the bootstrap arguments, so they are followed too. Without this a
                 * call made only from inside a lambda is invisible.
                 */
                override fun visitInvokeDynamicInsn(
                    insnName: String,
                    insnDescriptor: String,
                    handle: Handle?,
                    vararg args: Any?,
                ) {
                    if (predicate == null) return
                    for (arg in args) {
                        if (arg is Handle && predicate(arg.owner)) {
                            sink += MemberUse(arg.owner, arg.name, arg.desc, UseKind.METHOD, name, key, line)
                        }
                    }
                }
            }
        }

        fun build() = ClassEntry(
            internalName = name,
            entryName = entryName,
            majorVersion = majorVersion,
            superName = superName,
            interfaces = interfaces,
            access = access,
            sourceFile = sourceFile,
            sizeBytes = sizeBytes,
            isKotlin = isKotlin,
            methods = methods,
            fields = fields,
            deprecated = deprecated,
            deprecatedMembers = deprecatedMembers,
        )
    }
}
