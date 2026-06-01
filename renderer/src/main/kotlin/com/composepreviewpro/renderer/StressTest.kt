package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ClasspathEntries
import com.composepreviewpro.ipc.Click
import com.composepreviewpro.ipc.ComposableId
import com.composepreviewpro.ipc.PreviewTheme
import java.io.File
import java.util.Base64

/**
 * Hard-mode render battery. Unlike [SmokeTest] (which uses the bare
 * [OffscreenRenderer]), this drives the **full** [InteractiveSession] path —
 * the same code the IPC server runs — so every CompositionLocal provider, the
 * ViewModel-owner stub, value-class name-mangling resolution, and the
 * interaction loop are all exercised end to end.
 *
 *   ./gradlew :renderer:stressTest
 *
 * Targets are the composables in :sample/StressComposables.kt, chosen to cover
 * progressively nastier shapes. Each renders to ./stress-out/<name>.png.
 */
fun main() {
    val classpathDir = System.getProperty("classpathDir")
        ?: File("sample/build/classes/kotlin/main").absolutePath
    // User module classes dir (where StressComposablesKt lives) + ALL of
    // :sample's transitive dependency jars (compose, lifecycle-viewmodel-
    // compose, coroutines, …) supplied by the Gradle task. This mirrors what
    // the plugin's PreviewService assembles in production: the module's own
    // class-output root FIRST, dependency jars after.
    val deps = System.getProperty("sampleClasspath")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    val fullClasspath = (listOf(classpathDir) + deps).distinct()
    val outDir = File("stress-out").apply { mkdirs() }

    val pkg = "com.composepreviewpro.sample.StressComposablesKt"
    val mmPkg = "com.composepreviewpro.sample.MultiModuleComposablesKt"
    data class Target(
        val name: String,
        val note: String,
        val interactive: Boolean = false,
        val fqn: String? = null,
    )

    val targets = listOf(
        Target("AccentChip", "value-class param (Color, no default) → mangled name + auto-mock"),
        Target("MoneyTag", "custom @JvmInline value class default → mangled name"),
        Target("PrivateBadge", "PRIVATE + value-class default → private mangled JVM method"),
        Target("PrivateBadgeHost", "public host of a private composable"),
        Target("DeeplyNested", "6-level recursive nesting"),
        Target("ComplexStateBoard", "remember + derivedStateOf + mutableStateListOf"),
        Target("LazyFeed", "LazyColumn with stable keys + contentType"),
        Target("ConfusingSignature", "defaults + nullable + vararg"),
        Target("GenericList", "generic <T> composable"),
        Target("SealedStateCard", "sealed-interface state + exhaustive when"),
        Target("ViewModelScreen", "viewModel() → LocalViewModelStoreOwner provider", interactive = true),
        Target("InteractiveCounter", "clickable local-state mutation", interactive = true),
        Target("BrandedHeader", "reads a user CompositionLocal with a THROWING default"),
        Target("BrandedHeaderHost", "same Local, self-provided (control)"),
        Target("MixedSignature", "Color + custom value class + defaults (heavy mangling)"),
        Target("MediaRow", "value-class Color param + data-class param read in Text() (MediaCard shape)"),
        Target("FeedArticleCard", "data class w/ INTERNAL ctor + nested list + java.time field (NiA shape)"),
        // ── Multi-module / shared-UI: composables in :sample consuming the
        //    separate :sample-designsystem module (widgets, theme Local, types).
        Target("FeatureCardNoTheme", "cross-module GradientCard + cross-module throwing Local", fqn = "$mmPkg.FeatureCardNoTheme"),
        Target("FeatureWithPalette", "cross-module Palette type as parameter", fqn = "$mmPkg.FeatureWithPalette"),
        Target("FeatureDashboard", "feature screen composing cross-module widgets", fqn = "$mmPkg.FeatureDashboard"),
    )

    println("[stress] classpath entries: ${fullClasspath.size}")
    println("[stress] output:    ${outDir.absolutePath}")
    println("[stress] targets:   ${targets.size}")
    println()

    val classpath = ClasspathEntries(fullClasspath)

    var passed = 0
    var failed = 0
    val failures = mutableListOf<String>()

    ComposableResolver(classpath).use { resolver ->
        for (target in targets) {
            val id = ComposableId(target.fqn ?: "$pkg.${target.name}")
            try {
                // Kotlin path first; fall back to the pure-Java path for
                // value-class-mangled composables kotlin-reflect can't model.
                val fn = resolver.resolve(id)
                val method = if (fn == null) resolver.resolveMethod(id) else null
                if (fn == null && method == null) {
                    error("TARGET_NOT_FOUND — resolver could not find ${target.name}")
                }
                val bind = fn?.let {
                    when (val b = ArgumentBinder(resolver.classLoader).bind(it)) {
                        is ArgumentBinder.BindResult.Failed ->
                            error("MOCK_UNSUPPORTED — param '${b.parameter.name}': ${b.reason}")
                        is ArgumentBinder.BindResult.Ready -> b
                    }
                }
                val via = if (fn != null) "kotlin" else "java"

                val session = InteractiveSession(widthPx = 560, heightPx = 760, theme = PreviewTheme.LIGHT)
                session.use {
                    val firstPng = if (fn != null) {
                        session.mount(fn, bind!!.args, classpath.paths)
                    } else {
                        session.mountJava(method!!, classpath.paths)
                    }
                    val firstBytes = Base64.getDecoder().decode(firstPng)
                    File(outDir, "${target.name}.png").writeBytes(firstBytes)

                    var interactionNote = ""
                    if (target.interactive) {
                        // Tap somewhere a button/clickable lives, then re-render;
                        // a byte-level frame change proves the click fired and
                        // the composition recomposed (live, stateful preview).
                        val afterPng = session.interact(Click(x = 120, y = 120))
                        val afterBytes = Base64.getDecoder().decode(afterPng)
                        File(outDir, "${target.name}-after-click.png").writeBytes(afterBytes)
                        interactionNote = if (!afterPng.equals(firstPng)) {
                            " | click → frame changed ✓"
                        } else {
                            " | click → no change (clickable not under cursor?)"
                        }
                    }

                    println("  ✓ ${target.name.padEnd(20)} [${via.padEnd(6)}] ${firstBytes.size} bytes  — ${target.note}$interactionNote")
                    passed++
                }
            } catch (t: Throwable) {
                failed++
                val root = generateSequence(t) { it.cause }.last()
                val msg = "${root.javaClass.simpleName}: ${root.message?.take(140)}"
                println("  ✗ ${target.name.padEnd(20)} $msg")
                failures += "${target.name} — ${target.note}\n      → $msg"
            }
        }
    }

    println()
    println("[stress] $passed passed, $failed failed (of ${targets.size})")
    if (failures.isNotEmpty()) {
        println()
        println("[stress] failure detail:")
        failures.forEach { println("  • $it") }
    }
}
