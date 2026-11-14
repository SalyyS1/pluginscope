plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "com.salyvn.pluginscope"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    // Class files are only ever READ. No writing, no agents, no plugin code is executed.
    implementation("org.ow2.asm:asm:9.10.1")
    // plugin.yml / paper-plugin.yml come out of untrusted jars, so SafeConstructor only.
    implementation("org.yaml:snakeyaml:2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.salyvn.pluginscope.MainKt"
    applicationName = "pluginscope"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// One source of truth for the version: the build stamps it into a resource the CLI reads back,
// so it cannot drift between build.gradle.kts and the Kotlin source.
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("pluginscope-version.properties") {
        expand("version" to project.version.toString())
    }
}

// The thin jar gets a classifier so it cannot overwrite the shaded CLI artifact that users
// download and that the release checksums cover.
tasks.jar {
    archiveClassifier = "thin"
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveBaseName = "pluginscope"
    manifest {
        attributes["Main-Class"] = "com.salyvn.pluginscope.MainKt"
        attributes["Implementation-Version"] = project.version.toString()
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
