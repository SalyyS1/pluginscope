# pluginscope

[![build](https://github.com/SalyyS1/pluginscope/actions/workflows/build.yml/badge.svg)](https://github.com/SalyyS1/pluginscope/actions/workflows/build.yml)
[![Web Inspector](https://img.shields.io/badge/web_inspector-client--side_JAR_audit-0284c7)](https://salyys1.github.io/pluginscope/)

Static audits for Minecraft server plugins. It reads build output (`*.jar`, resource packs, and an entire `plugins/` directory) and reports what will fail at load time or runtime without starting a server and without executing untrusted plugin code.

Nothing inspected is ever loaded into the JVM: class files are parsed as raw bytecode via ASM, `plugin.yml` and `paper-plugin.yml` descriptors are read through `SafeConstructor` with YAML alias expansion capped, and resource packs are inspected directly from disk or zip archives.

Try the zero-backend browser `.jar` classloader and shading inspector at **[salyys1.github.io/pluginscope](https://salyys1.github.io/pluginscope/)**.

Four commands, four questions:

| Command | Question it answers |
| --- | --- |
| `jar` | Is this JAR packaged cleanly? Did it leave `org.bstats` unrelocated, drift between `paper-plugin.yml` and `plugin.yml`, shade server-provided libraries, or compile for the wrong Java release? |
| `compat` | Will it run against a target Minecraft version's API? Which classes and members does its bytecode call that were removed or changed? |
| `stack` | What breaks when this `plugins/` folder is installed together: Paper isolated classloader blocks, undeclared cross-plugin bytecode references, transitive shading collisions (`bStats`, `Adventure`, `HikariCP`, `Gson`, `SnakeYAML`), missing dependencies, or command clashes? |
| `pack` | Will the resource pack it ships actually render, or will it load and silently fall back to missing textures? |

`pluginscope mcp` also serves all analyses over the Model Context Protocol on `stdio` so coding agents can audit plugin artifacts directly.

## What is new in v0.7.0

- Paper 26.3 block-state and data component codec verification (`J077`):
  - Flags runtime mutations or `Registry.register` calls on frozen Mojang `MappedRegistry` / `BuiltInRegistries` (`DATA_COMPONENT_TYPE`, `BLOCK`, `BLOCK_ENTITY_TYPE`), which fail after Paper's pre-enable freeze barrier or corrupt clientbound `RegistrySynchronization` codecs.
  - Detects removed legacy numeric block-state byte and `MaterialData` APIs (`BlockState#setRawData`, `Block#setData`) when targeting modern Paper (`1.20.5+..26.3+`).
  - Performs intra-procedural ASM operand-stack and local-variable dataflow tracking on detached `Block#getState()` snapshots, warning when `TileState` / `PersistentDataContainer` / `SignSide` / `BlockState` mutations occur without calling `BlockState#update(...)` or returning the snapshot (while recognizing Paper's live `block.getState(false)` accessor as safe).
- Shaded ASM `ClassWriter(COMPUTE_FRAMES)` stack-map-table hazard scanner (`J078`):
  - Tracks constant integer bitmasks (`ClassWriter.COMPUTE_FRAMES`, bit `0x2`) passed to `org.objectweb.asm.ClassWriter` (and relocated `*/asm/ClassWriter` hierarchies) across bytecode instructions.
  - Flags `ClassWriter` instantiations with `COMPUTE_FRAMES` that do not override `getCommonSuperClass(String, String)`, preventing `TypeNotPresentException`, `ClassNotFoundException`, and classloader deadlocks across isolated `PaperPluginClassLoader` boundaries on Java 21 and Java 25.
- Cross-plugin `EventPriority` cancellation and `MONITOR` mutation detector (`S072`):
  - Parses `@EventHandler(priority, ignoreCancelled)` annotations and listener bytecode across all installed plugins in `plugins/`.
  - Reports `CRITICAL` violations when a plugin calls `setCancelled` or mutates event results inside a read-only `EventPriority.MONITOR` handler while co-installed plugins listen to the same event, and `WARNING` findings on uncoordinated same-priority cancel races or `HIGH`/`HIGHEST` `setCancelled(false)` overrides lacking explicit load-order edges.
- Interactive browser `.jar` inspector upgrade (`docs/index.html`):
  - Client-side JVM method `Code` instruction and `RuntimeVisibleAnnotations` inspector detecting `J077`, `J078`, and `S072` locally in the browser with an in-memory 3-JAR v0.7.0 binary demo.

## Previous highlights in v0.6.0

- Paper 1.21.4+..26.3+ PluginRemapper hazard analysis (`J075`):
  - Detects `paper-plugin.yml` plugins containing direct or shaded references to `org.bukkit.craftbukkit` or `net.minecraft.server`. Modern Paper (1.20.5+..26.3+) bypasses `PluginRemapper` for `paper-plugin.yml` plugins, so unremapped server internals fail at runtime with `ClassNotFoundException` / `LinkageError`.
  - Flags legacy `plugin.yml` plugins targeting modern Paper (1.21.4+..26.3+) that define classes dynamically via custom `ClassLoader` or `MethodHandles.Lookup#defineClass`, bypassing the runtime remapper.
- Unrelocated NMS and CraftBukkit reflection string literal scanner (`J076`):
  - Scans constant-pool `LDC` instructions across method bytecode for raw String literals referencing `net.minecraft.*` or `org.bukkit.craftbukkit.*`. Build relocation tools (Shadow/relocate) and runtime remappers only rewrite bytecode class descriptors, leaving string literals unmodified and failing upon reflection lookup on modern Paper.
- Transitive Multi-Release JAR classfile collision detector (`S071`):
  - Audits Multi-Release JAR (MR-JAR, JEP 238) structures across `plugins/` directories. Identifies collisions where multiple plugins bundle conflicting classfiles under `META-INF/versions/` (e.g. Java 11 vs Java 17) or mix versioned overrides with unversioned base classes with mismatched method ABI, plus stripped `Multi-Release: true` manifests.
- Interactive multi-JAR classloader and dependency graph visualizer on GitHub Pages (`docs/index.html`):
  - Client-side visualizer rendering Paper isolated classloader domains, open vs closed classloader boundaries, Multi-Release Java tiers, and dependency edges with a zero-upload binary demo.

## Previous highlights in v0.5.0

- Cross-plugin ASM dataflow leak analysis (`J074`, `S070`):
  - `J074`: Single-JAR cross-plugin hook and pipeline leak audit catching `PlaceholderExpansion#register()` without `unregister()` in `onDisable`, Netty `ChannelPipeline#addBefore/addAfter/addFirst/addLast` injections without `remove` or `replace` in `onDisable`, and `ServicesManager#load` / `getRegistration` / `PluginManager#getPlugin` return values retained in `static` fields or collections without cleanup in `onDisable` or `PluginDisableEvent` / `ServiceUnregisterEvent`.
  - `S070`: Multi-JAR inter-plugin ASM operand-stack dataflow across `plugins/` sets. Identifies static registry sink methods in target plugins (where formal parameters flow into `PUTSTATIC` or static collection `add`/`put`) and flags caller plugins passing own instances or `invokedynamic` lambdas without invoking cleanup in `onDisable`, plus direct cross-plugin `static` field/collection retention of foreign plugin classes.
- `paper-plugin.yml` multi-loader dependency graph and transitive classloader exposure (`J043`, `S005`, `S006`, `S007`):
  - `J043`: Validates `paper-plugin.yml` dependency option schemas (`load: BEFORE|AFTER|OMIT`, boolean `required` and `join-classpath`), top-level `load: STARTUP|POSTWORLD`, colliding `main`/`bootstrapper`/`loader` entrypoints, contradictory `bootstrap` vs `server` `BEFORE`/`AFTER` edges, and dual-descriptor `plugin.yml` vs `paper-plugin.yml` opposite load directions.
  - `S005`: Flags `dependencies.bootstrap` entries in `paper-plugin.yml` targeting installed legacy `plugin.yml` plugins (absent from Paper's bootstrap provider storage).
  - `S006`: Detects topological load-order cycles across `paper-plugin.yml` (`bootstrap` and `server` `BEFORE`/`AFTER`) and `plugin.yml` (`depend`, `softdepend`, `loadbefore`, `provides`), plus `STARTUP` plugins depending on `POSTWORLD` plugins.
  - `S007`: Detects non-transitive `PaperPluginClassLoader` API exposure when Plugin A joins Plugin B's classpath and calls a method, field, or supertype on Plugin B that exposes types owned by an isolated third Plugin C.
- Zero-backend browser inspector upgrade (`docs/index.html`): Client-side detection for `J043`, `J074`, `S005`, `S006`, `S007`, and `S070` with a new interactive multi-JAR cross-plugin leak and multi-loader dependency graph binary demo.

## Previous highlights in v0.4.0

- ASM bytecode dataflow and `onDisable` call-graph leak analysis (`J070` - `J073`):
  - `J070`: Tracks `BukkitTask` and Folia `ScheduledTask` handles across operand stacks and local variables, flagging repeating tasks discarded via `POP` in dynamic code paths or accumulated in `static` fields and collections without cancellation and clearing in `onDisable` (suppressing self-cancelling `BukkitRunnable` subclasses and helper/lambda shutdown chains).
  - `J071`: Detects unmanaged `Executors.new*` pools, `ThreadPoolExecutor`, `java.util.Timer`, and `Thread.start()` calls whose worker threads pin `PaperPluginClassLoader` in JVM GC roots when not shut down in `onDisable` (while allowing method-scoped `ExecutorService.close()` usage).
  - `J072`: Audits `static` fields and generic signatures retaining live `Player`, `Entity`, `World`, `Chunk`, or `BlockState` references without `onDisable` or `PlayerQuitEvent` / `WorldUnloadEvent` cleanup (allowing `UUID` keys and `WeakHashMap`).
  - `J073`: Flags JVM classloader-pinning hooks (`Runtime.addShutdownHook`, `DriverManager.registerDriver`, `Logger.addHandler` without deregistration in `onDisable`) and unremoved `static ThreadLocal` fields.
- Zero-backend browser inspector upgrade (`docs/index.html`): Parses `.class` static field signatures, method tables, and `Methodref` constant-pool entries locally in the browser to detect `J070` - `J073` with a live binary leak demo.

## Previous highlights in v0.3.0

- Paper 26.3+ api-version and Mojang-mapped bytecode verification (J014, J025, J026):
  - J014: Flags legacy plugin.yml descriptors declaring modern Paper api-version 26.3+. Paper 26.3 requires paper-plugin.yml for modern Paper features and classloader isolation, while Spigot rejects 26.x declarations.
  - J025: Detects references to legacy versioned NMS or CraftBukkit packages (net.minecraft.server.v1_... or org.bukkit.craftbukkit.v1_...) when targeting modern Paper 26.x+ (Java 25+), where all legacy versioned relocation shims have been removed.
  - J026: Flags unversioned Mojang-mapped net.minecraft bytecode calls inside legacy plugin.yml plugins without paper-plugin.yml, preventing runtime ClassNotFoundException crashes on Spigot and pre-1.20.5 servers.
- Cross-plugin service and event shading conflict matrix (S033, S034):
  - S033: Scans META-INF/services/ provider interfaces and Bukkit ServicesManager registrations across the plugin set and flags service interfaces shaded across multiple plugins without relocation, preventing split service registration lookups.
  - S034: Identifies custom Bukkit Event classes shaded unrelocated across multiple plugins, which causes separate static HandlerList instances on independent classloaders and silently drops fired events.
  - Generates cross-plugin service and event conflict matrices in scan reports and the browser inspector.
- Zero-backend browser inspector upgrade (docs/index.html): Interactive detection for J014, J025, J026, S033, and S034 with live classloader conflict tables.

## Requirements

Java 21 or newer. The release JAR is self-contained.

## Install

Download `pluginscope-0.7.0.jar` from [GitHub Releases](https://github.com/SalyyS1/pluginscope/releases) and run:

```bash
java -jar pluginscope-0.7.0.jar --help
```

## Quick start

```bash
# Audit one plugin JAR against a target Minecraft server version
java -jar pluginscope-0.7.0.jar jar MyPlugin.jar --target 26.3

# Check API compatibility against Paper 1.21.4 (downloads and caches paper-api once)
java -jar pluginscope-0.7.0.jar compat MyPlugin.jar --mc 1.21.4

# Audit an entire server plugins/ folder for classloader blocks and shading collisions
java -jar pluginscope-0.7.0.jar stack /srv/mc/plugins

# Emit console output AND write OASIS SARIF v2.1.0 in a single run
java -jar pluginscope-0.7.0.jar stack /srv/mc/plugins --sarif pluginscope.sarif

# Validate a resource pack directory or zip
java -jar pluginscope-0.7.0.jar pack pack.zip --mc 26.3

# Print all rules with rationale, remediation, and primary source provenance
java -jar pluginscope-0.7.0.jar rules
```

## GitHub Actions & Code Scanning (SARIF v2.1.0)

Use the composite action `SalyyS1/pluginscope@v0.7.0` to gate pull requests and publish shading, packaging, and Paper classloader findings directly to the GitHub **Security -> Code scanning** tab:

```yaml
name: Plugin Audit
on: [push, pull_request]

permissions:
  contents: read
  security-events: write

jobs:
  pluginscope:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Build plugin JAR
        run: ./gradlew build --no-daemon

      - name: Run PluginScope static audit
        uses: SalyyS1/pluginscope@v0.7.0
        with:
          command: jar
          path: build/libs
          target: '26.3'
          fail-on: never
          sarif-file: pluginscope.sarif

      - name: Upload SARIF to GitHub Code Scanning
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: pluginscope.sarif
```

## Sample terminal output

Single-JAR audit catching unrelocated `org.bstats`, dual-descriptor dependency drift between `plugin.yml` and `paper-plugin.yml`, server-provided Gson bundling, and leftover JAR signatures:

```text
$ java -jar pluginscope-0.3.0.jar jar build/libs/CrateMaster.jar --target 1.21.4

jar: /workspace/build/libs/CrateMaster.jar
  plugin       CrateMaster
  version      3.0.0
  descriptor   paper-plugin.yml
  api-version  1.21.4
  size         0.18 MiB
  classes      24 (4 own)
  needs java   21
  own package  com.acme.crates
  kotlin       no

[critical] J024  bStats Metrics shaded without relocation
    org.bstats
      2 bStats class(es) (9 KiB) shipped under org.bstats instead of com.acme.crates.
        org.bstats.bukkit.Metrics
        org.bstats.charts.SimplePie
    fix: Relocate org.bstats under your own package in ShadowJar or maven-shade-plugin.

[critical] J042  paper-plugin.yml omits dependencies or entrypoints required on Paper
    paper-plugin.yml vs plugin.yml
      plugin.yml declares dependency(ies) [Vault, PlaceholderAPI] missing or isolated (join-classpath: false) in paper-plugin.yml dependencies.server; Paper ignores plugin.yml and blocks classloader access.
        plugin.yml depend: Vault
        plugin.yml softdepend: PlaceholderAPI
    fix: Declare every runtime plugin dependency under dependencies.server with join-classpath: true in paper-plugin.yml, and ensure bootstrapper/loader classes exist.

[warning] J021  Bundles a library the server already provides
    com.google.gson
      6 classes, 28 KiB. The server provides com.google.code.gson:gson 2.14.0.
    fix: Depend on it as compileOnly, or relocate it if you genuinely need a different version.
```

Multi-JAR `stack` audit catching cross-plugin `PaperPluginClassLoader` isolation failures and unrelocated HikariCP + bStats collisions across installed plugins:

```text
$ java -jar pluginscope-0.3.0.jar stack /srv/mc/plugins

plugins directory: /srv/mc/plugins
  jars             3
  plugins          3
  total size       1.4 MiB
  target           Paper
    LegacyAuctions 0.9    api 1.20.4  java 21
    SkyShop        1.4.2  api 1.21.4  java 21  folia
    VaultCore      2.1.0  api 1.21.4  java 21  folia

[critical] S003  Bytecode references an installed plugin without declaring a dependency
    SkyShop -> VaultCore
      SkyShop (paper-plugin.yml) references VaultCore classes without declaring VaultCore in dependencies.server; PaperPluginClassLoader throws NoClassDefFoundError.
        com.acme.skyshop.SkyShopPlugin#onEnable:42 -> com.acme.vaultcore.LedgerApi#deposit()V
    fix: Declare the target plugin under dependencies.server (paper-plugin.yml) or depend/softdepend (plugin.yml).

[critical] S004  Paper isolated classloader blocks cross-plugin class access
    LegacyAuctions -> VaultCore
      Legacy plugin.yml plugin LegacyAuctions depends on Paper plugin VaultCore, which sets has-open-classloader: false and blocks class visibility.
    fix: Set join-classpath: true under dependencies.server in paper-plugin.yml, or set has-open-classloader: true on the target Paper plugin.

[critical] S032  High-risk transitive library shaded unrelocated across plugins
    com.zaxxer.hikari
      High-risk transitive library com.zaxxer.hikari shaded by 2 plugins in 2 incompatible ABI builds.
        SkyShop: 28 classes, 152 KiB
        VaultCore: 24 classes, 138 KiB
        HikariCP pool, MBean and SLF4J bindings collide across shared plugin classloaders
    fix: Relocate shaded transitive libraries under each plugin's own package, or declare shared Maven coordinates in the descriptor `libraries:` section.
```

## Severity tiers

| Severity | Meaning |
| --- | --- |
| `blocker` | The artifact will not load at all, or is rejected before `onEnable`. |
| `critical` | It loads, then fails at runtime (`NoClassDefFoundError`, `LinkageError`, `IllegalStateException`, `SecurityException`) when the affected path executes. |
| `warning` | It works today by accident of load order, server version, or installed neighbours. |
| `info` | Diagnostic context worth human attention; no defect is claimed. |

`--fail-on` sets the lowest severity that causes a non-zero exit code (`critical` by default; `never` always exits `0`). Every rule is documented in [docs/rules.md](docs/rules.md).

## Using it from an AI agent (MCP)

`pluginscope mcp` speaks the Model Context Protocol over `stdio`:

```json
{
  "mcpServers": {
    "pluginscope": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/pluginscope-0.7.0.jar", "mcp"]
    }
  }
}
```

Exposed read-only tools: `scan_jar`, `check_api_compat`, `audit_plugins_dir`, `validate_pack`, and `list_rules`.

## Companion Minecraft Toolchain

| Tool | Purpose |
| --- | --- |
| [foliascan](https://github.com/SalyyS1/foliascan) | Bytecode and regional taint-flow verifier for Folia multithreading (`CLI`, SARIF GitHub Action, and [browser JAR scanner](https://salyys1.github.io/foliascan/)). |
| [serverdoctor](https://github.com/SalyyS1/serverdoctor) | Offline diagnostics for Folia region stalls, G1GC/ZGC pauses, Paper Moonrise chunk I/O, configs, and crash reports (`CLI`, `mcp`, and [browser log analyzer](https://salyys1.github.io/serverdoctor/)). |
| [minecraftkit](https://github.com/SalyyS1/minecraftkit) | Offline Paper/Folia API catalog, `/mc:*` coding agent skills, [web API explorer](https://salyys1.github.io/minecraftkit/), and stdio MCP server. |
| [mcstage](https://github.com/SalyyS1/mcstage) | Standalone harness that boots a real Paper, Folia, or Purpur server, installs plugins, drives console commands, and asserts on logs. |
| [glyphsmith](https://github.com/SalyyS1/glyphsmith) | Resource pack font compiler turning PNG sprites into Minecraft bitmap glyphs and negative-space shifts. |

## Building from source

```bash
./gradlew test shadowJar --no-daemon
```

Produces `build/libs/pluginscope-0.7.0.jar` (self-contained shaded CLI) and runs the full JUnit 6 suite against compiled bytecode fixtures.

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

pluginscope is an independent project by [SalyVn](https://github.com/SalyyS1). It is not affiliated with, endorsed by, or sponsored by PaperMC, Mojang, or Microsoft.
