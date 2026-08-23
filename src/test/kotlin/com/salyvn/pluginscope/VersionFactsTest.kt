package com.salyvn.pluginscope

import com.salyvn.pluginscope.data.JavaRuntimes
import com.salyvn.pluginscope.data.McVersions
import com.salyvn.pluginscope.data.ServerRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionFactsTest {

    @Test
    fun `both Minecraft numbering schemes are recognised`() {
        assertEquals(McVersions.ApiVersionShape.LEGACY_NUMBERING, McVersions.shapeOf("1.21"))
        assertEquals(McVersions.ApiVersionShape.LEGACY_NUMBERING, McVersions.shapeOf("1.21.4"))
        assertEquals(McVersions.ApiVersionShape.CURRENT_NUMBERING, McVersions.shapeOf("26.2"))
        assertEquals(McVersions.ApiVersionShape.EXPLICIT_NONE, McVersions.shapeOf("none"))
        assertEquals(McVersions.ApiVersionShape.MALFORMED, McVersions.shapeOf("yesterday"))
        assertEquals(McVersions.ApiVersionShape.MALFORMED, McVersions.shapeOf("1.21-pre1"))
    }

    @Test
    fun `the Java release per Minecraft version follows Mojang's manifest`() {
        assertEquals(8, McVersions.minimumJavaFor("1.16.5"))
        assertEquals(16, McVersions.minimumJavaFor("1.17.1"))
        assertEquals(17, McVersions.minimumJavaFor("1.20.4"))
        assertEquals(21, McVersions.minimumJavaFor("1.20.5"))
        assertEquals(21, McVersions.minimumJavaFor("1.21.4"))
        assertEquals(25, McVersions.minimumJavaFor("26.2"))
        assertNull(McVersions.minimumJavaFor("none"))
        assertNull(McVersions.minimumJavaFor("nonsense"))
    }

    @Test
    fun `class file majors map to Java releases both ways`() {
        assertEquals(8, JavaRuntimes.releaseFor(52))
        assertEquals(17, JavaRuntimes.releaseFor(61))
        assertEquals(21, JavaRuntimes.releaseFor(65))
        assertEquals(25, JavaRuntimes.releaseFor(69))
        assertEquals(65, JavaRuntimes.majorVersionFor(21))
        assertEquals(52, JavaRuntimes.majorVersionFor(8))
    }

    @Test
    fun `provided libraries are matched by package root, not by prefix accident`() {
        assertEquals("com.google.gson", ServerRuntime.providedFor("com/google/gson/Gson")?.dotted)
        assertEquals("com.google.gson", ServerRuntime.providedFor("com/google/gson")?.dotted)
        // A package that merely starts with the same characters must not match.
        assertNull(ServerRuntime.providedFor("com/google/gsonx/Thing"))
        assertNull(ServerRuntime.providedFor("com/example/plugin/libs/gson/Gson"))
    }

    @Test
    fun `server namespaces are recognised and explained`() {
        assertTrue(ServerRuntime.isServerNamespace("org/bukkit/entity/Player"))
        assertTrue(ServerRuntime.isServerNamespace("net/minecraft/server/MinecraftServer"))
        assertFalse(ServerRuntime.isServerNamespace("org/bukkitx/Thing"))
        assertTrue(ServerRuntime.serverNamespaceReason("io/papermc/paper/Anything")!!.isNotBlank())
    }

    @Test
    fun `the Kotlin runtime is treated as not provided by the server`() {
        assertTrue(ServerRuntime.isKotlinRuntime("kotlin/jvm/internal/Intrinsics"))
        assertTrue(ServerRuntime.isKotlinRuntime("kotlinx/coroutines/Job"))
        assertNull(ServerRuntime.providedFor("kotlin/Unit"))
    }
}
