package com.salyvn.pluginscope.compat

import com.salyvn.pluginscope.core.BuildInfo
import com.salyvn.pluginscope.core.FindingSink
import com.salyvn.pluginscope.core.JarInspection
import com.salyvn.pluginscope.core.JarReader
import com.salyvn.pluginscope.core.MemberUse
import com.salyvn.pluginscope.core.ScanReport
import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.core.UseKind
import java.nio.file.Path

data class CompatOptions(
    /** Namespaces whose references are checked. Everything else in the jar is ignored. */
    val includePrefixes: Set<String> = DEFAULT_PREFIXES,
    /** Report deprecated members as well as missing ones. */
    val reportDeprecated: Boolean = true,
    val maxSitesPerMember: Int = 3,
) {
    companion object {
        /** The plugin-facing namespaces of Paper, Spigot and their historical Paper-fork packages. */
        val DEFAULT_PREFIXES = setOf(
            "org/bukkit/",
            "io/papermc/paper/",
            "com/destroystokyo/paper/",
            "org/spigotmc/",
        )

        /** Not API. Never resolved against the surface, always reported as internals. */
        val INTERNAL_PREFIXES = setOf(
            "net/minecraft/",
            "org/bukkit/craftbukkit/",
        )
    }
}

/**
 * Answers one question: if this jar is dropped onto a server whose API is the given surface, what
 * breaks?
 *
 * The jar's own classes — including anything shaded into it — are treated as present, so only real
 * external references are examined.
 */
class CompatChecker(private val options: CompatOptions = CompatOptions()) {

    fun check(jarPath: Path, surface: ApiSurface): ScanReport {
        val inspection = JarReader.read(jarPath) { owner -> isCandidate(owner) }
        return check(inspection, surface)
    }

    fun check(inspection: JarInspection, surface: ApiSurface): ScanReport {
        val sink = FindingSink()
        val external = inspection.memberUses.filterNot { inspection.contains(it.owner) }

        val internals = external.filter { use -> CompatOptions.INTERNAL_PREFIXES.any { use.owner.startsWith(it) } }
        val api = external - internals.toSet()

        reportInternals(internals, sink)
        reportApi(api, surface, sink)

        if (api.isEmpty() && internals.isEmpty()) {
            sink.add(
                CompatRules.NO_API_REFERENCES,
                location = inspection.fileName,
                detail = "The jar makes no reference to ${options.includePrefixes.joinToString(", ") { it.trimEnd('/').replace('/', '.') }}.",
            )
        }

        return ScanReport(
            tool = "${BuildInfo.NAME} compat",
            toolVersion = BuildInfo.version,
            subject = inspection.path,
            subjectKind = "jar",
            findings = sink.findings(),
            facts = buildMap {
                put("plugin", inspection.descriptor?.name ?: "(no descriptor)")
                put("version", inspection.descriptor?.version ?: "-")
                put("declared api-version", inspection.descriptor?.apiVersion ?: "(none)")
                put("checked against", "${surface.label} (${surface.size} classes)")
                put("api references", "${api.map { it.owner + it.memberKey }.distinct().size} distinct")
                if (internals.isNotEmpty()) {
                    put("internal references", "${internals.map { it.owner + it.memberKey }.distinct().size} distinct")
                }
            },
        )
    }

    private fun reportApi(uses: List<MemberUse>, surface: ApiSurface, sink: FindingSink) {
        // Group by the member being referenced: one finding per broken member, with call sites as
        // evidence, rather than one finding per bytecode instruction.
        val grouped = uses.groupBy { Triple(it.owner, it.memberKey, it.kind) }
        // A missing class shows up once as a type reference and again for every member touched on
        // it, so those are collected and reported per class instead.
        val absentClasses = LinkedHashMap<String, MutableList<MemberUse>>()

        for ((key, sites) in grouped) {
            val (owner, memberKey, kind) = key
            val sample = sites.first()
            when (surface.resolve(owner, memberKey, kind)) {
                Resolution.MISSING_CLASS -> absentClasses.getOrPut(owner) { mutableListOf() } += sites

                Resolution.MISSING_MEMBER -> sink.add(
                    CompatRules.MEMBER_ABSENT,
                    location = sample.render(),
                    detail = "Owner exists in ${surface.label} but this ${kind.name.lowercase()} does not. " +
                        "Referenced from ${sites.size} site(s).",
                    evidence = callSites(sites),
                )

                Resolution.FOUND -> if (options.reportDeprecated && surface.isDeprecated(owner, memberKey)) {
                    sink.add(
                        CompatRules.MEMBER_DEPRECATED,
                        location = sample.render(),
                        detail = "Deprecated in ${surface.label}. Referenced from ${sites.size} site(s).",
                        evidence = callSites(sites),
                    )
                }

                Resolution.UNKNOWN -> Unit // Ancestry incomplete: silence beats a guess.
            }
        }

        for ((owner, sites) in absentClasses) {
            sink.add(
                CompatRules.CLASS_ABSENT,
                location = owner.replace('/', '.'),
                detail = "Not present in ${surface.label}. Referenced from ${sites.size} site(s).",
                evidence = callSites(sites),
            )
        }
    }

    private fun reportInternals(uses: List<MemberUse>, sink: FindingSink) {
        if (uses.isEmpty()) return
        val byOwner = uses.groupBy { it.owner }
        for ((owner, sites) in byOwner) {
            sink.add(
                CompatRules.SERVER_INTERNALS,
                location = owner.replace('/', '.'),
                detail = "${sites.size} reference(s) to server internals from ${sites.map { it.fromClass }.distinct().size} class(es).",
                evidence = callSites(sites),
                severityOverride = if (byOwner.size > 20) Severity.WARNING else null,
            )
        }
    }

    private fun callSites(sites: List<MemberUse>): List<String> = sites
        .distinctBy { it.fromClass to it.line }
        .take(options.maxSitesPerMember)
        .map { site ->
            val where = site.fromClass.replace('/', '.') + "#" + site.fromMethod.substringBefore('(')
            if (site.line != null) "$where (line ${site.line})" else where
        }

    private fun isCandidate(owner: String): Boolean =
        options.includePrefixes.any { owner.startsWith(it) } ||
            CompatOptions.INTERNAL_PREFIXES.any { owner.startsWith(it) }
}
