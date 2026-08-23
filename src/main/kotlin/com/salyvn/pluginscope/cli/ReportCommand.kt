package com.salyvn.pluginscope.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.pluginscope.core.ScanReport
import com.salyvn.pluginscope.core.Severity
import com.salyvn.pluginscope.report.ConsoleReporter
import com.salyvn.pluginscope.report.JsonReporter
import com.salyvn.pluginscope.report.SarifReporter
import java.nio.file.Files

/**
 * Shared plumbing for every subcommand: how a report is rendered, where it goes, and what the
 * process exit code becomes. Kept in one place so `jar`, `compat`, `stack` and `pack` behave
 * identically in a CI script.
 */
abstract class ReportCommand(name: String) : CliktCommand(name = name) {

    protected val format by option("--format", help = "Output format")
        .choice("console", "json", "sarif").default("console")

    protected val output by option("-o", "--output", help = "Write the report to this file").path()

    protected val failOn by option(
        "--fail-on",
        help = "Lowest severity that makes the run exit non-zero (default: critical)",
    ).choice("blocker", "critical", "warning", "info", "never").default("critical")

    protected val noColor by option("--no-color", help = "Disable ANSI colour").flag()

    protected val verbose by option("-v", "--verbose", help = "Show every hit and its source").flag()

    protected fun emit(reports: List<ScanReport>) {
        val text = when (format) {
            "json" -> JsonReporter.render(reports)
            "sarif" -> SarifReporter.render(reports)
            else -> {
                val reporter = ConsoleReporter(color = !noColor && output == null, verbose = verbose)
                reports.joinToString("\n" + "-".repeat(72) + "\n") { reporter.render(it) }
            }
        }

        output?.let { target ->
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, text)
            echo("Wrote $format report to $target")
        } ?: echo(text)

        val threshold = Severity.parse(failOn) ?: return
        val worst = reports.mapNotNull { it.worst }.maxByOrNull { it.rank }
        if (worst != null && worst.rank >= threshold.rank) throw ProgramResult(1)
    }
}
