package com.salyvn.pluginscope.data

/**
 * Class file major versions and the Java releases they belong to.
 *
 * Fixed by the JVM specification: major version 45 is Java 1.1 and each release since adds one,
 * so 52 is Java 8 and 65 is Java 21 (JVMS, "The ClassFile Structure", table of major versions).
 * A class whose major version exceeds what the running JVM supports fails at load with
 * UnsupportedClassVersionError, before any of its code runs.
 */
object JavaRuntimes {

    private const val JAVA_8_MAJOR = 52
    private const val JAVA_8 = 8

    /** Java release for a class file major version, e.g. 65 -> 21. */
    fun releaseFor(majorVersion: Int): Int = majorVersion - JAVA_8_MAJOR + JAVA_8

    /** Class file major version a Java release produces, e.g. 21 -> 65. */
    fun majorVersionFor(release: Int): Int = release - JAVA_8 + JAVA_8_MAJOR

    fun describe(majorVersion: Int): String = "class file $majorVersion (Java ${releaseFor(majorVersion)})"
}
