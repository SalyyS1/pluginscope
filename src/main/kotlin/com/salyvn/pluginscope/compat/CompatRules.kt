package com.salyvn.pluginscope.compat

import com.salyvn.pluginscope.core.Rule
import com.salyvn.pluginscope.core.Severity

/** Checks the cross-version compatibility command can report. */
object CompatRules {

    val CLASS_ABSENT = Rule(
        id = "C001",
        severity = Severity.CRITICAL,
        title = "Referenced API class does not exist in the target API",
        rationale = "The class is not in the API being checked against, so touching this code path " +
            "throws NoClassDefFoundError on that server version.",
        provenance = "Class entries of the API jar(s) passed to --api",
        fix = "Guard the call behind a version check, or drop support for the older target.",
    )

    val MEMBER_ABSENT = Rule(
        id = "C002",
        severity = Severity.CRITICAL,
        title = "Referenced API member does not exist in the target API",
        rationale = "The owner class is present and its full ancestry is known, and the method or " +
            "field is nowhere in it, which is NoSuchMethodError or NoSuchFieldError at runtime. " +
            "One exception worth knowing: Paper rewrites some legacy calls while loading a plugin " +
            "that declares an older api-version, so a small number of these are repaired for you.",
        provenance = "Member tables of the API jar(s), resolved through the class hierarchy; " +
            "rewriting caveat from Paper's load-time class processing (Commodore)",
        fix = "Use the replacement member, or reflectively feel for it when supporting both versions.",
    )

    val MEMBER_DEPRECATED = Rule(
        id = "C010",
        severity = Severity.INFO,
        title = "Referenced API member is deprecated in the target API",
        rationale = "Still works today. Deprecation in the server API is the usual precursor to " +
            "removal one or two releases later.",
        provenance = "@Deprecated annotations and ACC_DEPRECATED flags in the API jar(s)",
        fix = "Move to the documented replacement while it is a choice rather than a break.",
    )

    val SERVER_INTERNALS = Rule(
        id = "C020",
        severity = Severity.WARNING,
        title = "Uses server internals rather than the plugin API",
        rationale = "net.minecraft and craftbukkit classes are implementation detail. They are " +
            "remapped, renamed or restructured between Minecraft versions with no notice.",
        provenance = "Reference owner package, matched against the server's internal namespaces",
        fix = "Prefer the API. Where there is no API, isolate the internals behind your own " +
            "version-specific layer so a break is one class, not the whole plugin.",
    )

    val NO_API_REFERENCES = Rule(
        id = "C030",
        severity = Severity.INFO,
        title = "No references to the checked namespaces",
        rationale = "Nothing in the jar touches the API being checked. Either the wrong namespaces " +
            "were selected, or the jar is a library rather than a plugin.",
        provenance = "Reference scan of the jar",
        fix = "Widen the check with --include-prefix if this is unexpected.",
    )

    val all = listOf(CLASS_ABSENT, MEMBER_ABSENT, MEMBER_DEPRECATED, SERVER_INTERNALS, NO_API_REFERENCES)
}
