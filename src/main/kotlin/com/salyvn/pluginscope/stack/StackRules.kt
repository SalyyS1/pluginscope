package com.salyvn.pluginscope.stack

import com.salyvn.pluginscope.core.Rule
import com.salyvn.pluginscope.core.Severity

/** Checks that only make sense when a whole `plugins/` directory is looked at at once. */
object StackRules {

    val MISSING_HARD_DEPENDENCY = Rule(
        id = "S001",
        severity = Severity.BLOCKER,
        title = "Hard dependency is not installed",
        rationale = "A required dependency that is absent stops the depending plugin from being " +
            "enabled at all, and the log line for it is easy to miss during a big startup.",
        provenance = "depend / dependencies of each descriptor, compared with the plugin set",
        fix = "Install the dependency, or make it a soft dependency if the plugin can run without it.",
    )

    val DEPENDENCY_CYCLE = Rule(
        id = "S002",
        severity = Severity.CRITICAL,
        title = "Hard dependencies form a cycle",
        rationale = "The loader cannot order a cycle, so at least one plugin in it is skipped.",
        provenance = "Cycle search over the hard-dependency graph",
        fix = "Break the cycle: one direction becomes a soft dependency.",
    )

    val UNDECLARED_PLUGIN_DEPENDENCY = Rule(
        id = "S003",
        severity = Severity.CRITICAL,
        title = "Bytecode references an installed plugin without declaring a dependency",
        rationale = "For paper-plugin.yml plugins, PaperPluginClassLoader isolates classloaders and " +
            "refuses to load classes from undeclared plugins, throwing NoClassDefFoundError even when " +
            "the target plugin is installed. For legacy plugin.yml plugins, load order becomes arbitrary.",
        provenance = "Cross-plugin bytecode references compared with descriptor dependency declarations",
        fix = "Declare the target plugin under dependencies.server (paper-plugin.yml) or depend/softdepend (plugin.yml).",
    )

    val ISOLATED_CLASSLOADER_BLOCKED = Rule(
        id = "S004",
        severity = Severity.CRITICAL,
        title = "Paper isolated classloader blocks cross-plugin class access",
        rationale = "Paper blocks runtime class access when a paper-plugin.yml plugin sets " +
            "join-classpath: false or declares a dependency only under dependencies.bootstrap, or " +
            "when a legacy plugin.yml plugin accesses a paper-plugin.yml plugin that sets " +
            "has-open-classloader: false.",
        provenance = "PaperMC/Paper PaperPluginClassLoader and SpigotPluginClassLoader visibility rules",
        fix = "Set join-classpath: true under dependencies.server in paper-plugin.yml, or set " +
            "has-open-classloader: true on the target Paper plugin.",
    )

    val DUPLICATE_PLUGIN_NAME = Rule(
        id = "S010",
        severity = Severity.CRITICAL,
        title = "Two jars declare the same plugin name",
        rationale = "Plugin names are the identity the server keys everything off. With two " +
            "candidates, one is refused and which one that is depends on directory order.",
        provenance = "`name` of each descriptor in the directory",
        fix = "Remove the stale jar. This is usually an old version left behind after an update.",
    )

    val DUPLICATE_MAIN_CLASS = Rule(
        id = "S011",
        severity = Severity.CRITICAL,
        title = "Two jars declare the same main class",
        rationale = "Two jars carrying the same main class are almost always two builds of one " +
            "plugin, and the classes of both end up on the server.",
        provenance = "`main` of each descriptor in the directory",
        fix = "Keep one build.",
    )

    val NAME_SHADOWED_BY_PROVIDES = Rule(
        id = "S012",
        severity = Severity.WARNING,
        title = "A plugin name is also claimed through `provides`",
        rationale = "`provides` makes a plugin answer to another name for dependency resolution. " +
            "When the real plugin is installed too, dependants may bind to either.",
        provenance = "`provides` compared with the installed plugin names",
        fix = "Install only one of the two, or remove the `provides` claim.",
    )

    val COMMAND_COLLISION = Rule(
        id = "S020",
        severity = Severity.WARNING,
        title = "Two plugins register the same command",
        rationale = "Only one plugin owns the plain command name. The other is reachable only " +
            "through its namespaced form, which players never type.",
        provenance = "`commands` blocks of the legacy descriptors",
        fix = "Rename one command, or set the winner explicitly in the server's commands.yml aliases.",
    )

    val ALIAS_COLLISION = Rule(
        id = "S021",
        severity = Severity.INFO,
        title = "A command alias is claimed by more than one plugin",
        rationale = "Same as a command collision, but for an alias, and the other claimant may be " +
            "a plugin whose real command carries that name. It surfaces as 'that shortcut opens " +
            "the wrong menu' rather than as an error.",
        provenance = "`aliases` of each declared command, compared with the declared command names",
        fix = "Drop the overlapping alias from one of the plugins.",
    )

    val SHARED_LIBRARY_SKEW = Rule(
        id = "S030",
        severity = Severity.WARNING,
        title = "Several plugins ship the same unrelocated library, in different builds",
        rationale = "The package is exposed under its original name by more than one plugin and " +
            "the class sets differ, so the copy that answers a given class depends on load order.",
        provenance = "Foreign package roots of every jar, fingerprinted by their class list",
        fix = "Relocate the library in your own plugins. For third-party jars, report it upstream.",
    )

    val SHARED_LIBRARY_DUPLICATED = Rule(
        id = "S031",
        severity = Severity.INFO,
        title = "The same library is bundled by several plugins",
        rationale = "Identical copies are only wasted space, but they are the reason a plugins " +
            "folder is often several times larger than it needs to be.",
        provenance = "Foreign package roots shared by more than one jar",
        fix = "Nothing urgent. Runtime libraries or a shared library plugin would remove the copies.",
    )

    val TRANSITIVE_SHADING_COLLISION = Rule(
        id = "S032",
        severity = Severity.CRITICAL,
        title = "High-risk transitive library shaded unrelocated across plugins",
        rationale = "Unrelocated copies of bStats, Kyori Adventure, HikariCP, Gson, SnakeYAML, " +
            "SLF4J, Caffeine or Netty across multiple plugins collide on overlapping plugin " +
            "classloaders, causing LinkageError, ClassCastException, ServiceConfigurationError " +
            "or bStats relocation-guard crashes at runtime.",
        provenance = "Unrelocated high-risk transitive package roots fingerprinted by class ABI across installed jars",
        fix = "Relocate shaded transitive libraries under each plugin's own package, or declare " +
            "shared Maven coordinates in the descriptor `libraries:` section.",
    )

    val SERVICE_PROVIDER_SHADING_CONFLICT = Rule(
        id = "S033",
        severity = Severity.CRITICAL,
        title = "Service provider interface or registration shaded across multiple plugins",
        rationale = "When multiple plugins bundle the same Service provider interface (e.g. from " +
            "META-INF/services/ or Bukkit ServicesManager/RegisteredServiceProvider) without relocation, " +
            "service registrations across plugin classloaders fail with ClassCastException or return null " +
            "because ClassLoader A's Service.class does not equal ClassLoader B's Service.class.",
        provenance = "META-INF/services provider interfaces and Bukkit ServicesManager registration types compared across installed plugins",
        fix = "Relocate the service interface and provider under each plugin's namespace, or place the API in a common shared library plugin.",
    )

    val EVENT_HANDLER_SHADING_CONFLICT = Rule(
        id = "S034",
        severity = Severity.CRITICAL,
        title = "Custom Bukkit Event class shaded across multiple plugins causes dropped events",
        rationale = "Bukkit custom Event classes define static getHandlerList() and getHandlers() methods. " +
            "When two plugins shade the same custom Event class unrelocated, each plugin's classloader has a " +
            "separate HandlerList instance. An event fired by Plugin A will never trigger @EventHandler listeners " +
            "registered in Plugin B, causing silent event dropping.",
        provenance = "Classes extending org.bukkit.event.Event or declaring static HandlerList getHandlerList() compared across installed jars",
        fix = "Relocate custom Event classes or share them through a single common API dependency with join-classpath: true or depend.",
    )

    val JAVA_REQUIREMENT = Rule(
        id = "S040",
        severity = Severity.INFO,
        title = "Java release required by this plugin set",
        rationale = "The highest class-file version across every jar is the minimum Java the " +
            "server must run, whatever the server itself needs.",
        provenance = "Class file major versions of every class in the directory",
        fix = "Run at least this Java release, or replace the plugin that raises the floor.",
    )

    val LEGACY_PLUGIN_PRESENT = Rule(
        id = "S041",
        severity = Severity.WARNING,
        title = "Plugin loads in legacy mode",
        rationale = "A plugin with no api-version is treated as pre-1.13 and has legacy data " +
            "conversion applied to it, which is a behaviour difference, not just a warning.",
        provenance = "Missing api-version in the descriptor",
        fix = "Update the plugin, or ask its author to declare an api-version.",
    )

    val MISSING_SOFT_DEPENDENCY = Rule(
        id = "S050",
        severity = Severity.INFO,
        title = "Soft dependency or load-order target is not installed",
        rationale = "Not a fault: it records which optional integrations of the installed plugins " +
            "are inactive on this server.",
        provenance = "softdepend / loadbefore compared with the plugin set",
        fix = "Install the plugin if you expected the integration to be active.",
    )

    val FOLIA_MIX = Rule(
        id = "S060",
        severity = Severity.WARNING,
        title = "Plugin does not declare Folia support",
        rationale = "Under regionised threading the server refuses to load plugins that do not " +
            "declare support, so on Folia this plugin is simply absent.",
        provenance = "folia-supported key of each descriptor",
        fix = "Only relevant if the server runs Folia. Check for a Folia build of the plugin.",
    )

    val all = listOf(
        MISSING_HARD_DEPENDENCY, DEPENDENCY_CYCLE, UNDECLARED_PLUGIN_DEPENDENCY,
        ISOLATED_CLASSLOADER_BLOCKED, DUPLICATE_PLUGIN_NAME, DUPLICATE_MAIN_CLASS,
        NAME_SHADOWED_BY_PROVIDES, COMMAND_COLLISION, ALIAS_COLLISION, SHARED_LIBRARY_SKEW,
        SHARED_LIBRARY_DUPLICATED, TRANSITIVE_SHADING_COLLISION, SERVICE_PROVIDER_SHADING_CONFLICT,
        EVENT_HANDLER_SHADING_CONFLICT, JAVA_REQUIREMENT,
        LEGACY_PLUGIN_PRESENT, MISSING_SOFT_DEPENDENCY, FOLIA_MIX,
    )
}
