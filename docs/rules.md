# Rules

Every check pluginscope can report, as printed by `pluginscope rules` in version 0.1.1.
Each rule carries the source of the fact behind it, so a finding can be argued with.

Severities: `blocker` means the artifact does not load, `critical` means it loads and then
fails at runtime, `warning` means it works but depends on luck, `info` means no defect is
being claimed.

## pluginscope jar

23 rules.

### J001 — No plugin descriptor

`info`

**Why it matters.** The jar has neither plugin.yml nor paper-plugin.yml, so the server would never load it as a plugin. Reported as info because libraries are scanned too.

**Fix.** If this should be a plugin, make sure the descriptor ends up at the jar root.

**Source.** PaperMC/Paper plugin loading requires one of the two descriptors

### J002 — Descriptor is missing a required field

`blocker`

**Why it matters.** The server refuses to load a plugin whose descriptor lacks name, version or main.

**Fix.** Add the missing key, or generate the descriptor from the build (plugin-yml / resource-factory).

**Source.** PaperMC/Paper paper-api PluginDescriptionFile required keys

### J003 — Descriptor could not be parsed

`blocker`

**Why it matters.** Invalid YAML, a duplicate key or a non-mapping root all abort plugin loading.

**Fix.** Fix the YAML. Tabs, unquoted colons in strings and duplicated keys are the usual causes.

**Source.** SnakeYAML parse failure while reading the descriptor

### J004 — Main class is not inside the jar

`blocker`

**Why it matters.** The declared main class cannot be found among the jar's class entries, so loading fails with ClassNotFoundException before onEnable.

**Fix.** Check for a typo, or for a build that shaded away or relocated the plugin's own package.

**Source.** Compared descriptor `main` against the jar's class entries

### J005 — Plugin name contains characters the server rejects

`critical`

**Why it matters.** Plugin names are restricted so they can be used in permissions, command namespaces and data folder names.

**Fix.** Use only letters, digits, underscore, hyphen and dot in `name`.

**Source.** PaperMC/Paper plugin name validation pattern

### J006 — Descriptor contains a key the format does not define

`warning`

**Why it matters.** Unknown keys are ignored silently, which is how `depends:` instead of `depend:` ships to production and the dependency is never enforced.

**Fix.** Remove the key or correct the spelling.

**Source.** Key list taken from the descriptor format definitions

### J007 — No api-version, so the plugin loads in legacy mode

`warning`

**Why it matters.** Without api-version the server treats the plugin as pre-1.13 and runs legacy material and enchantment conversion over its data.

**Fix.** Declare the api-version your code was compiled against.

**Source.** PaperMC/Paper legacy plugin handling keyed off a missing api-version

### J008 — api-version is not a value the server recognises

`warning`

**Why it matters.** An unrecognised api-version is either rejected at load time or silently downgraded, depending on the server version.

**Fix.** Use one of the accepted values.

**Source.** Accepted values from Paper's api-version list

### J009 — Both plugin.yml and paper-plugin.yml are present

`warning`

**Why it matters.** Paper uses paper-plugin.yml and ignores plugin.yml entirely, so anything declared only in the legacy file silently stops applying.

**Fix.** Keep one descriptor, or make sure the legacy file is only a fallback for Spigot.

**Source.** Paper plugin loader prefers paper-plugin.yml when both exist

### J010 — Compiled for a newer Java than the target server runs

`critical`

**Why it matters.** The JVM throws UnsupportedClassVersionError while loading the class, before any plugin code executes.

**Fix.** Lower the compiler release, or raise the declared target.

**Source.** JVMS class file major versions mapped to Java releases

### J011 — Bundled code targets a newer Java than the plugin's own classes

`info`

**Why it matters.** A shaded dependency compiled for a newer Java fails only when the class is first touched, which is often long after startup.

**Fix.** Pick a dependency version built for your target Java release.

**Source.** Class file major versions compared across jar entries

### J012 — Main class sits in the server's namespace

`blocker`

**Why it matters.** The loader rejects a descriptor whose main class is inside org.bukkit, so the plugin never loads.

**Fix.** Move the plugin's main class into your own package.

**Source.** paper-api PluginDescriptionFile: "main may not be within the org.bukkit namespace"

### J013 — Version-like value is unquoted, so YAML changed it

`warning`

**Why it matters.** YAML resolves an unquoted 1.20 to the number 1.2 and 1.10 to 1.1, dropping the trailing zero before anything Minecraft-specific ever sees the value. The descriptor then declares a version nobody intended, and no error is raised anywhere. Only reported when the value no longer reads as it was written: 1.21 unquoted survives.

**Fix.** Quote the value so it stays exactly as written.

**Source.** SnakeYAML's YAML 1.1 float resolver, applied to the same descriptor the server loads with org.yaml.snakeyaml.Yaml (referenced from paper-api PluginDescriptionFile)

### J020 — Jar ships classes from the server's own namespace

`critical`

**Why it matters.** Shipping org/bukkit, io/papermc, net/minecraft or com/mojang classes shadows the server's versions on the plugin classloader and redistributes code the plugin does not own.

**Fix.** Mark the server API as compileOnly so it is not shaded into the jar.

**Source.** Package roots owned by the server and Mojang

### J021 — Bundles a library the server already provides

`warning`

**Why it matters.** The server exposes this library to plugins. Shipping a second copy wastes space at best, and at worst the two versions disagree about a class that crosses the API boundary.

**Fix.** Depend on it as compileOnly, or relocate it if you genuinely need a different version.

**Source.** Dependencies of paper-api and the server runtime

### J022 — Third-party library shaded without relocation

`warning`

**Why it matters.** Unrelocated library packages are visible under their original names, so which version wins depends on load order and on what other plugins ship.

**Fix.** Relocate shaded dependencies under your own package, or declare them in `libraries:`.

**Source.** Class entries outside the plugin's own package root

### J023 — Kotlin standard library shaded without relocation

`warning`

**Why it matters.** The server does not provide the Kotlin stdlib, so every Kotlin plugin ships its own. Unrelocated, several plugins expose the same packages at different versions.

**Fix.** Relocate kotlin.** under your own package in the shadow configuration.

**Source.** kotlin/** entries outside the plugin's own package root

### J030 — Jar contains entries that do not belong in a release build

`warning`

**Why it matters.** Sources, build metadata, editor droppings and nested jars inflate the download and occasionally change behaviour.

**Fix.** Exclude these from the packaging task.

**Source.** Entry list of the jar

### J031 — Signature files survived shading

`critical`

**Why it matters.** A signed dependency's META-INF/*.SF no longer matches after other classes are merged in, and the JVM answers with SecurityException at class load.

**Fix.** Strip META-INF/*.SF, *.DSA, *.RSA in the shading step.

**Source.** META-INF signature entries alongside merged content

### J040 — Dependency declaration is contradictory

`warning`

**Why it matters.** Self-references and duplicated entries indicate a copy-paste error, and a plugin listed as both hard and soft dependency has an ambiguous load order.

**Fix.** Remove the duplicate or the self-reference.

**Source.** depend / softdepend / loadbefore / provides of the descriptor

### J041 — Shaded dependency could be a runtime library instead

`info`

**Why it matters.** The server can download declared libraries at load time, which keeps the jar small and lets several plugins share one copy.

**Fix.** Move the dependency to `libraries:` in the descriptor.

**Source.** `libraries:` support in the plugin descriptor

### J050 — Most of a bundled library is never referenced

`info`

**Why it matters.** Static reachability from the entry points suggests the jar carries code it never calls. Reflection and service files are not followed, so treat this as a hint.

**Fix.** Enable minimisation in the shading step, or shade a smaller module of the library.

**Source.** Reference graph between classes inside the jar

### J060 — Folia support is declared

`info`

**Why it matters.** The key only tells the server to load the plugin under regionised threading. It is not evidence that the code is thread-safe.

**Fix.** Verify the claim with a Folia-specific analyser before shipping it.

**Source.** folia-supported key in the descriptor

## pluginscope compat

5 rules.

### C001 — Referenced API class does not exist in the target API

`critical`

**Why it matters.** The class is not in the API being checked against, so touching this code path throws NoClassDefFoundError on that server version.

**Fix.** Guard the call behind a version check, or drop support for the older target.

**Source.** Class entries of the API jar(s) passed to --api

### C002 — Referenced API member does not exist in the target API

`critical`

**Why it matters.** The owner class is present and its full ancestry is known, and the method or field is nowhere in it, which is NoSuchMethodError or NoSuchFieldError at runtime. One exception worth knowing: Paper rewrites some legacy calls while loading a plugin that declares an older api-version, so a small number of these are repaired for you.

**Fix.** Use the replacement member, or reflectively feel for it when supporting both versions.

**Source.** Member tables of the API jar(s), resolved through the class hierarchy; rewriting caveat from Paper's load-time class processing (Commodore)

### C010 — Referenced API member is deprecated in the target API

`info`

**Why it matters.** Still works today. Deprecation in the server API is the usual precursor to removal one or two releases later.

**Fix.** Move to the documented replacement while it is a choice rather than a break.

**Source.** @Deprecated annotations and ACC_DEPRECATED flags in the API jar(s)

### C020 — Uses server internals rather than the plugin API

`warning`

**Why it matters.** net.minecraft and craftbukkit classes are implementation detail. They are remapped, renamed or restructured between Minecraft versions with no notice.

**Fix.** Prefer the API. Where there is no API, isolate the internals behind your own version-specific layer so a break is one class, not the whole plugin.

**Source.** Reference owner package, matched against the server's internal namespaces

### C030 — No references to the checked namespaces

`info`

**Why it matters.** Nothing in the jar touches the API being checked. Either the wrong namespaces were selected, or the jar is a library rather than a plugin.

**Fix.** Widen the check with --include-prefix if this is unexpected.

**Source.** Reference scan of the jar

## pluginscope stack

13 rules.

### S001 — Hard dependency is not installed

`blocker`

**Why it matters.** A required dependency that is absent stops the depending plugin from being enabled at all, and the log line for it is easy to miss during a big startup.

**Fix.** Install the dependency, or make it a soft dependency if the plugin can run without it.

**Source.** depend / dependencies of each descriptor, compared with the plugin set

### S002 — Hard dependencies form a cycle

`critical`

**Why it matters.** The loader cannot order a cycle, so at least one plugin in it is skipped.

**Fix.** Break the cycle: one direction becomes a soft dependency.

**Source.** Cycle search over the hard-dependency graph

### S010 — Two jars declare the same plugin name

`critical`

**Why it matters.** Plugin names are the identity the server keys everything off. With two candidates, one is refused and which one that is depends on directory order.

**Fix.** Remove the stale jar. This is usually an old version left behind after an update.

**Source.** `name` of each descriptor in the directory

### S011 — Two jars declare the same main class

`critical`

**Why it matters.** Two jars carrying the same main class are almost always two builds of one plugin, and the classes of both end up on the server.

**Fix.** Keep one build.

**Source.** `main` of each descriptor in the directory

### S012 — A plugin name is also claimed through `provides`

`warning`

**Why it matters.** `provides` makes a plugin answer to another name for dependency resolution. When the real plugin is installed too, dependants may bind to either.

**Fix.** Install only one of the two, or remove the `provides` claim.

**Source.** `provides` compared with the installed plugin names

### S020 — Two plugins register the same command

`warning`

**Why it matters.** Only one plugin owns the plain command name. The other is reachable only through its namespaced form, which players never type.

**Fix.** Rename one command, or set the winner explicitly in the server's commands.yml aliases.

**Source.** `commands` blocks of the legacy descriptors

### S021 — A command alias is claimed by more than one plugin

`info`

**Why it matters.** Same as a command collision, but for an alias, and the other claimant may be a plugin whose real command carries that name. It surfaces as 'that shortcut opens the wrong menu' rather than as an error.

**Fix.** Drop the overlapping alias from one of the plugins.

**Source.** `aliases` of each declared command, compared with the declared command names

### S030 — Several plugins ship the same unrelocated library, in different builds

`warning`

**Why it matters.** The package is exposed under its original name by more than one plugin and the class sets differ, so the copy that answers a given class depends on load order.

**Fix.** Relocate the library in your own plugins. For third-party jars, report it upstream.

**Source.** Foreign package roots of every jar, fingerprinted by their class list

### S031 — The same library is bundled by several plugins

`info`

**Why it matters.** Identical copies are only wasted space, but they are the reason a plugins folder is often several times larger than it needs to be.

**Fix.** Nothing urgent. Runtime libraries or a shared library plugin would remove the copies.

**Source.** Foreign package roots shared by more than one jar

### S040 — Java release required by this plugin set

`info`

**Why it matters.** The highest class-file version across every jar is the minimum Java the server must run, whatever the server itself needs.

**Fix.** Run at least this Java release, or replace the plugin that raises the floor.

**Source.** Class file major versions of every class in the directory

### S041 — Plugin loads in legacy mode

`warning`

**Why it matters.** A plugin with no api-version is treated as pre-1.13 and has legacy data conversion applied to it, which is a behaviour difference, not just a warning.

**Fix.** Update the plugin, or ask its author to declare an api-version.

**Source.** Missing api-version in the descriptor

### S050 — Soft dependency or load-order target is not installed

`info`

**Why it matters.** Not a fault: it records which optional integrations of the installed plugins are inactive on this server.

**Fix.** Install the plugin if you expected the integration to be active.

**Source.** softdepend / loadbefore compared with the plugin set

### S060 — Plugin does not declare Folia support

`warning`

**Why it matters.** Under regionised threading the server refuses to load plugins that do not declare support, so on Folia this plugin is simply absent.

**Fix.** Only relevant if the server runs Folia. Check for a Folia build of the plugin.

**Source.** folia-supported key of each descriptor

## pluginscope pack

24 rules.

### P001 — No pack.mcmeta at the pack root

`blocker`

**Why it matters.** A pack without pack.mcmeta beside its assets directory is not a pack. The client lists it as invalid and never applies it.

**Fix.** Zip the contents of the pack folder, not the folder itself, so pack.mcmeta is at the top.

**Source.** Vanilla pack layout: pack.mcmeta sits at the root next to assets/ (verified against the extracted vanilla assets in misode/mcmeta, branch assets)

### P002 — pack.mcmeta is not a usable JSON object

`blocker`

**Why it matters.** The file has to parse as JSON and carry a `pack` object. Anything else and the pack is rejected before a single texture is read.

**Fix.** Fix the JSON. A trailing comma, a comment or a missing `pack` wrapper are the usual causes.

**Source.** SpyglassMC/vanilla-mcdoc java/pack.mcdoc:4-5, `struct Pack { pack: struct PackBase { ... } }`

### P003 — pack.mcmeta declares no format

`critical`

**Why it matters.** `pack_format` became optional in 1.21.9, but only because `min_format` took over. With neither field the client has nothing to compare itself against.

**Fix.** Add `min_format` for 1.21.9 and later, or `pack_format` if older clients must recognise the pack.

**Source.** SpyglassMC/vanilla-mcdoc java/pack.mcdoc:12 (`pack_format?`, optional since 1.21.9) and :20 (`min_format?`)

### P010 — Declared format is not the target version's format

`warning`

**Why it matters.** A client whose format differs from the declared one still applies the pack, but warns the player that it was made for another version, and any part of the format that changed in between behaves differently than the author expected.

**Fix.** Set the declared format to the target's, or widen the declared range to include it.

**Source.** Version-to-format table fetched from https://raw.githubusercontent.com/misode/mcmeta/refs/heads/summary/versions/data.min.json

### P011 — supported_formats used together with a 1.21.9 or later min_format

`critical`

**Why it matters.** The two mechanisms are exclusive. Once `min_format` names a 1.21.9-or-later format, `supported_formats` must not be present at all.

**Fix.** Delete `supported_formats` and express the range with `min_format` and `max_format`.

**Source.** SpyglassMC/vanilla-mcdoc java/pack.mcdoc:13-16, "Must not be specified in case min_format indicates a format version for 1.21.9 and later"

### P012 — Declared format range does not cover the target

`warning`

**Why it matters.** The declared range is what the client checks itself against. Outside it the pack is listed as incompatible and the player has to force it.

**Fix.** Extend the range, or drop the target from the pack's supported versions.

**Source.** SpyglassMC/vanilla-mcdoc java/pack.mcdoc:16,20,24 for the range fields; target from https://raw.githubusercontent.com/misode/mcmeta/refs/heads/summary/versions/data.min.json

### P013 — A format field holds a value of the wrong shape

`critical`

**Why it matters.** A format is an int, or a one- or two-element list of ints for a minor version. A quoted number or a longer list matches no shape the format defines.

**Fix.** Write the number unquoted, or as [major] / [major, minor].

**Source.** SpyglassMC/vanilla-mcdoc java/pack.mcdoc:69, `type PackFormat = (int | [int] @ 1 | [int, int @ 0..])`; SpyglassMC/vanilla-mcdoc java/pack.mcdoc:16 with java/util/mod.mcdoc:28-35 for the inclusive range

### P014 — Format checks skipped because no target format is known

`info`

**Why it matters.** Comparing the declared format against a target needs the target. Everything that does not depend on it was still checked.

**Fix.** Pass --pack-format <n> to compare offline, or --mc <version> with network access.

**Source.** Command options: neither --pack-format nor a resolvable --mc was available

### P020 — Reference points at a file the pack does not contain

`critical`

**Why it matters.** A model parent, texture or item model that resolves to nothing renders as the missing-texture placeholder. The server cannot detect this at runtime. PlayerResourcePackStatusEvent.Status reports SUCCESSFULLY_LOADED for a client that applied the pack and then drew the missing-texture placeholder, so a broken reference reaches players silently.

**Fix.** Correct the reference, or ship the file it names.

**Source.** Reference fields from SpyglassMC/vanilla-mcdoc java/assets/model.mcdoc:7,10-15,47 and java/assets/item_definition.mcdoc:61,109. PaperMC/Paper paper-api PlayerResourcePackStatusEvent.Status, eight values, none of which signals a partial load

### P021 — Model parent chain forms a cycle

`critical`

**Why it matters.** Resolving a model walks its parents until one has no parent. A cycle never terminates, so the model fails to load. The server cannot detect this at runtime. PlayerResourcePackStatusEvent.Status reports SUCCESSFULLY_LOADED for a client that applied the pack and then drew the missing-texture placeholder, so a broken reference reaches players silently.

**Fix.** Break the cycle: one of the models in it should inherit from a base model instead.

**Source.** `parent` field, SpyglassMC/vanilla-mcdoc java/assets/model.mcdoc:7. PaperMC/Paper paper-api PlayerResourcePackStatusEvent.Status, eight values, none of which signals a partial load

### P022 — sounds.json names an ogg file the pack does not contain

`warning`

**Why it matters.** The sound event resolves to nothing, so the event plays silence. Nothing is logged on the server side. The server cannot detect this at runtime. PlayerResourcePackStatusEvent.Status reports SUCCESSFULLY_LOADED for a client that applied the pack and then drew the missing-texture placeholder, so a broken reference reaches players silently.

**Fix.** Add the .ogg under assets/<namespace>/sounds/, or correct the name.

**Source.** `sounds[].name` with type `file`, SpyglassMC/vanilla-mcdoc java/assets/sounds.mcdoc:8,19,43

### P023 — Font provider names a file the pack does not contain

`warning`

**Why it matters.** A provider whose file is absent contributes no glyphs, so the characters it was supposed to draw come out as the fallback glyph.

**Fix.** Ship the referenced file, or correct the provider's path.

**Source.** Provider fields from SpyglassMC/vanilla-mcdoc java/assets/font.mcdoc:32 (bitmap `file`), :45 (ttf `file`), :61 (unihex `hex_file`) and :86 (reference `id`). All four are bare strings in the schema, so their directories were confirmed against the extracted vanilla pack in misode/mcmeta, branch assets: minecraft:font/nonlatin_european.png is assets/minecraft/textures/font/nonlatin_european.png, and minecraft:font/unifont.zip is assets/minecraft/font/unifont.zip. ttf `file` is documented as relative to /assets/<namespace>/font/ on minecraft.wiki "Font"

### P024 — Atlas source names a texture the pack does not contain

`warning`

**Why it matters.** A sprite source that resolves to nothing adds no sprite, and every model that expected that sprite draws the placeholder instead. The server cannot detect this at runtime. PlayerResourcePackStatusEvent.Status reports SUCCESSFULLY_LOADED for a client that applied the pack and then drew the missing-texture placeholder, so a broken reference reaches players silently.

**Fix.** Ship the texture, or remove the source entry.

**Source.** Sprite source fields typed `#[id="texture"]` in SpyglassMC/vanilla-mcdoc java/assets/atlas.mcdoc:28 (single `resource`), :53 (paletted_permutations `textures`), :54 (`palette_key`), :56 (permutation values) and :71 (unstitch `resource`). The `sprite` fields at :31 and :80 are `definition=true`, so they name the sprite being created rather than a file, and are not checked

### P025 — JSON asset does not parse, so its references were not followed

`warning`

**Why it matters.** The file is not valid JSON. Whether the client's parser accepts it depends on which extensions that parser happens to tolerate, so nothing in the pack can rely on it, and every reference inside the file went unchecked here.

**Fix.** Fix the syntax. A trailing comma or a comment is the usual cause.

**Source.** Strict JSON parse of the file's own bytes, after dropping a UTF-8 byte order mark

### P030 — Item definition has two cases matching the same value

`critical`

**Why it matters.** A select node takes the first case whose `when` matches. A repeated value means the later case is dead code, and nothing warns about it: the item just keeps rendering the first model.

**Fix.** Remove the duplicate case, or give it the value it was meant to match.

**Source.** SpyglassMC/vanilla-mcdoc java/assets/item_definition.mcdoc:84-100 (`select` cases, `when: (T | [T])`) and :350-353 for the custom_model_data property

### P031 — Another pack defines the same item definition

`warning`

**Why it matters.** Two packs writing the same assets/<namespace>/items/<id>.json do not merge. The one applied last wins outright, so which plugin's models a player sees depends on the order the packs are stacked in.

**Fix.** Give each pack its own namespace, or merge the colliding definitions into one file with a select node per plugin.

**Source.** Item definitions are single files per item id, SpyglassMC/vanilla-mcdoc java/assets/item_definition.mcdoc:11

### P032 — custom_model_data index is not a valid index

`critical`

**Why it matters.** `index` selects an entry of the item's `custom_model_data` strings list, so it cannot be negative or fractional. The client cannot read the property at all.

**Fix.** Use a whole number, zero or greater. Zero is the default and can be omitted.

**Source.** SpyglassMC/vanilla-mcdoc java/assets/item_definition.mcdoc:352, `index?: int @ 0..`

### P040 — Resource path uses characters the game rejects

`warning`

**Why it matters.** Namespaces and paths allow lowercase letters, digits, underscore, hyphen and dot, plus forward slash in a path. An uppercase letter resolves on a Windows or macOS development machine and fails on the Linux server the pack is served from.

**Fix.** Rename to lowercase and replace anything outside [a-z0-9._-] (plus / in paths).

**Source.** minecraft.wiki "Resource location", Java Edition character table; uppercase was disallowed in 16w32a

### P041 — Pack ships files that are not pack content

`info`

**Why it matters.** Archive metadata, editor droppings and source art make the download bigger for every player who joins, and source art is often not licensed for redistribution.

**Fix.** Exclude these from the archive.

**Source.** Entry list of the pack

### P042 — Model uses the removed custom_model_data override mechanism

`info`

**Why it matters.** `overrides` with a custom_model_data predicate was how custom items worked before 1.21.4. It is gone from the model format; the replacement is an item definition under items/ with a select node on minecraft:custom_model_data.

**Fix.** Move the mapping into assets/<namespace>/items/<id>.json as a select node.

**Source.** `overrides` is marked #[until="1.21.4"] in SpyglassMC/vanilla-mcdoc java/assets/model.mcdoc:24-30; the replacement is item_definition.mcdoc:350-353

### P050 — Overlay can never apply

`warning`

**Why it matters.** An overlay applies when the client's format falls in the overlay's range and the named directory exists. A missing directory, or a range outside everything the pack claims to support, means the overlay is dead weight and the root files are used instead.

**Fix.** Create the directory, or correct the range so it falls inside the pack's declared range.

**Source.** SpyglassMC/vanilla-mcdoc java/pack.mcdoc:58-67, `directory: string @ 1..` with `formats`, `min_format` and `max_format`

### P060 — Atlas texture dimensions are not a power of two

`info`

**Why it matters.** Block and item textures are stitched into a mipmapped atlas. A sprite whose sides are not powers of two limits how many mipmap levels the atlas can generate, which shows up as noise at distance rather than as an error.

**Fix.** Resize to a power of two, 16x16 upwards for block and item textures.

**Source.** Dimensions read from the PNG IHDR chunk of each file

### P061 — File named .png is not a PNG

`critical`

**Why it matters.** The bytes do not start with the PNG signature, so the client cannot decode the file and every model using it draws the placeholder.

**Fix.** Re-export the file as a PNG. A renamed JPEG or TGA is the usual cause.

**Source.** First eight bytes compared against the PNG signature, RFC 2083 section 3.1

### P062 — Animated texture is not a whole number of frames tall

`warning`

**Why it matters.** With no explicit width and height in the animation block, frames are square and as wide as the image, so the image height has to be a multiple of its width. Otherwise the last frame is cut off.

**Fix.** Crop or pad the image to a whole multiple of the frame size, or state width and height in the .png.mcmeta animation block.

**Source.** `animation.width` and `animation.height` are ratios that default to the frame being square, SpyglassMC/vanilla-mcdoc java/assets/texture_meta.mcdoc:4-10
