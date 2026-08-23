package com.salyvn.pluginscope.jar

import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection

/** Entry-level checks: what is in the zip that should not have shipped. */
internal object HygieneChecks {

    private val SOURCE_EXTENSIONS = setOf(".java", ".kt", ".scala", ".groovy")
    private val JUNK_NAMES = setOf(".ds_store", "thumbs.db", "desktop.ini", ".gitignore", ".gitattributes")
    private val SIGNATURE_EXTENSIONS = setOf(".sf", ".dsa", ".rsa", ".ec")

    fun run(jar: JarInspection, sink: FindingSink) {
        val files = jar.resources.filterNot { it.isDirectory }

        val nested = files.filter { it.entryName.endsWith(".jar", ignoreCase = true) }
        if (nested.isNotEmpty()) {
            sink.add(
                JarRules.JAR_HYGIENE,
                location = "nested jars",
                detail = "${nested.size} jar file(s) inside the jar. Bukkit does not read them, so any " +
                    "code in there is either dead or loaded by a custom loader.",
                evidence = nested.take(4).map { "${it.entryName} (${it.sizeBytes / 1024} KiB)" },
            )
        }

        val sources = files.filter { entry -> SOURCE_EXTENSIONS.any { entry.entryName.endsWith(it, true) } }
        if (sources.isNotEmpty()) {
            sink.add(
                JarRules.JAR_HYGIENE,
                location = "source files",
                detail = "${sources.size} source file(s) shipped in the release jar.",
                evidence = sources.take(4).map { it.entryName },
            )
        }

        val junk = files.filter { entry -> entry.entryName.substringAfterLast('/').lowercase() in JUNK_NAMES }
        if (junk.isNotEmpty()) {
            sink.add(
                JarRules.JAR_HYGIENE,
                location = "editor and VCS files",
                detail = "${junk.size} file(s) that only exist because they sat in the source tree.",
                evidence = junk.take(4).map { it.entryName },
            )
        }

        val mavenMetadata = files.filter { it.entryName.startsWith("META-INF/maven/") }
        if (mavenMetadata.isNotEmpty()) {
            sink.add(
                JarRules.JAR_HYGIENE,
                location = "META-INF/maven",
                detail = "${mavenMetadata.size} build-metadata file(s) from shaded dependencies.",
                evidence = mavenMetadata.map { it.entryName.removePrefix("META-INF/maven/").substringBeforeLast('/') }
                    .distinct().take(4),
            )
        }

        val multiRelease = files.filter { it.entryName.startsWith("META-INF/versions/") }
        if (multiRelease.isNotEmpty()) {
            val releases = multiRelease.mapNotNull { it.entryName.removePrefix("META-INF/versions/").substringBefore('/').toIntOrNull() }
                .distinct().sorted()
            sink.add(
                JarRules.JAR_HYGIENE,
                location = "META-INF/versions",
                detail = "Multi-release jar with overrides for Java ${releases.joinToString(", ")}. " +
                    "The plugin classloader reads them only if the jar declares Multi-Release: true.",
                evidence = listOf("Multi-Release manifest attribute: ${jar.manifest["Multi-Release"] ?: "absent"}"),
            )
        }

        val signatures = files.filter { entry ->
            entry.entryName.startsWith("META-INF/") &&
                SIGNATURE_EXTENSIONS.any { entry.entryName.endsWith(it, ignoreCase = true) }
        }
        if (signatures.isNotEmpty()) {
            sink.add(
                JarRules.SIGNATURE_LEFTOVER,
                location = "META-INF signatures",
                detail = "${signatures.size} signature file(s) present. If any class was added or " +
                    "rewritten after signing, class loading fails with SecurityException.",
                evidence = signatures.take(4).map { it.entryName },
            )
        }

        jar.manifest["Class-Path"]?.let { classPath ->
            sink.add(
                JarRules.JAR_HYGIENE,
                location = "META-INF/MANIFEST.MF",
                detail = "Class-Path is set but the plugin classloader ignores it.",
                evidence = listOf("Class-Path: ${classPath.take(120)}"),
            )
        }

        val heavy = files.filter { it.sizeBytes > LARGE_RESOURCE_BYTES }
        if (heavy.isNotEmpty()) {
            sink.add(
                JarRules.JAR_HYGIENE,
                location = "large resources",
                detail = "${heavy.size} resource(s) over ${LARGE_RESOURCE_BYTES / 1024 / 1024} MiB each.",
                evidence = heavy.sortedByDescending { it.sizeBytes }.take(4)
                    .map { "${it.entryName} (${it.sizeBytes / 1024 / 1024} MiB)" },
            )
        }

        if (jar.readErrors.isNotEmpty()) {
            sink.add(
                JarRules.JAR_HYGIENE,
                location = "unreadable entries",
                detail = "${jar.readErrors.size} entry(ies) could not be parsed as class files.",
                evidence = jar.readErrors.take(4),
            )
        }
    }

    private const val LARGE_RESOURCE_BYTES = 4L * 1024 * 1024
}
