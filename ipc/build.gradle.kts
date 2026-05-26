/*
 * :ipc — Shared message protocol between :plugin (client) and :renderer (server).
 *
 * Pure Kotlin/JVM library. No Compose, no IntelliJ Platform — keep this tiny
 * so both processes can depend on it without dragging heavy classpaths.
 *
 * Wire format: newline-delimited JSON (NDJSON) over stdio.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
