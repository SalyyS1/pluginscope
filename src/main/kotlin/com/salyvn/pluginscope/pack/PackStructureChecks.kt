package com.salyvn.pluginscope.pack

import com.salyvn.pluginscope.core.FindingSink

/**
 * The format a pack is compared against, or the reason no comparison is possible. Keeping the reason
 * in the type is what lets a run with no network still say what it skipped instead of silently
 * dropping two checks.
 */
internal sealed interface Target {

    data class Known(val format: Int, val describedAs: String) : Target

    data class Unknown(val reason: String) : Target
}

/**
 * Checks on `pack.mcmeta`: whether the client would accept the file at all, whether the formats it
 * declares are shaped as the schema requires, whether they match the target, and whether each
 * overlay can ever apply.
 *
 * Field shapes verified against SpyglassMC/vanilla-mcdoc java/pack.mcdoc:12,16,20,24 and :58-67.
 */
internal object PackStructureChecks {

    /** Returns the parsed metadata, or null when the client would reject the pack outright. */
    fun readMeta(source: PackSource, sink: FindingSink): PackMeta? {
        val bytes = source.read(PackMeta.PATH)
        if (bytes == null) {
            // A pack zipped by right-clicking the pack folder has everything one level too deep.
            // That is by far the most common cause, and the fix differs from a genuinely absent file.
            val nested = source.paths
                .filter { it.endsWith("/${PackMeta.PATH}") && it.count { c -> c == '/' } == 1 }
                .sorted()
            sink.add(
                PackRules.NO_MCMETA,
                location = source.label,
                detail = if (nested.isEmpty()) {
                    "No ${PackMeta.PATH} anywhere in the pack."
                } else {
                    "${PackMeta.PATH} is one directory below the root, so the pack root is that " +
                        "directory and not the archive root."
                },
                evidence = nested,
            )
            return null
        }

        return PackMeta.parse(bytes).getOrElse { failure ->
            sink.add(
                PackRules.MCMETA_UNPARSEABLE,
                location = PackMeta.PATH,
                detail = "The file cannot be read: " +
                    (failure.message?.lineSequence()?.first()?.trim() ?: failure::class.simpleName),
            )
            null
        }
    }

    /**
     * @param minFormatBoundary the format 1.21.9 shipped for this kind of pack, which is where
     *   `supported_formats` stopped being allowed. It differs between resource and data packs, so the
     *   caller passes the one that matches what the pack actually carries.
     */
    fun checkFormats(meta: PackMeta, target: Target, minFormatBoundary: Int, sink: FindingSink) {
        if (meta.malformedFields.isNotEmpty()) {
            sink.add(
                PackRules.FORMAT_VALUE_MALFORMED,
                location = PackMeta.PATH,
                detail = "${meta.malformedFields.size} field(s) in `pack` hold a value of a shape " +
                    "the client cannot read, so it falls back to treating them as absent.",
                evidence = meta.malformedFields.map { (key, written) -> "$key: $written" },
            )
        }

        if ("pack_format" !in meta.presentKeys && "min_format" !in meta.presentKeys) {
            sink.add(
                PackRules.NO_FORMAT_DECLARED,
                location = PackMeta.PATH,
                detail = "`pack` declares neither `pack_format` nor `min_format`.",
                evidence = listOf("keys present: " + meta.presentKeys.sorted().joinToString(", ").ifEmpty { "none" }),
            )
        }

        if (meta.supportedFormats != null && meta.minFormat != null && meta.minFormat.major >= minFormatBoundary) {
            sink.add(
                PackRules.SUPPORTED_FORMATS_WITH_MIN_FORMAT,
                location = PackMeta.PATH,
                detail = "`supported_formats` is ${meta.supportedFormats} and `min_format` is " +
                    "${meta.minFormat}, which is format $minFormatBoundary or later.",
                evidence = listOf("`supported_formats` must not be present alongside such a `min_format`"),
            )
        }

        checkAgainstTarget(meta, target, sink)
    }

    private fun checkAgainstTarget(meta: PackMeta, target: Target, sink: FindingSink) {
        if (target is Target.Unknown) {
            sink.add(
                PackRules.TARGET_UNKNOWN,
                location = PackMeta.PATH,
                detail = target.reason,
                evidence = listOf("skipped: ${PackRules.FORMAT_MISMATCH.id}, ${PackRules.RANGE_MISSES_TARGET.id}"),
            )
            return
        }

        val known = target as Target.Known
        val declared = meta.declaredRange ?: return
        if (declared.covers(known.format)) return

        // A pack that names one format and a pack that names a range fail differently: the first is
        // applied with a version warning, the second is listed as incompatible.
        if (declared.min == declared.max) {
            sink.add(
                PackRules.FORMAT_MISMATCH,
                location = PackMeta.PATH,
                detail = "The pack declares format ${declared.min} and the target is format " +
                    "${known.format} (${known.describedAs}).",
            )
        } else {
            sink.add(
                PackRules.RANGE_MISSES_TARGET,
                location = PackMeta.PATH,
                detail = "The pack declares formats $declared and the target is format " +
                    "${known.format} (${known.describedAs}).",
            )
        }
    }

    /**
     * An overlay applies when the client's format falls in the overlay's range and the directory it
     * names is present. Either half missing makes the overlay dead weight.
     */
    fun checkOverlays(source: PackSource, meta: PackMeta, sink: FindingSink) {
        val declared = meta.declaredRange
        val dead = meta.overlays.mapNotNull { overlay ->
            val at = "entries[${overlay.position}]"
            val directory = overlay.directory
            when {
                directory.isNullOrBlank() -> "$at declares no directory"
                directory !in source.directories -> "$at names directory \"$directory\", which the pack does not contain"
                overlay.range != null && declared != null && !overlay.range.overlaps(declared) ->
                    "$at covers formats ${overlay.range}, outside the pack's declared $declared"

                else -> null
            }
        }
        if (dead.isEmpty()) return
        sink.add(
            PackRules.OVERLAY_NEVER_APPLIES,
            location = PackMeta.PATH,
            detail = "${dead.size} of ${meta.overlays.size} overlay entr" +
                (if (meta.overlays.size == 1) "y" else "ies") + " can never apply.",
            evidence = dead,
        )
    }
}
