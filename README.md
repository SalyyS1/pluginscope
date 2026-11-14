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

## What is new in v0.2.0

- **Transitive Shading Collision Detection (`J024`, `S032`)**: Detects single-JAR unrelocated `org.bstats` (`J024`, catching bStats's static `IllegalStateException` relocation guard even on 1-class bundles) and multi-plugin transitive shading collisions (`S032`) across `org.bstats`, `net.kyori`, `com.zaxxer.hikari`, `com.google.gson`, `org.yaml.snakeyaml`, `org.slf4j`, `com.github.benmanes.caffeine`, and `io.netty`, backed by method/field/bytecode-version ABI fingerprinting.
- **Paper Isolated Classloader & Descriptor Drift Audit (`J042`, `S003`, `S004`)**:
  - `J042` catches dual-descriptor JARs (`paper-plugin.yml` + `plugin.yml`) where dependencies or Maven `libraries:` exist in `plugin.yml` but were omitted from `paper-plugin.yml`, missing `bootstrapper` / `loader` classes, and `dependencies.bootstrap`-only traps.
  - `S003` scans cross-plugin bytecode calls across `plugins/` and flags plugins that reference another installed plugin's classes without declaring a dependency (`critical` under Paper's isolated `PaperPluginClassLoader`).
  - `S004` flags `join-classpath: false` / `dependencies.bootstrap`-only callers and legacy `plugin.yml` plugins accessing `paper-plugin.yml` targets that set `has-open-classloader: false`.
- **GitHub Code Scanning SARIF v2.1.0 & Composite Action (`action.yml`, `--sarif`)**: Repo-relative `artifactLocation.uri`, `uriBaseId: "%SRCROOT%"`, `region.startLine`, `ruleIndex`, Markdown remediation help, and simultaneous console + `--sarif <file>` output.
- **Zero-Backend Browser JAR Inspector (`docs/index.html`)**: Client-side ZIP central directory + `DecompressionStream('deflate-raw')` + JVM `0xCAFEBABE` constant-pool analyzer with SARIF/JSON export.

## Requirements

Java 21 or newer. The release JAR is self-contained.

## Install

Download `pluginscope-0.2.0.jar` from [GitHub Releases](https://github.com/SalyyS1/pluginscope/releases) and run:

```bash
java -jar pluginscope-0.2.0.jar --help
```

## Quick start

```bash
# Audit one plugin JAR against a target Minecraft server version
java -jar pluginscope-0.2.0.jar jar MyPlugin.jar --target 26.2

# Check API compatibility against Paper 1.21.4 (downloads and caches paper-api once)
java -jar pluginscope-0.2.0.jar compat MyPlugin.jar --mc 1.21.4

# Audit an entire server plugins/ folder for classloader blocks and shading collisions
java -jar pluginscope-0.2.0.jar stack /srv/mc/plugins

# Emit console output AND write OASIS SARIF v2.1.0 in a single run
java -jar pluginscope-0.2.0.jar stack /srv/mc/plugins --sarif pluginscope.sarif

# Validate a resource pack directory or zip
java -jar pluginscope-0.2.0.jar pack pack.zip --mc 26.2

# Print all 70 rules with rationale, remediation, and primary source provenance
java -jar pluginscope-0.2.0.jar rules
```

## GitHub Actions & Code Scanning (SARIF v2.1.0)

Use the composite action `SalyyS1/pluginscope@v0.2.0` to gate pull requests and publish shading, packaging, and Paper classloader findings directly to the GitHub **Security -> Code scanning** tab:

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
        uses: SalyyS1/pluginscope@v0.2.0
        with:
          command: jar
          path: build/libs
          target: '1.21.4'
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
$ java -jar pluginscope-0.2.0.jar jar build/libs/CrateMaster.jar --target 1.21.4

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
$ java -jar pluginscope-0.2.0.jar stack /srv/mc/plugins

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
      "args": ["-jar", "/absolute/path/to/pluginscope-0.2.0.jar", "mcp"]
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

Produces `build/libs/pluginscope-0.2.0.jar` (self-contained shaded CLI) and runs the full JUnit 6 suite against compiled bytecode fixtures.

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

pluginscope is an independent project by [SalyVn](https://github.com/SalyyS1). It is not affiliated with, endorsed by, or sponsored by PaperMC, Mojang, or Microsoft.
