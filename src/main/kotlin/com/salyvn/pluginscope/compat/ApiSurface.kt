package com.salyvn.pluginscope.compat

import com.salyvn.pluginscope.core.ClassEntry
import com.salyvn.pluginscope.core.JarReader
import com.salyvn.pluginscope.core.UseKind
import java.nio.file.Path

/** Outcome of looking a reference up in an API surface. */
enum class Resolution {
    /** The member exists on the owner or on one of its ancestors. */
    FOUND,

    /** The owner exists, its ancestry is fully known, and the member is nowhere in it. */
    MISSING_MEMBER,

    /** The owner itself is not part of the surface. */
    MISSING_CLASS,

    /**
     * The owner exists but part of its ancestry is outside the surface (a JDK type, a library the
     * surface does not include), so absence cannot be proven. Never reported as a defect.
     */
    UNKNOWN,
}

/**
 * The set of types and members one or more API jars expose, as read from their class files.
 *
 * This is deliberately a *closed-world* index: a member is only ever declared missing when the
 * whole inheritance chain of its owner is present, otherwise the answer is [Resolution.UNKNOWN].
 * That trade turns silence into the default and keeps the tool usable on real jars, where nobody
 * ever passes the complete transitive classpath.
 */
class ApiSurface(
    val label: String,
    private val classes: Map<String, ClassEntry>,
) {
    val size: Int get() = classes.size

    fun hasClass(internalName: String): Boolean = internalName in classes

    fun classAt(internalName: String): ClassEntry? = classes[internalName]

    fun resolve(owner: String, memberKey: String, kind: UseKind): Resolution {
        if (kind == UseKind.TYPE) return if (hasClass(owner)) Resolution.FOUND else Resolution.MISSING_CLASS
        classes[owner] ?: return Resolution.MISSING_CLASS

        var ancestryComplete = true
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(owner))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            val entry = classes[current]
            if (entry == null) {
                // java/lang/Object and other roots outside the surface are expected; anything else
                // means the chain is truncated and absence is not provable.
                if (current != "java/lang/Object") ancestryComplete = false
                continue
            }
            val found = when (kind) {
                UseKind.METHOD -> memberKey in entry.methods
                UseKind.FIELD -> memberKey in entry.fields
                UseKind.TYPE -> true
            }
            if (found) return Resolution.FOUND
            entry.superName?.let { queue.addLast(it) }
            queue.addAll(entry.interfaces)
        }
        // Methods inherited from Object (toString, equals, hashCode…) always resolve at runtime.
        if (kind == UseKind.METHOD && memberKey in OBJECT_METHODS) return Resolution.FOUND
        return if (ancestryComplete) Resolution.MISSING_MEMBER else Resolution.UNKNOWN
    }

    /** True when the member itself, or its owner, is marked deprecated in this surface. */
    fun isDeprecated(owner: String, memberKey: String): Boolean {
        val entry = classes[owner] ?: return false
        return entry.deprecated || memberKey in entry.deprecatedMembers
    }

    companion object {
        private val OBJECT_METHODS = setOf(
            "toString()Ljava/lang/String;", "hashCode()I", "equals(Ljava/lang/Object;)Z",
            "getClass()Ljava/lang/Class;", "notify()V", "notifyAll()V", "wait()V", "wait(J)V",
            "wait(JI)V", "clone()Ljava/lang/Object;", "finalize()V",
        )

        /** Reads every class of every jar into one surface. Later jars do not override earlier ones. */
        fun fromJars(paths: List<Path>, label: String): ApiSurface {
            val merged = LinkedHashMap<String, ClassEntry>()
            for (path in paths) {
                val inspection = JarReader.read(path)
                for (entry in inspection.classes) merged.putIfAbsent(entry.internalName, entry)
            }
            return ApiSurface(label, merged)
        }
    }
}
