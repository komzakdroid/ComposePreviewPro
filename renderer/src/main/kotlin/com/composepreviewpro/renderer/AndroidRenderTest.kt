package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ClasspathEntries
import com.composepreviewpro.ipc.ComposableId
import com.composepreviewpro.ipc.PreviewTheme
import java.io.File
import java.util.Base64

/**
 * End-to-end render of REAL Android composables (compiled by AGP against
 * AndroidX Compose, with a generated R class + resources) through the full
 * production path. Validates the Android-specific surface the desktop samples
 * cannot reach:
 *   • stringResource(R.string.…)                — Resources stub
 *   • painterResource(R.drawable.…)             — vector resource fabrication
 *   • windowInsetsPadding(WindowInsets.statusBars) — android-all + native runtime
 *
 *   ./gradlew :renderer:androidRenderTest
 */
fun main() {
    val inst = AgentLoader.ensureLoaded()
    NativeMethodNeutralizer.installInto(inst)

    val cpFile = File("sample-android/build/render-classpath.txt")
    if (!cpFile.isFile) error("render-classpath.txt missing — run :sample-android:dumpRenderClasspath")
    val androidJar = System.getProperty("androidJar")
        ?: (System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-36/android.jar" })
        ?: error("android.jar not found; set -DandroidJar or ANDROID_HOME")

    val entries = cpFile.readLines().filter { it.isNotBlank() } + androidJar
    val classpath = ClasspathEntries(entries)
    val outDir = File("android-out").apply { mkdirs() }

    val pkg = "com.composepreviewpro.sampleandroid.AndroidComposablesKt"
    val targets = listOf(
        "AndroidStringCard" to "stringResource(R.string) + WindowInsets.statusBars",
        "AndroidIconRow" to "painterResource(R.drawable vector) + stringResource",
        "AndroidFullCard" to "painterResource + stringResource + insets together",
    )

    println("[android-test] classpath entries: ${entries.size}")
    println("[android-test] android.jar: $androidJar")
    println("[android-test] android-all: ${AndroidRuntimeProvisioner.locate()?.name ?: "<missing>"}")
    println()

    var passed = 0
    var failed = 0
    ComposableResolver(classpath).use { resolver ->
        for ((name, note) in targets) {
            val id = ComposableId("$pkg.$name")
            try {
                val fn = resolver.resolve(id)
                val method = if (fn == null) resolver.resolveMethod(id) else null
                if (fn == null && method == null) error("TARGET_NOT_FOUND")
                val bind = fn?.let {
                    when (val b = ArgumentBinder(resolver.classLoader).bind(it)) {
                        is ArgumentBinder.BindResult.Failed -> error("MOCK_UNSUPPORTED: ${b.reason}")
                        is ArgumentBinder.BindResult.Ready -> b
                    }
                }
                InteractiveSession(widthPx = 560, heightPx = 760, theme = PreviewTheme.LIGHT).use { session ->
                    val png = if (fn != null) {
                        session.mount(fn, bind!!.args, classpath.paths)
                    } else {
                        session.mountJava(method!!, classpath.paths)
                    }
                    val bytes = Base64.getDecoder().decode(png)
                    File(outDir, "$name.png").writeBytes(bytes)
                    println("  ✓ ${name.padEnd(20)} ${bytes.size} bytes — $note")
                    passed++
                }
            } catch (t: Throwable) {
                val root = generateSequence(t) { it.cause }.last()
                println("  ✗ ${name.padEnd(20)} ${root.javaClass.simpleName}: ${root.message?.take(110)}")
                root.stackTrace.take(4).forEach { println("        at $it") }
                failed++
            }
        }
    }

    println()
    println("[android-test] $passed passed, $failed failed (of ${targets.size})")
    if (failed > 0) kotlin.system.exitProcess(1)
}
