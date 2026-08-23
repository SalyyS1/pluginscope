package com.salyvn.pluginscope.pack

import com.salyvn.pluginscope.core.BuildInfo
import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.ScanReport
import java.io.IOException
import java.nio.file.Path

/** What the command line asked for, resolved into the inputs the checks need. */
internal data class PackAuditOptions(
    val mcVersion: String? = null,
    val packFormat: Int? = null,
    /** Other packs the server stacks on top of this one, for the collision check. */
    val against: List<Path> = emptyList(),
    val allowNetwork: Boolean = true,
    val cacheDir: Path = PackFormatCatalog.defaultCacheDir(),
)

/**
 * Runs every pack check and assembles the report.
 *
 * The order matters in one place only: the pack index is built before the format checks, because
 * whether `supported_formats` is still allowed depends on which kind of pack this is, and that is
 * decided by which top-level tree the pack carries.
 */
internal class PackValidator(
    private val options: PackAuditOptions,
    private val log: (String) -> Unit = {},
) {

    fun validate(pack: Path): ScanReport = PackSource.open(pack).use { source ->
        val sink = FindingSink()
        val index = PackIndex(source)
        val assets = JsonAssets(source)
        val resolver = ReferenceResolver(index)

        val meta = PackStructureChecks.readMeta(source, sink)
        val target = resolveTarget(index.kind)
        if (meta != null) {
            PackStructureChecks.checkFormats(meta, target, minFormatBoundary(index.kind), sink)
            PackStructureChecks.checkOverlays(source, meta, sink)
        }

        val references = PackReferenceChecks.check(index, resolver, assets, meta?.effectiveFormat, sink)
        PackAssetChecks.check(index, resolver, assets, sink)
        PackHygieneChecks.check(index, assets, sink)
        checkCollisions(index, sink)
        assets.reportUnreadable(sink)

        ScanReport(
            tool = "${BuildInfo.NAME} pack",
            toolVersion = BuildInfo.version,
            subject = source.label,
            subjectKind = index.kind.label,
            findings = sink.findings(),
            facts = facts(source, index, meta, target, references, resolver),
        )
    }

    /**
     * `--pack-format` wins because it needs nothing but itself, which is what makes an offline run
     * complete rather than degraded. A version lookup that fails leaves the target unknown: the two
     * checks that need it say so, and every other check still runs.
     */
    private fun resolveTarget(kind: PackKind): Target {
        options.packFormat?.let { return Target.Known(it, "--pack-format $it") }
        val version = options.mcVersion
            ?: return Target.Unknown("No target was given, so nothing was compared against one.")
        return try {
            val formats = PackFormatCatalog.lookup(version, options.cacheDir, options.allowNetwork, log)
            val data = kind == PackKind.DATA
            val format = if (data) formats.dataPack else formats.resourcePack
            Target.Known(format.major, "Minecraft ${formats.id}, ${if (data) "data" else "resource"} pack format $format")
        } catch (e: IOException) {
            Target.Unknown("The pack format of '$version' could not be determined: ${e.message}")
        }
    }

    private fun minFormatBoundary(kind: PackKind): Int = if (kind == PackKind.DATA) {
        PackFormatCatalog.DATA_PACK_FORMAT_1_21_9
    } else {
        PackFormatCatalog.RESOURCE_PACK_FORMAT_1_21_9
    }

    /**
     * Two packs that define the same item definition do not merge; the one applied last replaces the
     * other outright. Only the ids in common are reported, since everything else stacks fine.
     */
    private fun checkCollisions(index: PackIndex, sink: FindingSink) {
        if (options.against.isEmpty()) return
        val mine = index.assetEntries(ItemDefinitions.FOLDER, ".json").keys
        if (mine.isEmpty()) return
        for (other in options.against) {
            val collisions = try {
                PackSource.open(other).use { source ->
                    val theirs = PackIndex(source).assetEntries(ItemDefinitions.FOLDER, ".json").keys
                    source.label to mine.filter { it in theirs }.map { it.toString() }.sorted()
                }
            } catch (e: IOException) {
                log("Cannot read $other for the collision check: ${e.message}")
                continue
            }
            if (collisions.second.isEmpty()) continue
            sink.add(
                PackRules.ITEM_DEFINITION_COLLISION,
                location = index.source.label,
                detail = "${collisions.second.size} item definition(s) are also defined by " +
                    "${collisions.first}, and whichever pack applies last wins.",
                evidence = collisions.second.take(8),
            )
        }
    }

    private fun facts(
        source: PackSource,
        index: PackIndex,
        meta: PackMeta?,
        target: Target,
        references: ReferenceFacts,
        resolver: ReferenceResolver,
    ): Map<String, String> = buildMap {
        put("container", source.kind)
        put(
            "entries",
            String.format(
                java.util.Locale.ROOT,
                "%d (%.2f MiB)",
                source.entries.size,
                source.entries.sumOf { it.sizeBytes } / 1024.0 / 1024.0,
            ),
        )
        put("asset namespaces", index.assetNamespaces.sorted().joinToString(", ").ifEmpty { "(none)" })
        if (index.dataNamespaces.isNotEmpty()) {
            put("data namespaces", index.dataNamespaces.sorted().joinToString(", "))
        }
        put("declared format", meta?.let { describe(it) } ?: "(none)")
        put(
            "target format",
            when (target) {
                is Target.Known -> "${target.format} (${target.describedAs})"
                is Target.Unknown -> "(unknown)"
            },
        )
        put("models", references.models.toString())
        put("item definitions", references.itemDefinitions.toString())
        put("references", "${resolver.checked} checked, ${resolver.expectedFromVanilla} expected from vanilla")
        if (resolver.externalNamespaces.isNotEmpty()) {
            put("references into other packs", resolver.externalNamespaces.sorted().joinToString(", "))
        }
        // Pack descriptions usually carry legacy section-sign colour codes. They are display markup
        // and printing them raw turns the fact line into mojibake on a non-UTF-8 console.
        meta?.description?.let { put("description", it.replace(SECTION_CODE, "").trim()) }
    }

    private companion object {
        /** Built from the code point so this source file stays pure ASCII. */
        val SECTION_CODE = Regex("${Char(0xA7)}.", RegexOption.DOT_MATCHES_ALL)
    }

    private fun describe(meta: PackMeta): String = buildList {
        meta.packFormat?.let { add("pack_format $it") }
        meta.minFormat?.let { add("min_format $it") }
        meta.maxFormat?.let { add("max_format $it") }
        meta.supportedFormats?.let { add("supported_formats $it") }
    }.joinToString(", ").ifEmpty { "(none)" }
}
