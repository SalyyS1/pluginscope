package com.salyvn.pluginscope.core

import kotlinx.serialization.Serializable

/**
 * How much a finding matters. The tiers are deliberately narrow so that a severity means one
 * specific thing to whoever reads the report:
 *
 * - [BLOCKER]  the artifact will not load at all, or will be rejected before enable.
 * - [CRITICAL] it loads, then fails at runtime as soon as the affected code path is taken.
 * - [WARNING]  it works today but depends on luck: load order, another plugin's choices,
 *              a server version bump.
 * - [INFO]     worth a human's attention, no defect claimed.
 */
@Serializable
enum class Severity(val label: String, val rank: Int) {
    BLOCKER("blocker", 4),
    CRITICAL("critical", 3),
    WARNING("warning", 2),
    INFO("info", 1);

    companion object {
        fun parse(raw: String): Severity? =
            entries.firstOrNull { it.label.equals(raw, ignoreCase = true) }
    }
}

/**
 * A rule states one checkable fact. [provenance] records where that fact was established from -
 * a POM, a source file, an official doc page: so every claim the tool prints can be audited
 * instead of taken on trust.
 */
data class Rule(
    val id: String,
    val severity: Severity,
    val title: String,
    val rationale: String,
    val provenance: String,
    val fix: String? = null,
)

/** One concrete hit of a [Rule] at one place. */
@Serializable
data class Finding(
    val ruleId: String,
    val severity: Severity,
    val title: String,
    /** Where the problem is: a jar entry, a file path, or `class#member`. */
    val location: String,
    /** What was actually observed, in the reader's terms. */
    val detail: String,
    /** Raw observations backing [detail]: class names, versions, counts. */
    val evidence: List<String> = emptyList(),
    val fix: String? = null,
    val provenance: String? = null,
)

/** Everything one command produced, ready to hand to a reporter. */
@Serializable
data class ScanReport(
    val tool: String,
    val toolVersion: String,
    /** What was inspected: a jar path, a directory, a pack root. */
    val subject: String,
    val subjectKind: String,
    val findings: List<Finding>,
    /** Facts worth printing even when nothing is wrong (plugin name, api-version, jar size…). */
    val facts: Map<String, String> = emptyMap(),
) {
    fun count(severity: Severity): Int = findings.count { it.severity == severity }

    val worst: Severity?
        get() = findings.maxByOrNull { it.severity.rank }?.severity
}

/** Collects findings for one subject, keeping rule metadata attached to each hit. */
class FindingSink {
    private val collected = mutableListOf<Finding>()

    fun add(
        rule: Rule,
        location: String,
        detail: String,
        evidence: List<String> = emptyList(),
        severityOverride: Severity? = null,
    ) {
        collected += Finding(
            ruleId = rule.id,
            severity = severityOverride ?: rule.severity,
            title = rule.title,
            location = location,
            detail = detail,
            evidence = evidence,
            fix = rule.fix,
            provenance = rule.provenance,
        )
    }

    /** Sorted worst-first, then by rule id, so console and JSON output are stable. */
    fun findings(): List<Finding> =
        collected.sortedWith(compareByDescending<Finding> { it.severity.rank }.thenBy { it.ruleId }.thenBy { it.location })

    fun isEmpty(): Boolean = collected.isEmpty()
}
