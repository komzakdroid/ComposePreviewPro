/*
 * Root build script.
 *
 * Strategy: declare all plugin IDs with `apply false` so they are loaded into
 * the classpath but not applied to the root project. Each subproject opts
 * into the plugins it actually needs.
 */
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.intellij.platform) apply false
    // Android test-fixture module (:sample-android). AGP 9 has built-in
    // Kotlin, so no kotlin.android plugin is applied.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

// Shared JVM toolchain and Kotlin target across all subprojects.
// The plugin no longer depends on the IDE's compile-on-save — hot reload
// invokes Gradle directly — so the project stays on Kotlin 2.3 across
// the board.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(libs.versions.java.get().toInt())
        }
    }
}
