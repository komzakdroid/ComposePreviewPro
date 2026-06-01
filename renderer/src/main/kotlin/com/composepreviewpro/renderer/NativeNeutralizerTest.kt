package com.composepreviewpro.renderer

import java.net.URLClassLoader

/**
 * Validates the systematic off-device Android native fix WITHOUT needing a full
 * Android-compiled composable: installs [NativeMethodNeutralizer], then loads
 * and force-initialises the framework classes whose `<clinit>` (or first call)
 * historically threw `UnsatisfiedLinkError` on a desktop JVM.
 *
 *   ./gradlew :renderer:nativeTest
 */
fun main() {
    val inst = AgentLoader.ensureLoaded()
    if (inst == null) {
        System.err.println("[native-test] FATAL: no Instrumentation (self-attach failed) — cannot install neutraliser")
        kotlin.system.exitProcess(1)
    }
    NativeMethodNeutralizer.installInto(inst)

    val androidAll = AndroidRuntimeProvisioner.locate()
        ?: error("android-all runtime not located; the Gradle task should set -Dcomposepreviewpro.androidRuntimeJar")
    println("[native-test] android-all: ${androidAll.name}")
    println("[native-test] SystemProperties shim: ${runCatching { Class.forName("android.os.SystemProperties").protectionDomain.codeSource?.location }.getOrNull()}")
    println()

    // Child loader mirrors production: android-all on the child, the renderer
    // (which carries the android.os.SystemProperties shim) as parent → the shim
    // wins by parent-first delegation; android-all natives get neutralised.
    val cl = URLClassLoader(arrayOf(androidAll.toURI().toURL()), object {}.javaClass.classLoader)

    // The exact classes the WindowInsets path detonated on, plus a spread of
    // other native-heavy framework classes a real render can reach.
    val targets = listOf(
        "android.os.Build",
        "android.os.Build\$VERSION",
        "dalvik.system.VMRuntime",
        "android.os.Process",
        "android.os.Trace",
        "android.os.SystemClock",
        "android.view.animation.PathInterpolator",
        "android.util.TypedValue",
        "android.text.TextUtils",
        "android.graphics.Color",
    )

    var passed = 0
    var failed = 0
    for (fqn in targets) {
        try {
            // initialize = true forces <clinit> — where the native calls live.
            Class.forName(fqn, true, cl)
            println("  ✓ $fqn  (loaded + initialised)")
            passed++
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            println("  ✗ $fqn  — ${root.javaClass.simpleName}: ${root.message?.take(90)}")
            root.stackTrace.take(6).forEach { println("        at $it") }
            failed++
        }
    }

    // The headline assertion: Build.VERSION.SDK_INT must read a sane value.
    val sdk = runCatching {
        Class.forName("android.os.Build\$VERSION", true, cl).getField("SDK_INT").getInt(null)
    }.getOrElse { -1 }
    println()
    println("  → Build.VERSION.SDK_INT = $sdk  (expected 34 from the SystemProperties shim)")

    println()
    println("[native-test] $passed passed, $failed failed (of ${targets.size})")
    if (failed > 0 || sdk != 34) kotlin.system.exitProcess(1)
}
