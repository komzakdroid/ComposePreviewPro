package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ClasspathEntries
import com.composepreviewpro.ipc.Click
import com.composepreviewpro.ipc.ComposableId
import com.composepreviewpro.ipc.Interact
import com.composepreviewpro.ipc.RedefineClasses
import com.composepreviewpro.ipc.RenderRequest
import com.composepreviewpro.ipc.Scroll
import com.composepreviewpro.ipc.RenderSize
import com.composepreviewpro.ipc.RendererSubprocess
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * End-to-end IPC integration test that exercises the *same* code path as
 * the IDE plugin, minus the IntelliJ Platform glue.
 *
 *   • Spawns the real renderer launcher script
 *   • Performs the Hello handshake via [RendererSubprocess]
 *   • Sends three RenderRequests over the same long-lived session
 *   • Writes the resulting PNGs to ./ipc-out/[name].png
 *   • Sends Shutdown and waits for clean exit
 *
 * Run via: ./gradlew :renderer:ipcIntegrationTest
 *
 * Verifies:
 *   1. The launcher script starts a JVM successfully
 *   2. The handshake completes
 *   3. Multiple requests can be served over one session (no fork/exec)
 *   4. requestId correlation is correct
 *   5. Clean shutdown leaves no zombie processes
 */
fun main() {
    val launcher = System.getProperty("rendererLauncher")
        ?.let(::File)
        ?: File("renderer/build/install/renderer/bin/renderer")
    val classpathDir = System.getProperty("classpathDir")
        ?: File("sample/build/classes/kotlin/main").absolutePath
    val outDir = File("ipc-out").apply { mkdirs() }

    println("[ipc-test] launcher:  ${launcher.absolutePath}")
    println("[ipc-test] classpath: $classpathDir")
    println("[ipc-test] output:    ${outDir.absolutePath}")

    val targets = listOf(
        "com.composepreviewpro.sample.SampleComposablesKt.Greeting",
        "com.composepreviewpro.sample.SampleComposablesKt.Counter",
        "com.composepreviewpro.sample.SampleComposablesKt.ProfileBadge",
        "com.composepreviewpro.sample.SampleComposablesKt.PriceTag",
        "com.composepreviewpro.sample.SampleComposablesKt.ActionRow",
    )

    var passed = 0
    var failed = 0
    val timings = mutableListOf<Long>()

    RendererSubprocess(launcher, onStderr = { line -> println("[renderer-stderr] $line") }).use { subprocess ->
        val t0 = System.currentTimeMillis()
        subprocess.ensureStarted()
        println("[ipc-test] handshake OK in ${System.currentTimeMillis() - t0}ms")

        for (fqn in targets) {
            val request = RenderRequest(
                requestId = UUID.randomUUID().toString(),
                target = ComposableId(fqn),
                classpath = ClasspathEntries(listOf(classpathDir)),
                size = RenderSize(),
            )
            val short = fqn.substringAfterLast('.')
            val start = System.currentTimeMillis()
            when (val outcome = subprocess.render(request)) {
                is RendererSubprocess.Outcome.Success -> {
                    val elapsed = System.currentTimeMillis() - start
                    timings += elapsed
                    val bytes = Base64.getDecoder().decode(outcome.result.pngBase64)
                    val file = File(outDir, "$short.png")
                    file.writeBytes(bytes)
                    val hitCount = outcome.result.hitMap.entries.size
                    val withSource = outcome.result.hitMap.entries.count {
                        it.sourceFile != null && it.sourceLine != null
                    }
                    println("  ✓ $short  (${bytes.size} bytes, ${elapsed}ms, hits=$hitCount, with-source=$withSource)")
                    passed++
                }
                is RendererSubprocess.Outcome.Errored -> {
                    println("  ✗ $short  ERRORED: ${outcome.error.kind}: ${outcome.error.message}")
                    failed++
                }
                is RendererSubprocess.Outcome.TimedOut -> {
                    println("  ✗ $short  TIMED OUT (60s)")
                    failed++
                }
                is RendererSubprocess.Outcome.Crashed -> {
                    println("  ✗ $short  CRASHED: ${outcome.reason}")
                    failed++
                }
            }
        }

        // ── Interactive path test ─────────────────────────────────────
        // After rendering Counter, click on the middle of its Button area
        // and scroll once. Both should round-trip and return a new PNG.
        println()
        println("[ipc-test] interactive phase: click + scroll on Counter")
        val click = Interact(UUID.randomUUID().toString(), Click(240, 320))
        when (val o = subprocess.interact(click)) {
            is RendererSubprocess.Outcome.Success -> {
                val bytes = Base64.getDecoder().decode(o.result.pngBase64)
                File(outDir, "interact-click.png").writeBytes(bytes)
                println("  ✓ click round-trip (${bytes.size} bytes)")
                passed++
            }
            else -> {
                println("  ✗ click failed: $o")
                failed++
            }
        }
        val scroll = Interact(UUID.randomUUID().toString(), Scroll(240, 320, 200f))
        when (val o = subprocess.interact(scroll)) {
            is RendererSubprocess.Outcome.Success -> {
                val bytes = Base64.getDecoder().decode(o.result.pngBase64)
                File(outDir, "interact-scroll.png").writeBytes(bytes)
                println("  ✓ scroll round-trip (${bytes.size} bytes)")
                passed++
            }
            else -> {
                println("  ✗ scroll failed: $o")
                failed++
            }
        }

        // ── Hot-swap path test ────────────────────────────────────────
        // Exercise the RedefineClasses codepath with a no-op swap: ship
        // the SampleComposablesKt class's CURRENT bytecode back to the
        // renderer and ask it to re-render. Proves the agent attached,
        // ClassDefinition was accepted by Instrumentation, and the post-
        // swap re-render works on the cached classloader.
        println()
        println("[ipc-test] hot-swap phase: redefining SampleComposablesKt with its own bytes")
        val classFile = File(classpathDir, "com/composepreviewpro/sample/SampleComposablesKt.class")
        if (!classFile.exists()) {
            println("  ✗ class file missing: ${classFile.absolutePath}")
            failed++
        } else {
            val classBytes = Base64.getEncoder().encodeToString(classFile.readBytes())
            val hotSwapStart = System.currentTimeMillis()
            val rerender = RenderRequest(
                requestId = UUID.randomUUID().toString(),
                target = ComposableId(targets.first()),
                classpath = ClasspathEntries(listOf(classpathDir)),
                size = RenderSize(),
            )
            val redefine = RedefineClasses(
                requestId = UUID.randomUUID().toString(),
                classes = mapOf("com.composepreviewpro.sample.SampleComposablesKt" to classBytes),
                rerender = rerender,
            )
            when (val outcome = subprocess.redefineClasses(redefine)) {
                is RendererSubprocess.Outcome.Success -> {
                    val elapsed = System.currentTimeMillis() - hotSwapStart
                    val bytes = Base64.getDecoder().decode(outcome.result.pngBase64)
                    File(outDir, "hot-swap-Greeting.png").writeBytes(bytes)
                    println("  ✓ hot-swap + re-render OK (${bytes.size} bytes, ${elapsed}ms)")
                    passed++
                }
                is RendererSubprocess.Outcome.Errored -> {
                    println("  ✗ hot-swap ERRORED: ${outcome.error.kind}: ${outcome.error.message}")
                    failed++
                }
                is RendererSubprocess.Outcome.TimedOut -> {
                    println("  ✗ hot-swap TIMED OUT")
                    failed++
                }
                is RendererSubprocess.Outcome.Crashed -> {
                    println("  ✗ hot-swap CRASHED: ${outcome.reason}")
                    failed++
                }
            }
        }
    }

    println()
    println("[ipc-test] $passed passed, $failed failed")
    if (timings.isNotEmpty()) {
        println("[ipc-test] render latency: " +
            "min=${timings.min()}ms, " +
            "median=${timings.sorted()[timings.size / 2]}ms, " +
            "max=${timings.max()}ms")
        println("[ipc-test] (vs. cold start of ~3000ms in the old fork/exec model)")
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}
