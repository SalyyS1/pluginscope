package com.salyvn.pluginscope.core

import java.util.Properties

/** Version stamped in by the build, so the CLI and the jar can never disagree about it. */
object BuildInfo {
    val version: String by lazy {
        val stream = BuildInfo::class.java.getResourceAsStream("/pluginscope-version.properties")
            ?: return@lazy "unknown"
        stream.use { Properties().apply { load(it) }.getProperty("version") ?: "unknown" }
    }

    const val NAME = "pluginscope"
    const val HOMEPAGE = "https://github.com/SalyyS1/pluginscope"
}
