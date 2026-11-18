package com.salyvn.pluginscope.core

/** One `.class` entry inside a jar, as read by ASM without loading or running it. */
data class ClassEntry(
    /** Internal name, e.g. `com/example/plugin/Main`. */
    val internalName: String,
    val entryName: String,
    /** Class-file major version: 52 = Java 8, 61 = Java 17, 65 = Java 21. */
    val majorVersion: Int,
    val superName: String?,
    val interfaces: List<String>,
    val access: Int,
    val sourceFile: String?,
    val sizeBytes: Long,
    /** True when the class carries `kotlin.Metadata`, i.e. it was produced by kotlinc. */
    val isKotlin: Boolean,
    /** Declared methods as `name+descriptor`, used when this jar is treated as an API surface. */
    val methods: Set<String>,
    /** Declared fields as `name:descriptor`. */
    val fields: Set<String>,
    val deprecated: Boolean,
    /** Methods and fields carrying `@Deprecated`, same encoding as [methods] / [fields]. */
    val deprecatedMembers: Set<String>,
    /** NMS and CraftBukkit string literals discovered in LDC constant pool instructions. */
    val stringConstants: Set<String> = emptySet(),
) {
    val packageName: String get() = internalName.substringBeforeLast('/', "")

    /** True if this class entry resides under META-INF/versions/ (Multi-Release JAR, JEP 238). */
    val isMultiRelease: Boolean
        get() = entryName.startsWith("META-INF/versions/")

    /** Target Java major version from META-INF/versions/<ver>/, e.g. 17 or 21. */
    val multiReleaseVersion: Int?
        get() {
            if (!isMultiRelease) return null
            val rest = entryName.removePrefix("META-INF/versions/")
            return rest.substringBefore('/').toIntOrNull()
        }

    /** Path without META-INF/versions/<ver>/ prefix, e.g. "org/example/Foo.class". */
    val normalizedClassPath: String
        get() = if (isMultiRelease) {
            entryName.removePrefix("META-INF/versions/").substringAfter('/')
        } else {
            entryName
        }

    /**
     * True when this class represents a Bukkit custom event: it either inherits directly from
     * `org/bukkit/event/Event` or implements the standard `static HandlerList getHandlerList()` contract.
     */
    val isBukkitEvent: Boolean
        get() = superName == "org/bukkit/event/Event" ||
            superName == "org.bukkit.event.Event" ||
            methods.any { it.startsWith("getHandlerList()Lorg/bukkit/event/HandlerList;") }
}

/** A non-class jar entry. Sizes come from the zip directory, so no decompression is needed. */
data class ResourceEntry(
    val entryName: String,
    val sizeBytes: Long,
    val compressedSizeBytes: Long,
    val isDirectory: Boolean,
)

enum class UseKind { METHOD, FIELD, TYPE }

/**
 * One reference from code inside the jar to something outside the class doing the referencing.
 * [line] is present when the class carries a LineNumberTable, which javac and kotlinc both emit
 * by default, so findings can point at a source line rather than a class.
 */
data class MemberUse(
    val owner: String,
    val name: String,
    val descriptor: String,
    val kind: UseKind,
    val fromClass: String,
    val fromMethod: String,
    val line: Int?,
) {
    /** Matches the encoding used by [ClassEntry.methods] / [ClassEntry.fields]. */
    val memberKey: String get() = when (kind) {
        UseKind.METHOD -> name + descriptor
        UseKind.FIELD -> "$name:$descriptor"
        UseKind.TYPE -> name
    }

    fun render(): String = when (kind) {
        UseKind.METHOD -> "${owner.replace('/', '.')}#$name$descriptor"
        UseKind.FIELD -> "${owner.replace('/', '.')}#$name"
        UseKind.TYPE -> owner.replace('/', '.')
    }
}

/**
 * A string literal discovered in LDC constant pool instructions, targeting NMS or CraftBukkit internals.
 */
data class StringLiteralUse(
    val value: String,
    val fromClass: String,
    val fromMethod: String,
    val line: Int?,
) {
    fun render(): String {
        val loc = if (line != null) ":$line" else ""
        return "${fromClass.replace('/', '.')}#$fromMethod$loc: \"$value\""
    }
}

/** Everything read out of one jar in a single pass. */
data class JarInspection(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val classes: List<ClassEntry>,
    val resources: List<ResourceEntry>,
    val descriptors: Map<DescriptorKind, PluginDescriptor>,
    val memberUses: List<MemberUse>,
    /** Service provider definitions under `META-INF/services/`: interface to implementation classes. */
    val serviceProviders: Map<String, List<String>> = emptyMap(),
    /** Entries that could not be parsed. A corrupt class is a finding, not a crash. */
    val readErrors: List<String>,
    val manifest: Map<String, String>,
    val stringLiteralUses: List<StringLiteralUse> = emptyList(),
) {
    /** True if this jar declares Multi-Release: true in its manifest or carries META-INF/versions/ classes. */
    val isMultiReleaseJar: Boolean
        get() = manifest["Multi-Release"]?.equals("true", ignoreCase = true) == true ||
            classes.any { it.isMultiRelease }

    val multiReleaseClasses: List<ClassEntry>
        get() = classes.filter { it.isMultiRelease }

    /** paper-plugin.yml wins when both are present, which is what the server does. */
    val descriptor: PluginDescriptor?
        get() = descriptors[DescriptorKind.PAPER] ?: descriptors[DescriptorKind.LEGACY]

    val isPlugin: Boolean get() = descriptors.isNotEmpty()

    private val byName: Map<String, ClassEntry> by lazy { classes.associateBy { it.internalName } }

    fun classAt(internalName: String): ClassEntry? = byName[internalName]

    fun contains(internalName: String): Boolean = internalName in byName

    val maxMajorVersion: Int? get() = classes.maxOfOrNull { it.majorVersion }

    /**
     * The package the plugin's own code lives in, derived from the main class and capped at three
     * segments. Shading relocates dependencies *under* this root by convention
     * (`com.example.plugin.libs.foo`), so anything outside it was shipped unrelocated.
     */
    val ownPackageRoot: String? by lazy {
        val main = descriptor?.mainInternalName ?: return@lazy inferredRootFromClasses()
        val segments = main.split('/').dropLast(1)
        // A main class in the default package tells us nothing, so fall back to what the jar
        // itself suggests rather than treating every class as somebody else's code.
        if (segments.isEmpty()) return@lazy inferredRootFromClasses()
        segments.take(3).joinToString("/")
    }

    private fun inferredRootFromClasses(): String? {
        val roots = classes.asSequence()
            .map { it.internalName.split('/').take(3).joinToString("/") }
            .groupingBy { it }.eachCount()
        return roots.maxByOrNull { it.value }?.key
    }

    fun isOwnCode(internalName: String): Boolean {
        val root = ownPackageRoot ?: return false
        return internalName.startsWith("$root/") || internalName == root
    }

    /** Top-level package groups of classes not under [ownPackageRoot], with class count and bytes. */
    fun bundledPackages(depth: Int = 3): List<BundledPackage> = classes
        .asSequence()
        .filterNot { isOwnCode(it.internalName) }
        .groupBy { it.internalName.split('/').let { p -> p.take(minOf(depth, maxOf(1, p.size - 1))).joinToString("/") } }
        .map { (root, entries) -> BundledPackage(root, entries.size, entries.sumOf { it.sizeBytes }) }
        .sortedByDescending { it.sizeBytes }
}

data class BundledPackage(val root: String, val classCount: Int, val sizeBytes: Long) {
    val dotted: String get() = root.replace('/', '.')
}
