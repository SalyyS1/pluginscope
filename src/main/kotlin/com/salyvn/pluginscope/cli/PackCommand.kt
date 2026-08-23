package com.salyvn.pluginscope.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.pluginscope.pack.PackAuditOptions
import com.salyvn.pluginscope.pack.PackFormatCatalog
import com.salyvn.pluginscope.pack.PackValidator

/**
 * `pluginscope pack` - validate a resource pack the way a client would read it.
 *
 * The reference checks are the reason this exists: a client that applied the pack and then drew the
 * missing-texture placeholder still reports SUCCESSFULLY_LOADED to the server, so nothing on the
 * server side can tell you the pack is broken.
 */
class PackCommand : ReportCommand("pack") {

    override fun help(context: Context) =
        "Validate a resource pack: pack.mcmeta formats, reference integrity, item definitions, hygiene."

    private val pack by argument(name = "PACK", help = "Resource pack directory or .zip")
        .path(mustExist = true, mustBeReadable = true)

    private val mc by option(
        "--mc",
        help = "Minecraft version the pack targets, e.g. 1.21.4 or 26.2. Needs the format table.",
    )

    private val packFormat by option(
        "--pack-format",
        help = "Target pack format number, if you would rather state it directly. Needs no network.",
    ).int()

    private val against by option(
        "--against",
        help = "Another pack served alongside this one, checked for item definition collisions (repeatable)",
    ).path(mustExist = true, mustBeReadable = true).multiple()

    private val noFetch by option(
        "--no-fetch",
        help = "Never touch the network; use the cached format table if there is one",
    ).flag()

    private val cache by option("--cache", help = "Directory for the cached pack format table").path()

    override fun run() {
        if (packFormat != null && mc != null) {
            echo("Both --pack-format and --mc were given; --pack-format is used.", err = true)
        }

        val validator = PackValidator(
            PackAuditOptions(
                mcVersion = mc,
                packFormat = packFormat,
                against = against,
                allowNetwork = !noFetch,
                cacheDir = cache ?: PackFormatCatalog.defaultCacheDir(),
            ),
            log = { echo(it, err = true) },
        )
        emit(listOf(validator.validate(pack)))
    }
}
