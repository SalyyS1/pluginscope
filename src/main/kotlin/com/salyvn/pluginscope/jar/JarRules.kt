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

    val all = listOf(
        NOT_A_PLUGIN, DESCRIPTOR_INCOMPLETE, DESCRIPTOR_UNPARSEABLE, MAIN_CLASS_MISSING, NAME_ILLEGAL,
        MAIN_IN_SERVER_NAMESPACE, DESCRIPTOR_UNKNOWN_KEY, API_VERSION_ABSENT, API_VERSION_UNKNOWN,
        UNQUOTED_VERSION_SCALAR, BOTH_DESCRIPTORS, CLASS_VERSION_TOO_NEW, CLASS_VERSION_MIXED,
        SERVER_NAMESPACE_BUNDLED, PROVIDED_LIBRARY_BUNDLED, UNRELOCATED_LIBRARY,
        KOTLIN_STDLIB_BUNDLED, JAR_HYGIENE, SIGNATURE_LEFTOVER, DEPENDENCY_SUSPECT,
        RUNTIME_LIBRARIES_HINT, UNREACHABLE_BUNDLE, FOLIA_DECLARATION,
    )
}
