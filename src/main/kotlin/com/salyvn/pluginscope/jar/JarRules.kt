package com.salyvn.pluginscope.jar

import com.salyvn.pluginscope.core.Rule
import com.salyvn.pluginscope.core.Severity

/**
 * Every check the single-jar audit can report, with the reason it matters and where the underlying
 * fact was established. The provenance strings are printed with `--verbose` and with `jar --rules`,
 * so a user can check a claim instead of believing it.
 */
object JarRules {

    val NOT_A_PLUGIN = Rule(
        id = "J001",
        severity = Severity.INFO,
        title = "No plugin descriptor",
        rationale = "The jar has neither plugin.yml nor paper-plugin.yml, so the server would " +
            "never load it as a plugin. Reported as info because libraries are scanned too.",
        provenance = "PaperMC/Paper plugin loading requires one of the two descriptors",
        fix = "If this should be a plugin, make sure the descriptor ends up at the jar root.",
    )

    val DESCRIPTOR_INCOMPLETE = Rule(
        id = "J002",
        severity = Severity.BLOCKER,
        title = "Descriptor is missing a required field",
        rationale = "The server refuses to load a plugin whose descriptor lacks name, version or main.",
        provenance = "PaperMC/Paper paper-api PluginDescriptionFile required keys",
        fix = "Add the missing key, or generate the descriptor from the build (plugin-yml / resource-factory).",
    )

    val DESCRIPTOR_UNPARSEABLE = Rule(
        id = "J003",
        severity = Severity.BLOCKER,
        title = "Descriptor could not be parsed",
        rationale = "Invalid YAML, a duplicate key or a non-mapping root all abort plugin loading.",
        provenance = "SnakeYAML parse failure while reading the descriptor",
        fix = "Fix the YAML. Tabs, unquoted colons in strings and duplicated keys are the usual causes.",
    )

    val MAIN_CLASS_MISSING = Rule(
        id = "J004",
        severity = Severity.BLOCKER,
        title = "Main class is not inside the jar",
        rationale = "The declared main class cannot be found among the jar's class entries, so " +
            "loading fails with ClassNotFoundException before onEnable.",
        provenance = "Compared descriptor `main` against the jar's class entries",
        fix = "Check for a typo, or for a build that shaded away or relocated the plugin's own package.",
    )

    val NAME_ILLEGAL = Rule(
        id = "J005",
        severity = Severity.CRITICAL,
        title = "Plugin name contains characters the server rejects",
        rationale = "Plugin names are restricted so they can be used in permissions, command " +
            "namespaces and data folder names.",
        provenance = "PaperMC/Paper plugin name validation pattern",
        fix = "Use only letters, digits, underscore, hyphen and dot in `name`.",
    )

    val MAIN_IN_SERVER_NAMESPACE = Rule(
        id = "J012",
        severity = Severity.BLOCKER,
        title = "Main class sits in the server's namespace",
        rationale = "The loader rejects a descriptor whose main class is inside org.bukkit, so the " +
            "plugin never loads.",
        provenance = "paper-api PluginDescriptionFile: \"main may not be within the org.bukkit namespace\"",
        fix = "Move the plugin's main class into your own package.",
    )

    val UNQUOTED_VERSION_SCALAR = Rule(
        id = "J013",
        severity = Severity.WARNING,
        title = "Version-like value is unquoted, so YAML changed it",
        rationale = "YAML resolves an unquoted 1.20 to the number 1.2 and 1.10 to 1.1, dropping the " +
            "trailing zero before anything Minecraft-specific ever sees the value. The descriptor " +
            "then declares a version nobody intended, and no error is raised anywhere. Only " +
            "reported when the value no longer reads as it was written: 1.21 unquoted survives.",
        provenance = "SnakeYAML's YAML 1.1 float resolver, applied to the same descriptor the server " +
            "loads with org.yaml.snakeyaml.Yaml (referenced from paper-api PluginDescriptionFile)",
        fix = "Quote the value so it stays exactly as written.",
    )

    val API_VERSION_PAPER_26_SPIGOT_INCOMPATIBLE = Rule(
        id = "J014",
        severity = Severity.WARNING,
        title = "api-version targets modern Paper 26.x+ in legacy plugin.yml",
        rationale = "Spigot's plugin loader only recognizes historical 1.13 through 1.21 api-version declarations. " +
            "Declaring 26.x or 26.3 in a legacy plugin.yml causes Spigot to reject the plugin or fall back to pre-1.13 " +
            "legacy mode. For modern Paper 26.x releases, declare paper-plugin.yml or dual descriptors.",
        provenance = "Spigot PluginDescriptionFile valid api-version patterns vs Paper 26.x apiVersioning.json",
        fix = "Migrate to paper-plugin.yml for Paper 26.x+, or declare a dual-descriptor setup with 1.21 fallback in plugin.yml.",
    )

    val DESCRIPTOR_UNKNOWN_KEY = Rule(
        id = "J006",
        severity = Severity.WARNING,
        title = "Descriptor contains a key the format does not define",
        rationale = "Unknown keys are ignored silently, which is how `depends:` instead of `depend:` " +
            "ships to production and the dependency is never enforced.",
        provenance = "Key list taken from the descriptor format definitions",
        fix = "Remove the key or correct the spelling.",
    )

    val API_VERSION_ABSENT = Rule(
        id = "J007",
        severity = Severity.WARNING,
        title = "No api-version, so the plugin loads in legacy mode",
        rationale = "Without api-version the server treats the plugin as pre-1.13 and runs legacy " +
            "material and enchantment conversion over its data.",
        provenance = "PaperMC/Paper legacy plugin handling keyed off a missing api-version",
        fix = "Declare the api-version your code was compiled against.",
    )

    val API_VERSION_UNKNOWN = Rule(
        id = "J008",
        severity = Severity.WARNING,
        title = "api-version is not a value the server recognises",
        rationale = "An unrecognised api-version is either rejected at load time or silently " +
            "downgraded, depending on the server version.",
        provenance = "Accepted values from Paper's api-version list",
        fix = "Use one of the accepted values.",
    )

    val BOTH_DESCRIPTORS = Rule(
        id = "J009",
        severity = Severity.WARNING,
        title = "Both plugin.yml and paper-plugin.yml are present",
        rationale = "Paper uses paper-plugin.yml and ignores plugin.yml entirely, so anything " +
            "declared only in the legacy file silently stops applying.",
        provenance = "Paper plugin loader prefers paper-plugin.yml when both exist",
        fix = "Keep one descriptor, or make sure the legacy file is only a fallback for Spigot.",
    )

    val CLASS_VERSION_TOO_NEW = Rule(
        id = "J010",
        severity = Severity.CRITICAL,
        title = "Compiled for a newer Java than the target server runs",
        rationale = "The JVM throws UnsupportedClassVersionError while loading the class, before " +
            "any plugin code executes.",
        provenance = "JVMS class file major versions mapped to Java releases",
        fix = "Lower the compiler release, or raise the declared target.",
    )

    val CLASS_VERSION_MIXED = Rule(
        id = "J011",
        severity = Severity.INFO,
        title = "Bundled code targets a newer Java than the plugin's own classes",
        rationale = "A shaded dependency compiled for a newer Java fails only when the class is " +
            "first touched, which is often long after startup.",
        provenance = "Class file major versions compared across jar entries",
        fix = "Pick a dependency version built for your target Java release.",
    )

    val SERVER_NAMESPACE_BUNDLED = Rule(
        id = "J020",
        severity = Severity.CRITICAL,
        title = "Jar ships classes from the server's own namespace",
        rationale = "Shipping org/bukkit, io/papermc, net/minecraft or com/mojang classes shadows " +
            "the server's versions on the plugin classloader and redistributes code the plugin " +
            "does not own.",
        provenance = "Package roots owned by the server and Mojang",
        fix = "Mark the server API as compileOnly so it is not shaded into the jar.",
    )

    val PROVIDED_LIBRARY_BUNDLED = Rule(
        id = "J021",
        severity = Severity.WARNING,
        title = "Bundles a library the server already provides",
        rationale = "The server exposes this library to plugins. Shipping a second copy wastes " +
            "space at best, and at worst the two versions disagree about a class that crosses the " +
            "API boundary.",
        provenance = "Dependencies of paper-api and the server runtime",
        fix = "Depend on it as compileOnly, or relocate it if you genuinely need a different version.",
    )

    val UNRELOCATED_LIBRARY = Rule(
        id = "J022",
        severity = Severity.WARNING,
        title = "Third-party library shaded without relocation",
        rationale = "Unrelocated library packages are visible under their original names, so which " +
            "version wins depends on load order and on what other plugins ship.",
        provenance = "Class entries outside the plugin's own package root",
        fix = "Relocate shaded dependencies under your own package, or declare them in `libraries:`.",
    )

    val KOTLIN_STDLIB_BUNDLED = Rule(
        id = "J023",
        severity = Severity.WARNING,
        title = "Kotlin standard library shaded without relocation",
        rationale = "The server does not provide the Kotlin stdlib, so every Kotlin plugin ships " +
            "its own. Unrelocated, several plugins expose the same packages at different versions.",
        provenance = "kotlin/** entries outside the plugin's own package root",
        fix = "Relocate kotlin.** under your own package in the shadow configuration.",
    )

    val BSTATS_UNRELOCATED = Rule(
        id = "J024",
        severity = Severity.CRITICAL,
        title = "bStats Metrics shaded without relocation",
        rationale = "bStats checks its own package name in a static initialiser and throws " +
            "IllegalStateException during onEnable when org.bstats was not relocated under the " +
            "plugin's own namespace.",
        provenance = "org.bstats.Metrics static relocation guard in bstats-base",
        fix = "Relocate org.bstats under your own package in ShadowJar or maven-shade-plugin.",
    )

    val LEGACY_VERSIONED_NMS_ON_MODERN_PAPER = Rule(
        id = "J025",
        severity = Severity.CRITICAL,
        title = "Bytecode references legacy versioned NMS or CraftBukkit package on Paper 26.x+",
        rationale = "Paper 26.x has removed all legacy versioned package shims (net.minecraft.server.v1_XX_RX and " +
            "org.bukkit.craftbukkit.v1_XX_RX). Plugins declaring api-version 26.x or running on modern Paper throw " +
            "ClassNotFoundException upon touching versioned packages.",
        provenance = "PaperMC modern classloader removal of CraftBukkit versioned relocation shims",
        fix = "Use the unversioned Bukkit/Paper API or unversioned CraftBukkit/Mojang-mapped classes.",
    )

    val MOJANG_MAPPED_BYTECODE_IN_LEGACY_DESCRIPTOR = Rule(
        id = "J026",
        severity = Severity.CRITICAL,
        title = "Mojang-mapped net.minecraft bytecode referenced in legacy plugin.yml",
        rationale = "Unversioned net.minecraft references require Mojang runtime mappings. Legacy plugin.yml plugins " +
            "deployed on Spigot or pre-1.20.5 Paper servers fail with ClassNotFoundException because Spigot does not " +
            "provide Mojang-mapped packages to legacy plugins.",
        provenance = "Spigot runtime obfuscation vs Paper paper-plugin.yml Mojang-mapped runtime classloader",
        fix = "Migrate to paper-plugin.yml to run with Mojang mappings, or compile against the public Bukkit API.",
    )

    val JAR_HYGIENE = Rule(
        id = "J030",
        severity = Severity.WARNING,
        title = "Jar contains entries that do not belong in a release build",
        rationale = "Sources, build metadata, editor droppings and nested jars inflate the download " +
            "and occasionally change behaviour.",
        provenance = "Entry list of the jar",
        fix = "Exclude these from the packaging task.",
    )

    val SIGNATURE_LEFTOVER = Rule(
        id = "J031",
        severity = Severity.CRITICAL,
        title = "Signature files survived shading",
        rationale = "A signed dependency's META-INF/*.SF no longer matches after other classes are " +
            "merged in, and the JVM answers with SecurityException at class load.",
        provenance = "META-INF signature entries alongside merged content",
        fix = "Strip META-INF/*.SF, *.DSA, *.RSA in the shading step.",
    )

    val DEPENDENCY_SUSPECT = Rule(
        id = "J040",
        severity = Severity.WARNING,
        title = "Dependency declaration is contradictory",
        rationale = "Self-references and duplicated entries indicate a copy-paste error, and a " +
            "plugin listed as both hard and soft dependency has an ambiguous load order.",
        provenance = "depend / softdepend / loadbefore / provides of the descriptor",
        fix = "Remove the duplicate or the self-reference.",
    )

    val RUNTIME_LIBRARIES_HINT = Rule(
        id = "J041",
        severity = Severity.INFO,
        title = "Shaded dependency could be a runtime library instead",
        rationale = "The server can download declared libraries at load time, which keeps the jar " +
            "small and lets several plugins share one copy.",
        provenance = "`libraries:` support in the plugin descriptor",
        fix = "Move the dependency to `libraries:` in the descriptor.",
    )

    val PAPER_DESCRIPTOR_DRIFT = Rule(
        id = "J042",
        severity = Severity.CRITICAL,
        title = "paper-plugin.yml omits dependencies or entrypoints required on Paper",
        rationale = "Paper ignores plugin.yml when paper-plugin.yml is present and isolates " +
            "PaperPluginClassLoader so undeclared or bootstrap-only dependencies cannot be " +
            "resolved at server runtime, failing with NoClassDefFoundError.",
        provenance = "PaperMC/Paper PaperPluginParent and PaperPluginClassLoader isolation rules",
        fix = "Declare every runtime plugin dependency under dependencies.server with " +
            "join-classpath: true in paper-plugin.yml, and ensure bootstrapper/loader classes exist.",
    )

    val PAPER_DEPENDENCY_GRAPH_INVALID = Rule(
        id = "J043",
        severity = Severity.CRITICAL,
        title = "paper-plugin.yml dependency option, load order, or entrypoint collision",
        rationale = "Paper deserializes dependencies.<phase>.<Plugin> through Configurate DependencyConfiguration " +
            "(accepting only load: BEFORE | AFTER | OMIT, boolean required, and boolean join-classpath) and " +
            "instantiates PluginBootstrap and PluginLoader before Bukkit server state exists. Invalid load enums " +
            "abort plugin loading, colliding main/bootstrapper/loader classes trigger initialization crashes, and " +
            "contradictory BEFORE vs AFTER edges across bootstrap, server, or dual plugin.yml descriptors create " +
            "unsatisfiable load-order constraints.",
        provenance = "PaperMC/Paper DependencyConfiguration.LoadOrder, PaperPluginMeta, and PluginLoadOrder",
        fix = "Use BEFORE, AFTER, or OMIT for per-dependency load, STARTUP or POSTWORLD for root load, separate " +
            "PluginBootstrap and PluginLoader from your JavaPlugin main class, and keep bootstrap/server/plugin.yml " +
            "load directions consistent.",
    )

    val UNREACHABLE_BUNDLE = Rule(
        id = "J050",
        severity = Severity.INFO,
        title = "Most of a bundled library is never referenced",
        rationale = "Static reachability from the entry points suggests the jar carries code it " +
            "never calls. Reflection and service files are not followed, so treat this as a hint.",
        provenance = "Reference graph between classes inside the jar",
        fix = "Enable minimisation in the shading step, or shade a smaller module of the library.",
    )

    val FOLIA_DECLARATION = Rule(
        id = "J060",
        severity = Severity.INFO,
        title = "Folia support is declared",
        rationale = "The key only tells the server to load the plugin under regionised threading. " +
            "It is not evidence that the code is thread-safe.",
        provenance = "folia-supported key in the descriptor",
        fix = "Verify the claim with a Folia-specific analyser before shipping it.",
    )

    val UNCANCELLED_SCHEDULER_TASK = Rule(
        id = "J070",
        severity = Severity.WARNING,
        title = "Scheduled task handle is leaked or accumulated without cancellation",
        rationale = "Repeating scheduler tasks whose BukkitTask or ScheduledTask handle is discarded in " +
            "dynamic code paths or accumulated in static fields and collections without cancellation or " +
            "clearing in onDisable continue accumulating callbacks, retaining captured listener and world state.",
        provenance = "PaperMC/Paper BukkitScheduler, AsyncScheduler, and RegionScheduler task lifecycle contracts",
        fix = "Retain the returned BukkitTask or ScheduledTask handle, cancel repeating tasks when work finishes " +
            "or in onDisable, and clear static task collections during plugin shutdown.",
    )

    val UNMANAGED_THREAD_OR_EXECUTOR_LEAK = Rule(
        id = "J071",
        severity = Severity.CRITICAL,
        title = "Unmanaged ExecutorService, Timer, or Thread started without onDisable shutdown",
        rationale = "Bukkit and Paper only cancel tasks submitted through server scheduler APIs. Raw " +
            "ExecutorService pools, java.util.Timer threads, and java.lang.Thread instances inherit " +
            "PaperPluginClassLoader as their contextClassLoader and remain live JVM GC roots across " +
            "plugin disable or reload unless explicitly shut down.",
        provenance = "PaperMC/Paper PaperPluginClassLoader lifecycle and JVM live-thread GC root semantics",
        fix = "Shut down custom ExecutorService instances (shutdown or shutdownNow), cancel java.util.Timer " +
            "instances in onDisable, or migrate background work to BukkitScheduler and AsyncScheduler.",
    )

    val STATIC_SERVER_ENTITY_RETENTION = Rule(
        id = "J072",
        severity = Severity.WARNING,
        title = "Static field or collection retains live Player, Entity, World, or Chunk instances",
        rationale = "Strong static references to live server lifecycle objects (Player, Entity, World, Chunk, " +
            "BlockState) prevent the JVM from garbage-collecting disconnected players and unloaded ServerLevel " +
            "chunk graphs until the static reference is cleared.",
        provenance = "PaperMC/Paper ServerPlayer and ServerLevel unload lifecycle",
        fix = "Key caches by UUID or NamespacedKey instead of live Entity or World references, use WeakHashMap, " +
            "or clear the static field in onDisable and disconnect or unload event handlers.",
    )

    val CLASSLOADER_PINNING_HOOK = Rule(
        id = "J073",
        severity = Severity.CRITICAL,
        title = "JVM hook or unremoved static ThreadLocal pins the plugin classloader",
        rationale = "Registering Runtime.addShutdownHook, DriverManager.registerDriver, or JUL Logger.addHandler " +
            "without deregistration in onDisable, or storing values in a static ThreadLocal without calling " +
            "ThreadLocal.remove(), anchors PaperPluginClassLoader inside JVM global structures or shared " +
            "server and Netty thread maps.",
        provenance = "java.lang.ApplicationShutdownHooks, java.sql.DriverManager, and java.lang.ThreadLocalMap classloader retention",
        fix = "Deregister shutdown hooks, JDBC drivers, and log handlers inside onDisable, and always invoke " +
            "ThreadLocal.remove() in a finally block.",
    )

    val CROSS_PLUGIN_HOOK_OR_PIPELINE_LEAK = Rule(
        id = "J074",
        severity = Severity.CRITICAL,
        title = "Cross-plugin hook, expansion, or Netty pipeline handler leaked across reloads",
        rationale = "Injecting custom ChannelHandler instances into Netty ChannelPipeline without removal in " +
            "onDisable, registering persistent PlaceholderExpansion hooks without unregister() in onDisable, or " +
            "caching foreign Plugin and ServicesManager provider instances inside static fields without clearing " +
            "in onDisable or PluginDisableEvent retains foreign or own PaperPluginClassLoader graphs across " +
            "plugin disable and reload.",
        provenance = "io.netty.channel.ChannelPipeline handler retention, PlaceholderAPI ExpansionManager lifecycle, and Bukkit ServicesManager / PluginManager references",
        fix = "Unregister PlaceholderExpansion instances and remove custom Netty ChannelPipeline handlers in " +
            "onDisable, and clear static cross-plugin provider fields in onDisable and PluginDisableEvent.",
    )

    val PAPER_PLUGIN_REMAPPER_HAZARD = Rule(
        id = "J075",
        severity = Severity.CRITICAL,
        title = "Paper 1.21.4+..26.3+ PluginRemapper hazard in descriptor or bytecode",
        rationale = "Paper's runtime PluginRemapper converts legacy Spigot-mapped and CraftBukkit bytecode " +
            "to Mojang mappings exclusively for legacy plugin.yml plugins. When a plugin declares paper-plugin.yml, " +
            "Paper bypasses PluginRemapper entirely, so any direct or shaded references to net.minecraft.server " +
            "or org.bukkit.craftbukkit fail with LinkageError. Conversely, when a plugin is declared only in plugin.yml " +
            "without paper-plugin.yml, but uses dynamic ClassLoader definition or targets Paper 1.21.4+..26.3+ " +
            "without remapping hooks, unremapped types or dynamic classes bypass runtime remapping.",
        provenance = "PaperMC Paperweight runtime PluginRemapper and PaperPluginClassLoader remapping lifecycle",
        fix = "Compile against Mojang mappings and Bukkit API for paper-plugin.yml plugins, or avoid referencing " +
            "unremapped server internals and dynamic classloader definitions that bypass PluginRemapper.",
    )

    val UNRELOCATED_NMS_REFLECTION_LITERAL = Rule(
        id = "J076",
        severity = Severity.WARNING,
        title = "Unrelocated NMS or CraftBukkit reflection string literal in bytecode",
        rationale = "Bytecode contains String literals in the constant pool specifying internal CraftBukkit " +
            "(org.bukkit.craftbukkit.*) or Minecraft Server (net.minecraft.* / net.minecraft.server.*) class names " +
            "or packages passed to reflection. Paper's PluginRemapper and build tools (Shadow/relocate) only rewrite " +
            "bytecode class and member descriptors, leaving String literals in LDC instructions unmodified. " +
            "On modern Paper (1.20.5+..26.3+), legacy versioned packages (v1_*) and Spigot mappings no longer exist, " +
            "causing ClassNotFoundException or NoSuchFieldException at runtime upon reflection lookup.",
        provenance = "LDC string constants in method bytecode compared against CraftBukkit and NMS package patterns",
        fix = "Replace reflection string literals with compile-time Bukkit/Paper API calls, or use Paperweight " +
            "userdev Mojang mappings with ReflectionRemapper utilities.",
    )

    val all = listOf(
        NOT_A_PLUGIN, DESCRIPTOR_INCOMPLETE, DESCRIPTOR_UNPARSEABLE, MAIN_CLASS_MISSING, NAME_ILLEGAL,
        MAIN_IN_SERVER_NAMESPACE, DESCRIPTOR_UNKNOWN_KEY, API_VERSION_ABSENT, API_VERSION_UNKNOWN,
        UNQUOTED_VERSION_SCALAR, API_VERSION_PAPER_26_SPIGOT_INCOMPATIBLE, BOTH_DESCRIPTORS,
        CLASS_VERSION_TOO_NEW, CLASS_VERSION_MIXED, SERVER_NAMESPACE_BUNDLED, PROVIDED_LIBRARY_BUNDLED,
        UNRELOCATED_LIBRARY, KOTLIN_STDLIB_BUNDLED, BSTATS_UNRELOCATED, LEGACY_VERSIONED_NMS_ON_MODERN_PAPER,
        MOJANG_MAPPED_BYTECODE_IN_LEGACY_DESCRIPTOR, JAR_HYGIENE, SIGNATURE_LEFTOVER, DEPENDENCY_SUSPECT,
        RUNTIME_LIBRARIES_HINT, PAPER_DESCRIPTOR_DRIFT, PAPER_DEPENDENCY_GRAPH_INVALID, UNREACHABLE_BUNDLE,
        FOLIA_DECLARATION, UNCANCELLED_SCHEDULER_TASK, UNMANAGED_THREAD_OR_EXECUTOR_LEAK,
        STATIC_SERVER_ENTITY_RETENTION, CLASSLOADER_PINNING_HOOK, CROSS_PLUGIN_HOOK_OR_PIPELINE_LEAK,
        PAPER_PLUGIN_REMAPPER_HAZARD, UNRELOCATED_NMS_REFLECTION_LITERAL,
    )
}
