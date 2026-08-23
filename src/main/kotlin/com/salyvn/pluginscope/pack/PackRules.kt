package com.salyvn.pluginscope.pack

import com.salyvn.pluginscope.core.Rule
import com.salyvn.pluginscope.core.Severity

/**
 * Every check the pack validator can report. [Rule.provenance] names the source the underlying fact
 * came from, because a pack validator that cannot be audited is just an opinion.
 *
 * The reference-integrity rules exist because the server has no way to tell you about them. On a
 * client that loaded the pack and then fell back to the missing-texture placeholder, the server sees
 * `SUCCESSFULLY_LOADED`: PlayerResourcePackStatusEvent.Status has eight values
 * (SUCCESSFULLY_LOADED, DECLINED, FAILED_DOWNLOAD, ACCEPTED, DOWNLOADED, INVALID_URL, FAILED_RELOAD,
 * DISCARDED) and not one of them distinguishes a pack that applied from a pack that applied badly.
 */
internal object PackRules {

    private const val PACK_STATUS_BLIND =
        "The server cannot detect this at runtime. PlayerResourcePackStatusEvent.Status reports " +
            "SUCCESSFULLY_LOADED for a client that applied the pack and then drew the " +
            "missing-texture placeholder, so a broken reference reaches players silently."

    private const val PACK_STATUS_SOURCE =
        "PaperMC/Paper paper-api PlayerResourcePackStatusEvent.Status, eight values, none of which " +
            "signals a partial load"

    private const val MCDOC_PACK = "SpyglassMC/vanilla-mcdoc java/pack.mcdoc"

    val NO_MCMETA = Rule(
        id = "P001",
        severity = Severity.BLOCKER,
        title = "No pack.mcmeta at the pack root",
        rationale = "A pack without pack.mcmeta beside its assets directory is not a pack. The " +
            "client lists it as invalid and never applies it.",
        provenance = "Vanilla pack layout: pack.mcmeta sits at the root next to assets/ " +
            "(verified against the extracted vanilla assets in misode/mcmeta, branch assets)",
        fix = "Zip the contents of the pack folder, not the folder itself, so pack.mcmeta is at the top.",
    )

    val MCMETA_UNPARSEABLE = Rule(
        id = "P002",
        severity = Severity.BLOCKER,
        title = "pack.mcmeta is not a usable JSON object",
        rationale = "The file has to parse as JSON and carry a `pack` object. Anything else and the " +
            "pack is rejected before a single texture is read.",
        provenance = "$MCDOC_PACK:4-5, `struct Pack { pack: struct PackBase { ... } }`",
        fix = "Fix the JSON. A trailing comma, a comment or a missing `pack` wrapper are the usual causes.",
    )

    val NO_FORMAT_DECLARED = Rule(
        id = "P003",
        severity = Severity.CRITICAL,
        title = "pack.mcmeta declares no format",
        rationale = "`pack_format` became optional in 1.21.9, but only because `min_format` took " +
            "over. With neither field the client has nothing to compare itself against.",
        provenance = "$MCDOC_PACK:12 (`pack_format?`, optional since 1.21.9) and :20 (`min_format?`)",
        fix = "Add `min_format` for 1.21.9 and later, or `pack_format` if older clients must recognise the pack.",
    )

    val FORMAT_VALUE_MALFORMED = Rule(
        id = "P013",
        severity = Severity.CRITICAL,
        title = "A format field holds a value of the wrong shape",
        rationale = "A format is an int, or a one- or two-element list of ints for a minor version. " +
            "A quoted number or a longer list matches no shape the format defines.",
        provenance = "$MCDOC_PACK:69, `type PackFormat = (int | [int] @ 1 | [int, int @ 0..])`; " +
            "$MCDOC_PACK:16 with java/util/mod.mcdoc:28-35 for the inclusive range",
        fix = "Write the number unquoted, or as [major] / [major, minor].",
    )

    val FORMAT_MISMATCH = Rule(
        id = "P010",
        severity = Severity.WARNING,
        title = "Declared format is not the target version's format",
        rationale = "A client whose format differs from the declared one still applies the pack, " +
            "but warns the player that it was made for another version, and any part of the format " +
            "that changed in between behaves differently than the author expected.",
        provenance = "Version-to-format table fetched from ${PackFormatCatalog.SOURCE_URL}",
        fix = "Set the declared format to the target's, or widen the declared range to include it.",
    )

    val SUPPORTED_FORMATS_WITH_MIN_FORMAT = Rule(
        id = "P011",
        severity = Severity.CRITICAL,
        title = "supported_formats used together with a 1.21.9 or later min_format",
        rationale = "The two mechanisms are exclusive. Once `min_format` names a 1.21.9-or-later " +
            "format, `supported_formats` must not be present at all.",
        provenance = "$MCDOC_PACK:13-16, \"Must not be specified in case min_format indicates a " +
            "format version for 1.21.9 and later\"",
        fix = "Delete `supported_formats` and express the range with `min_format` and `max_format`.",
    )

    val RANGE_MISSES_TARGET = Rule(
        id = "P012",
        severity = Severity.WARNING,
        title = "Declared format range does not cover the target",
        rationale = "The declared range is what the client checks itself against. Outside it the " +
            "pack is listed as incompatible and the player has to force it.",
        provenance = "$MCDOC_PACK:16,20,24 for the range fields; target from " +
            PackFormatCatalog.SOURCE_URL,
        fix = "Extend the range, or drop the target from the pack's supported versions.",
    )

    val TARGET_UNKNOWN = Rule(
        id = "P014",
        severity = Severity.INFO,
        title = "Format checks skipped because no target format is known",
        rationale = "Comparing the declared format against a target needs the target. Everything " +
            "that does not depend on it was still checked.",
        provenance = "Command options: neither --pack-format nor a resolvable --mc was available",
        fix = "Pass --pack-format <n> to compare offline, or --mc <version> with network access.",
    )

    val DANGLING_REFERENCE = Rule(
        id = "P020",
        severity = Severity.CRITICAL,
        title = "Reference points at a file the pack does not contain",
        rationale = "A model parent, texture or item model that resolves to nothing renders as the " +
            "missing-texture placeholder. $PACK_STATUS_BLIND",
        provenance = "Reference fields from SpyglassMC/vanilla-mcdoc java/assets/model.mcdoc:7,10-15,47 " +
            "and java/assets/item_definition.mcdoc:61,109. $PACK_STATUS_SOURCE",
        fix = "Correct the reference, or ship the file it names.",
    )

    val PARENT_CYCLE = Rule(
        id = "P021",
        severity = Severity.CRITICAL,
        title = "Model parent chain forms a cycle",
        rationale = "Resolving a model walks its parents until one has no parent. A cycle never " +
            "terminates, so the model fails to load. $PACK_STATUS_BLIND",
        provenance = "`parent` field, SpyglassMC/vanilla-mcdoc java/assets/model.mcdoc:7. " +
            PACK_STATUS_SOURCE,
        fix = "Break the cycle: one of the models in it should inherit from a base model instead.",
    )

    val ASSET_JSON_UNREADABLE = Rule(
        id = "P025",
        severity = Severity.WARNING,
        title = "JSON asset does not parse, so its references were not followed",
        rationale = "The file is not valid JSON. Whether the client's parser accepts it depends on " +
            "which extensions that parser happens to tolerate, so nothing in the pack can rely on " +
            "it, and every reference inside the file went unchecked here.",
        provenance = "Strict JSON parse of the file's own bytes, after dropping a UTF-8 byte order mark",
        fix = "Fix the syntax. A trailing comma or a comment is the usual cause.",
    )

    val SOUND_FILE_MISSING = Rule(
        id = "P022",
        severity = Severity.WARNING,
        title = "sounds.json names an ogg file the pack does not contain",
        rationale = "The sound event resolves to nothing, so the event plays silence. Nothing is " +
            "logged on the server side. $PACK_STATUS_BLIND",
        provenance = "`sounds[].name` with type `file`, SpyglassMC/vanilla-mcdoc " +
            "java/assets/sounds.mcdoc:8,19,43",
        fix = "Add the .ogg under assets/<namespace>/sounds/, or correct the name.",
    )

    val FONT_FILE_MISSING = Rule(
        id = "P023",
        severity = Severity.WARNING,
        title = "Font provider names a file the pack does not contain",
        rationale = "A provider whose file is absent contributes no glyphs, so the characters it " +
            "was supposed to draw come out as the fallback glyph.",
        provenance = "Provider fields from SpyglassMC/vanilla-mcdoc java/assets/font.mcdoc:32 " +
            "(bitmap `file`), :45 (ttf `file`), :61 (unihex `hex_file`) and :86 (reference `id`). " +
            "All four are bare strings in the schema, so their directories were confirmed against " +
            "the extracted vanilla pack in misode/mcmeta, branch assets: minecraft:font/" +
            "nonlatin_european.png is assets/minecraft/textures/font/nonlatin_european.png, and " +
            "minecraft:font/unifont.zip is assets/minecraft/font/unifont.zip. ttf `file` is " +
            "documented as relative to /assets/<namespace>/font/ on minecraft.wiki \"Font\"",
        fix = "Ship the referenced file, or correct the provider's path.",
    )

    val ATLAS_SOURCE_MISSING = Rule(
        id = "P024",
        severity = Severity.WARNING,
        title = "Atlas source names a texture the pack does not contain",
        rationale = "A sprite source that resolves to nothing adds no sprite, and every model that " +
            "expected that sprite draws the placeholder instead. $PACK_STATUS_BLIND",
        provenance = "Sprite source fields typed `#[id=\"texture\"]` in SpyglassMC/vanilla-mcdoc " +
            "java/assets/atlas.mcdoc:28 (single `resource`), :53 (paletted_permutations `textures`), " +
            ":54 (`palette_key`), :56 (permutation values) and :71 (unstitch `resource`). The `sprite` " +
            "fields at :31 and :80 are `definition=true`, so they name the sprite being created " +
            "rather than a file, and are not checked",
        fix = "Ship the texture, or remove the source entry.",
    )

    val SELECT_CASE_COLLISION = Rule(
        id = "P030",
        severity = Severity.CRITICAL,
        title = "Item definition has two cases matching the same value",
        rationale = "A select node takes the first case whose `when` matches. A repeated value means " +
            "the later case is dead code, and nothing warns about it: the item just keeps rendering " +
            "the first model.",
        provenance = "SpyglassMC/vanilla-mcdoc java/assets/item_definition.mcdoc:84-100 " +
            "(`select` cases, `when: (T | [T])`) and :350-353 for the custom_model_data property",
        fix = "Remove the duplicate case, or give it the value it was meant to match.",
    )

    val SELECT_INDEX_INVALID = Rule(
        id = "P032",
        severity = Severity.CRITICAL,
        title = "custom_model_data index is not a valid index",
        rationale = "`index` selects an entry of the item's `custom_model_data` strings list, so it " +
            "cannot be negative or fractional. The client cannot read the property at all.",
        provenance = "SpyglassMC/vanilla-mcdoc java/assets/item_definition.mcdoc:352, `index?: int @ 0..`",
        fix = "Use a whole number, zero or greater. Zero is the default and can be omitted.",
    )

    val ITEM_DEFINITION_COLLISION = Rule(
        id = "P031",
        severity = Severity.WARNING,
        title = "Another pack defines the same item definition",
        rationale = "Two packs writing the same assets/<namespace>/items/<id>.json do not merge. The " +
            "one applied last wins outright, so which plugin's models a player sees depends on the " +
            "order the packs are stacked in.",
        provenance = "Item definitions are single files per item id, " +
            "SpyglassMC/vanilla-mcdoc java/assets/item_definition.mcdoc:11",
        fix = "Give each pack its own namespace, or merge the colliding definitions into one file " +
            "with a select node per plugin.",
    )

    val ILLEGAL_RESOURCE_PATH = Rule(
        id = "P040",
        severity = Severity.WARNING,
        title = "Resource path uses characters the game rejects",
        rationale = "Namespaces and paths allow lowercase letters, digits, underscore, hyphen and " +
            "dot, plus forward slash in a path. An uppercase letter resolves on a Windows or macOS " +
            "development machine and fails on the Linux server the pack is served from.",
        provenance = "minecraft.wiki \"Resource location\", Java Edition character table; uppercase " +
            "was disallowed in 16w32a",
        fix = "Rename to lowercase and replace anything outside [a-z0-9._-] (plus / in paths).",
    )

    val JUNK_ENTRY = Rule(
        id = "P041",
        severity = Severity.INFO,
        title = "Pack ships files that are not pack content",
        rationale = "Archive metadata, editor droppings and source art make the download bigger for " +
            "every player who joins, and source art is often not licensed for redistribution.",
        provenance = "Entry list of the pack",
        fix = "Exclude these from the archive.",
    )

    val LEGACY_CUSTOM_MODEL_DATA = Rule(
        id = "P042",
        severity = Severity.INFO,
        title = "Model uses the removed custom_model_data override mechanism",
        rationale = "`overrides` with a custom_model_data predicate was how custom items worked " +
            "before 1.21.4. It is gone from the model format; the replacement is an item definition " +
            "under items/ with a select node on minecraft:custom_model_data.",
        provenance = "`overrides` is marked #[until=\"1.21.4\"] in SpyglassMC/vanilla-mcdoc " +
            "java/assets/model.mcdoc:24-30; the replacement is item_definition.mcdoc:350-353",
        fix = "Move the mapping into assets/<namespace>/items/<id>.json as a select node.",
    )

    val OVERLAY_NEVER_APPLIES = Rule(
        id = "P050",
        severity = Severity.WARNING,
        title = "Overlay can never apply",
        rationale = "An overlay applies when the client's format falls in the overlay's range and the " +
            "named directory exists. A missing directory, or a range outside everything the pack " +
            "claims to support, means the overlay is dead weight and the root files are used instead.",
        provenance = "$MCDOC_PACK:58-67, `directory: string @ 1..` with `formats`, `min_format` and " +
            "`max_format`",
        fix = "Create the directory, or correct the range so it falls inside the pack's declared range.",
    )

    val TEXTURE_NOT_POWER_OF_TWO = Rule(
        id = "P060",
        severity = Severity.INFO,
        title = "Atlas texture dimensions are not a power of two",
        rationale = "Block and item textures are stitched into a mipmapped atlas. A sprite whose " +
            "sides are not powers of two limits how many mipmap levels the atlas can generate, which " +
            "shows up as noise at distance rather than as an error.",
        provenance = "Dimensions read from the PNG IHDR chunk of each file",
        fix = "Resize to a power of two, 16x16 upwards for block and item textures.",
    )

    val NOT_A_PNG = Rule(
        id = "P061",
        severity = Severity.CRITICAL,
        title = "File named .png is not a PNG",
        rationale = "The bytes do not start with the PNG signature, so the client cannot decode the " +
            "file and every model using it draws the placeholder.",
        provenance = "First eight bytes compared against the PNG signature, RFC 2083 section 3.1",
        fix = "Re-export the file as a PNG. A renamed JPEG or TGA is the usual cause.",
    )

    val ANIMATION_FRAME_MISMATCH = Rule(
        id = "P062",
        severity = Severity.WARNING,
        title = "Animated texture is not a whole number of frames tall",
        rationale = "With no explicit width and height in the animation block, frames are square and " +
            "as wide as the image, so the image height has to be a multiple of its width. Otherwise " +
            "the last frame is cut off.",
        provenance = "`animation.width` and `animation.height` are ratios that default to the frame " +
            "being square, SpyglassMC/vanilla-mcdoc java/assets/texture_meta.mcdoc:4-10",
        fix = "Crop or pad the image to a whole multiple of the frame size, or state width and height " +
            "in the .png.mcmeta animation block.",
    )

    val all = listOf(
        NO_MCMETA, MCMETA_UNPARSEABLE, NO_FORMAT_DECLARED, FORMAT_MISMATCH,
        SUPPORTED_FORMATS_WITH_MIN_FORMAT, RANGE_MISSES_TARGET, FORMAT_VALUE_MALFORMED, TARGET_UNKNOWN,
        DANGLING_REFERENCE, PARENT_CYCLE, ASSET_JSON_UNREADABLE, SOUND_FILE_MISSING, FONT_FILE_MISSING,
        ATLAS_SOURCE_MISSING,
        SELECT_CASE_COLLISION, ITEM_DEFINITION_COLLISION, SELECT_INDEX_INVALID,
        ILLEGAL_RESOURCE_PATH, JUNK_ENTRY, LEGACY_CUSTOM_MODEL_DATA, OVERLAY_NEVER_APPLIES,
        TEXTURE_NOT_POWER_OF_TWO, NOT_A_PNG, ANIMATION_FRAME_MISMATCH,
    )
}
