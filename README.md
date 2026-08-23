# pluginscope

Static audits for Minecraft server plugins. It reads build output — jars, packs, a `plugins/`
directory — and reports what will go wrong, without starting a server and without running any of
the code it looks at.

That last point is the reason it exists in this shape. A server owner can point it at a paid plugin
they did not compile, and a developer can put the same binary in CI, because nothing is loaded into
the JVM: class files are read as bytes, descriptors are parsed with YAML aliasing capped, packs are
read as files.

Four commands, four questions:

| Command | Question it answers |
| --- | --- |
| `jar` | Is this jar packaged correctly? What did it shade that the server already provides, and which Java release do its class files need? |
| `compat` | Will it run against a given Minecraft version's API? Which classes and members does it call that are not there? |
| `stack` | What breaks when this set of plugins is installed together — missing dependencies, duplicate plugins, command clashes, the same library in two different builds? |
| `pack` | Will the resource pack it ships actually apply, or will it load and silently render missing textures? |

There is also an `mcp` mode that serves the same analyses to an AI coding agent over the Model
Context Protocol, so "audit this jar" becomes something the agent can do itself instead of
guessing.

## Why these four

The failures above share a property: the server does not tell you about them.

- A plugin that shades an unrelocated copy of a library the server already provides works on the
  developer's machine and breaks on somebody else's, depending on load order and on what other
  plugins ship.
- A class file compiled for a newer Java release fails with `UnsupportedClassVersionError` at load,
  before any of the plugin's own error handling can run.
- A missing hard dependency produces one line in a startup log that is thousands of lines long.
- A broken resource pack produces no server-side signal at all. Paper's
  `PlayerResourcePackStatusEvent.Status` has seven values and none of them means "loaded, but half
  the models fell back to missing-texture" — the client reports success and renders purple and
  black.

Everything reported carries a rule id, a severity that means one specific thing, and the source the
underlying fact came from.

## Requirements

Java 21 or newer. Nothing else — the release jar is self-contained.

## Install

Download `pluginscope-<version>.jar` from the
[releases](https://github.com/SalyyS1/pluginscope/releases) page and run it:

```
java -jar pluginscope.jar --help
```

## Quick start

```
# One jar, checked as if it were going onto a 26.2 server
java -jar pluginscope.jar jar MyPlugin.jar --target 26.2

# Will it run on 1.21.4? Downloads that version's paper-api once and caches it
java -jar pluginscope.jar compat MyPlugin.jar --mc 1.21.4

# A whole server's plugins folder, as one install set
java -jar pluginscope.jar stack /srv/mc/plugins

# The resource pack the plugin ships
java -jar pluginscope.jar pack pack.zip --mc 26.2

# Every rule, with its rationale and its source
java -jar pluginscope.jar rules
```

## What the output looks like

Both samples below are real output, captured from a deliberately broken pair of demo plugins built
for this purpose — a toy jar that shades gson and the Kotlin runtime under their original names,
misspells `depend` as `depends`, ships its own source file, and declares no `api-version`.

```
$ java -jar pluginscope.jar jar plugins/DemoPlugin.jar --target 1.21.4

jar: D:\tmp\demo-server\plugins\DemoPlugin.jar
  plugin       DemoPlugin
  version      1.0.0
  descriptor   plugin.yml
  api-version  (none)
  size         0.01 MiB
  classes      13 (1 own)
  needs java   21
  own package  com.example.demo
  kotlin       no

[warning] J006  Descriptor contains a key the format does not define
    plugin.yml
      Ignored by the server: depends.
        'depends' looks like 'depend'
    fix: Remove the key or correct the spelling.

[warning] J007  No api-version, so the plugin loads in legacy mode
    plugin.yml
      No api-version, so the server treats this plugin as pre-1.13 legacy.
    fix: Declare the api-version your code was compiled against.

[warning] J021  Bundles a library the server already provides
    com.google.gson
      5 classes, 1 KiB. The server provides com.google.code.gson:gson 2.14.0.
        server side: paper-api 26.2.build.116-stable POM, compile scope
    fix: Depend on it as compileOnly, or relocate it if you genuinely need a different version.

[warning] J022  Third-party library shaded without relocation
    org.apache.commons
      4 classes, 0 KiB, outside the plugin's own package (com.example.demo).
        org.apache.commons.lang3.StringUtils1
        org.apache.commons.lang3.StringUtils2
        org.apache.commons.lang3.StringUtils3
    fix: Relocate shaded dependencies under your own package, or declare them in `libraries:`.

[warning] J023  Kotlin standard library shaded without relocation
    kotlin.jvm.internal
      3 classes across 1 package(s), 0 KiB, shipped under their original names.
        kotlin.jvm.internal: 3 classes, 0 KiB
    fix: Relocate kotlin.** under your own package in the shadow configuration.

[warning] J030  Jar contains entries that do not belong in a release build
    META-INF/maven
      1 build-metadata file(s) from shaded dependencies.
        com.google.code.gson/gson
    source files
      1 source file(s) shipped in the release jar.
        DemoPlugin.java
    fix: Exclude these from the packaging task.

Summary: 7 warnings
```

The same three jars looked at as one install set. Nothing here is visible from any single jar: the
gson skew and the alias clash are properties of the combination.

```
$ java -jar pluginscope.jar stack plugins

plugins directory: D:\tmp\demo-server\plugins
  jars           3
  plugins        3
  total size     0.0 MiB
  target         Paper
    DemoBank     0.9.1  api 1.21  java 21
    DemoEconomy  2.3.0  api 1.21  java 21
    DemoPlugin   1.0.0  api none  java 21

[blocker] S001  Hard dependency is not installed
    DemoEconomy -> Vault
      DemoEconomy requires Vault, which is not in this directory.
    fix: Install the dependency, or make it a soft dependency if the plugin can run without it.

[warning] S030  Several plugins ship the same unrelocated library, in different builds
    com.google.gson
      Shipped unrelocated by 2 plugins in 2 different builds.
        DemoEconomy: 5 classes, 1 KiB
        DemoPlugin: 5 classes, 1 KiB
        the server itself provides com.google.code.gson:gson
    fix: Relocate the library in your own plugins. For third-party jars, report it upstream.

[warning] S041  Plugin loads in legacy mode
    DemoPlugin
      No api-version in plugin.yml, so the server applies legacy conversion to it.
    fix: Update the plugin, or ask its author to declare an api-version.

[info] S021  A command alias is claimed by more than one plugin
    /bal
      An alias of DemoEconomy, and the command name of DemoBank.
    fix: Drop the overlapping alias from one of the plugins.

[info] S040  Java release required by this plugin set
    DemoBank-0.9.1.jar
      Highest class file version is 65, so this set needs Java 21 or newer.
    fix: Run at least this Java release, or replace the plugin that raises the floor.

Summary: 1 blocker, 2 warnings, 2 infos
```

Findings are grouped by rule, so a jar with forty hits of one rule reads as one problem. `--verbose`
lists every hit and prints the provenance line for each rule.

## Severity means something specific

| Severity | Meaning |
| --- | --- |
| `blocker` | The artifact will not load at all, or is rejected before enable. |
| `critical` | It loads, then fails at runtime as soon as the affected code path is taken. |
| `warning` | It works today, but depends on luck: load order, another plugin's choices, a version bump. |
| `info` | Worth a human's attention. No defect is being claimed. |

`--fail-on` picks the lowest severity that makes the process exit non-zero. The default is
`critical`, so a CI job fails on things that will actually break and stays quiet about the rest.
`--fail-on never` always exits zero.

All 65 rules are listed in [docs/rules.md](docs/rules.md) with their rationale and their source.
`pluginscope rules` prints the same catalogue from whichever version you are running.

## Output for machines

`--format json` prints the full report with stable field names. `--format sarif` writes SARIF
2.1.0, which GitHub code scanning ingests, so findings land on the pull request that introduced
them.

```yaml
- name: Audit the plugin jar
  run: |
    java -jar pluginscope.jar jar build/libs/*.jar \
      --target 26.2 --format sarif -o pluginscope.sarif --fail-on never

- uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: pluginscope.sarif
```

Rules are declared in `tool.driver.rules` because code scanning silently drops results whose rule
is not declared, and fingerprints are location-based rather than line-based, so reformatting a file
does not invent new alerts.

## Using it from an AI agent

`pluginscope mcp` speaks the Model Context Protocol on stdin and stdout. Register it once and an
agent can audit jars on its own:

```json
{
  "mcpServers": {
    "pluginscope": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/pluginscope.jar", "mcp"]
    }
  }
}
```

The tools exposed are `scan_jar`, `check_api_compat`, `audit_plugins_dir`, `validate_pack` and
`list_rules`. All of them are marked read-only, they return the same JSON the CLI produces, and a
failure that the agent can act on (a path that does not exist, an argument that is missing) comes
back as tool content rather than as a protocol error.

## The commands in detail

### `jar` — one plugin, on its own

```
pluginscope jar <jar or directory>... [--target 26.2 | --target-java 21] [--no-reachability] [--disable J021]
```

It reads the descriptor, the class files and the zip entries, and reports:

- descriptor problems that stop a load: missing required fields, a main class that is not in the
  jar, a plugin name outside the accepted character set, a main class inside `org.bukkit`
- descriptor problems that silently do nothing: a key the format does not define, which is how
  `depends:` instead of `depend:` reaches production with the dependency unenforced
- `api-version` missing, which puts the plugin into legacy handling, or malformed
- classes compiled for a newer Java release than the target server runs, and bundled code compiled
  for a newer release than the plugin's own classes
- server namespaces shipped inside the jar (`org/bukkit`, `io/papermc`, `net/minecraft`, …)
- libraries the server already provides, shipped again
- third-party libraries and the Kotlin runtime shaded without relocation
- packaging leftovers: sources, `META-INF/maven`, editor droppings, nested jars, signature files
  that no longer match after shading, a `Class-Path` the plugin classloader ignores
- how much of each bundled library is never referenced, as a hint for minimisation

Give `--target` and the Java check becomes exact; leave it out and the report still tells you which
release the class files need.

### `compat` — one plugin against one server API

```
pluginscope compat <jar> (--mc 1.21.4 | --api paper-api.jar...) [--include-prefix net.kyori.adventure] [--no-deprecated]
```

Every Bukkit and Paper reference in the jar is resolved against the API you name, walking the class
hierarchy so inherited members resolve the way the JVM would. `--mc` downloads that version's
`paper-api` from Paper's repository once, verifies it against the repository's own checksum, and
caches it under `~/.pluginscope/api-cache`; both the historical `1.21.4-R0.1-SNAPSHOT` and the
current `26.2.build.N-stable` version shapes are understood.

Two deliberate choices about false positives:

- A member is only reported missing when the whole ancestry of its owner is present in the API you
  supplied. If part of the chain is outside it, the answer is silence rather than a guess, because
  nobody ever passes the complete transitive classpath.
- References into `net.minecraft` and `craftbukkit` are reported separately as internals, not as
  API breakage. They are not part of the API and were never promised to survive a version bump.

One caveat the tool cannot see: Paper rewrites some legacy calls while loading a plugin that
declares an older `api-version`, so a small number of "member does not exist" findings are repaired
for you at load time. Treat `C002` as "prove this one" rather than as a verdict when you are
checking an old plugin against a much newer server.

### `stack` — a whole install set

```
pluginscope stack <plugins directory> [--folia]
```

Reports the properties that belong to the set rather than to any one jar: a hard dependency that is
not installed, a dependency cycle, two jars claiming one plugin name or one main class, commands and
aliases claimed twice, the same unrelocated library shipped by several plugins in different builds,
the Java release the whole set needs, and which plugins load in legacy mode. `--folia` additionally
reports plugins that do not declare Folia support, which on Folia means they are simply absent.

For per-jar thread-safety analysis under Folia — where the plugin declares support but the code is
not actually safe — use [foliascan](https://github.com/SalyyS1/foliascan), which is the other half
of this pair.

### `pack` — the resource pack a plugin ships

```
pluginscope pack <directory or zip> [--mc 26.2 | --pack-format 88] [--against other-pack.zip]... [--no-fetch]
```

Validates the pack against a target version's format, resolves references inside the pack, and looks
for the collisions that make two plugins' packs quietly overwrite each other. `--pack-format` makes
the whole run offline; `--mc` looks the number up and caches it.

## What it does not do

- **No thread-safety or dataflow analysis.** Whether a plugin is safe under Folia's regionised
  threading is a different question, answered by `foliascan`.
- **No malware scanning.** Paper's own Hangar scanner covers that ground on uploads.
- **No schema engine.** Pack validation checks structure, references and collisions; it does not
  reimplement the full mcdoc schemas, and it does not try to out-validate Spyglass in an editor.
- **Reachability is static.** Reflection, `Class.forName` and resource-driven wiring are invisible,
  so a low reachability number is a hint to look, never an instruction to delete.
- **The provided-library table covers what `paper-api` exposes at compile scope.** The server's own
  runtime dependencies are left out on purpose: whether a plugin classloader can reach them has not
  been verified here, and guessing would produce confident bad advice.
- **Pack format numbers are fetched, not bundled.** The published source carries no licence, so
  nothing is vendored; supply `--pack-format` to work entirely offline.

## Where the facts come from

Every rule states a fact — what the server rejects, what a class file version means, what a pack
field may contain — and every fact was read from a primary source: the published `paper-api` POM,
the constant pool of `PluginDescriptionFile`, `apiVersioning.json` inside the API jar, the JVM
specification, the pack schema descriptions in vanilla-mcdoc, the MCP specification repository.

The source travels with the rule. `pluginscope rules` prints it, and `--verbose` prints it beside
each finding, so a claim can be checked instead of believed. `NOTICE` records the whole list.

## Building from source

```
./gradlew build
```

Produces `build/libs/pluginscope-<version>.jar` (the runnable, self-contained one) and a `-thin`
jar. Tests run as part of `build` and use real compiled fixture jars, including a clean plugin that
must produce no findings at all — a tool that complains about correct builds gets switched off and
never run again.

## Prior art

pluginscope contains no code from other projects, but it is not the first tool in this area and it
would be dishonest to imply otherwise. The closest neighbours, all worth knowing about:
[mcdrift](https://github.com/kitsune-de/mcdrift) for Minecraft version drift with SARIF output,
[jarhc](https://github.com/smarkwal/jarhc) for generic jar health,
[japicmp](https://github.com/siom79/japicmp) and [revapi](https://github.com/revapi/revapi) for API
diffing, [animal-sniffer](https://github.com/mojohaus/animal-sniffer) for checking a jar against a
recorded API surface, [SpyglassMC](https://github.com/SpyglassMC/Spyglass) and
[vanilla-mcdoc](https://github.com/SpyglassMC/vanilla-mcdoc) for pack schemas, and
[misode/mcmeta](https://github.com/misode/mcmeta) for version data. `NOTICE` has the full list with
licences and what each one contributed to the design.

## Licence

Apache-2.0. See `LICENSE`.

pluginscope is an independent project by [SalyVn](https://github.com/SalyyS1). It is not affiliated
with, endorsed by or sponsored by PaperMC, Mojang or Microsoft, and it bundles no Minecraft code or
game data.
