package com.salyvn.pluginscope.data

/**
 * What the server already puts on a plugin's classpath, and what a plugin must therefore not ship
 * a second copy of.
 *
 * Every entry was read out of the published `paper-api` POM rather than remembered:
 * https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/26.2.build.116-stable/paper-api-26.2.build.116-stable.pom
 *
 * Versions move; the package roots do not, and the package root is what decides whether a shaded
 * copy collides. The version is kept in the table only so a report can say what the server has.
 */
object ServerRuntime {

    data class ProvidedLibrary(
        /** Internal package root, e.g. `com/google/gson`. */
        val packageRoot: String,
        val artifact: String,
        val versionAtReference: String,
        val provenance: String,
    ) {
        val dotted: String get() = packageRoot.replace('/', '.')
    }

    private const val PAPER_API_POM =
        "paper-api 26.2.build.116-stable POM, compile scope"

    /**
     * Compile-scope dependencies of paper-api. A plugin compiling against paper-api sees all of
     * these, and so does its code at runtime.
     */
    val provided: List<ProvidedLibrary> = listOf(
        ProvidedLibrary("net/kyori/adventure", "net.kyori:adventure-api (+ serializers, minimessage)", "5.2.0", PAPER_API_POM),
        ProvidedLibrary("net/kyori/examination", "net.kyori:examination-api (via adventure-api)", "1.3.0", "adventure-api 5.2.0 POM, compile scope"),
        ProvidedLibrary("net/kyori/option", "net.kyori:option (via adventure-api)", "1.1.0", "adventure-api 5.2.0 POM, compile scope"),
        ProvidedLibrary("com/google/common", "com.google.guava:guava", "33.6.0-jre", PAPER_API_POM),
        ProvidedLibrary("com/google/gson", "com.google.code.gson:gson", "2.14.0", PAPER_API_POM),
        ProvidedLibrary("org/yaml/snakeyaml", "org.yaml:snakeyaml", "2.2", PAPER_API_POM),
        ProvidedLibrary("org/joml", "org.joml:joml", "1.10.8", PAPER_API_POM),
        ProvidedLibrary("it/unimi/dsi/fastutil", "it.unimi.dsi:fastutil", "8.5.18", PAPER_API_POM),
        ProvidedLibrary("org/apache/logging/log4j", "org.apache.logging.log4j:log4j-api", "2.26.0", PAPER_API_POM),
        ProvidedLibrary("org/slf4j", "org.slf4j:slf4j-api", "2.0.17", PAPER_API_POM),
        ProvidedLibrary("com/mojang/brigadier", "com.mojang:brigadier", "1.3.10", PAPER_API_POM),
        ProvidedLibrary("net/md_5/bungee", "net.md-5:bungeecord-chat", "1.21-R0.2-deprecated+build.21", PAPER_API_POM),
        ProvidedLibrary("org/jspecify/annotations", "org.jspecify:jspecify", "1.0.0", PAPER_API_POM),
        ProvidedLibrary("org/checkerframework", "org.checkerframework:checker-qual", "3.49.2", PAPER_API_POM),
        ProvidedLibrary("org/jetbrains/annotations", "org.jetbrains:annotations (via adventure-api)", "-", "adventure-api 5.2.0 POM, compile scope"),
        ProvidedLibrary("org/eclipse/aether", "org.apache.maven:maven-resolver-provider", "3.9.6", PAPER_API_POM),
    )

    /**
     * Transitive library roots known to cause runtime LinkageError, ClassCastException,
     * ServiceConfigurationError or static relocation-guard crashes when shaded unrelocated
     * across multiple plugins on overlapping classloaders.
     */
    val highRiskTransitiveRoots: Map<String, String> = linkedMapOf(
        "org/bstats" to "bStats Metrics enforces relocation at startup and collides on shared classloaders",
        "net/kyori" to "Kyori Adventure types cross the Paper API boundary; unrelocated copies trigger LinkageError",
        "com/zaxxer/hikari" to "HikariCP pool, MBean and SLF4J bindings collide across shared plugin classloaders",
        "com/google/gson" to "Google Gson is provided by Paper; unrelocated copies clash across plugin API calls",
        "org/yaml/snakeyaml" to "SnakeYAML 1.x vs 2.x Constructor and LoaderOptions ABIs break when shaded unrelocated",
        "org/slf4j" to "SLF4J logger bindings split or throw LinkageError when shaded unrelocated",
        "com/github/benmanes/caffeine" to "Caffeine cache node classes clash across overlapping plugin classloaders",
        "io/netty" to "Netty channel and ByteBuf types collide with the server network stack when shaded unrelocated",
    )

    /**
     * Namespaces owned by the server, Mojang or a server fork. A plugin jar containing these is
     * shipping someone else's code and shadowing the classes the server itself loaded.
     */
    val serverNamespaces: List<Pair<String, String>> = listOf(
        "org/bukkit" to "Bukkit API, provided by the server",
        "io/papermc/paper" to "Paper API, provided by the server",
        "com/destroystokyo/paper" to "legacy Paper API, provided by the server",
        "org/spigotmc" to "Spigot API, provided by the server",
        "net/minecraft" to "Minecraft server internals",
        "com/mojang/authlib" to "Mojang authlib, provided by the server",
        "com/mojang/datafixers" to "Mojang DataFixerUpper, provided by the server",
        "org/bukkit/craftbukkit" to "CraftBukkit implementation classes",
    )

    /** The Kotlin runtime is not provided by any current server, so a Kotlin plugin must ship it. */
    val kotlinRuntimeRoots = listOf("kotlin", "kotlinx")

    fun providedFor(internalName: String): ProvidedLibrary? =
        provided.firstOrNull { internalName == it.packageRoot || internalName.startsWith(it.packageRoot + "/") }

    fun isServerNamespace(internalName: String): Boolean =
        serverNamespaces.any { (root, _) -> internalName == root || internalName.startsWith("$root/") }

    fun serverNamespaceReason(internalName: String): String? =
        serverNamespaces.firstOrNull { (root, _) -> internalName == root || internalName.startsWith("$root/") }?.second

    fun isKotlinRuntime(internalName: String): Boolean =
        kotlinRuntimeRoots.any { internalName == it || internalName.startsWith("$it/") }

    fun isBstats(internalName: String): Boolean =
        internalName == "org/bstats" || internalName.startsWith("org/bstats/")

    fun highRiskRootFor(internalName: String): Pair<String, String>? =
        highRiskTransitiveRoots.entries
            .firstOrNull { (root, _) -> internalName == root || internalName.startsWith("$root/") }
            ?.toPair()
}
