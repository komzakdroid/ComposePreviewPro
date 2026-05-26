package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ClasspathEntries
import com.composepreviewpro.ipc.ComposableId
import java.io.File
import java.util.Base64

/**
 * Standalone entry point used during development to verify the rendering
 * pipeline end-to-end without an IDE or IPC wiring.
 *
 * Usage: ./gradlew :renderer:smokeTest -PtargetFqn=<fqn> -PclasspathDir=<dir>
 * Defaults render every sample composable and write PNGs to ./smoke-out/.
 */
fun main(args: Array<String>) {
    val classpathDir = System.getProperty("classpathDir")
        ?: File("sample/build/classes/kotlin/main").absolutePath
    val outDir = File("smoke-out").apply { mkdirs() }

    val targets = if (args.isNotEmpty()) {
        args.toList()
    } else listOf(
        // Primitive / lambda heavy composables
        "com.composepreviewpro.sample.SampleComposablesKt.Greeting",
        "com.composepreviewpro.sample.SampleComposablesKt.Counter",
        "com.composepreviewpro.sample.SampleComposablesKt.ProfileBadge",
        "com.composepreviewpro.sample.SampleComposablesKt.PriceTag",
        "com.composepreviewpro.sample.SampleComposablesKt.ActionRow",
        // Data class / list / enum heavy screens
        "com.composepreviewpro.sample.SampleScreensKt.UserProfileScreen",
        "com.composepreviewpro.sample.SampleScreensKt.ChatListScreen",
        "com.composepreviewpro.sample.SampleScreensKt.ProductDetailScreen",
        "com.composepreviewpro.sample.SampleScreensKt.DashboardScreen",
    )

    println("[smoke] classpath: $classpathDir")
    println("[smoke] output:    ${outDir.absolutePath}")
    println("[smoke] targets:   ${targets.size}")
    println()

    val classpath = ClasspathEntries(listOf(classpathDir))
    var passed = 0
    var failed = 0

    for (fqn in targets) {
        val id = ComposableId(fqn)
        val shortName = id.functionName
        try {
            ComposableResolver(classpath).use { resolver ->
                val fn = resolver.resolve(id)
                    ?: error("Function not found: $fqn")
                when (val bind = ArgumentBinder(resolver.classLoader).bind(fn)) {
                    is ArgumentBinder.BindResult.Failed ->
                        error("Bind failed for ${bind.parameter.name}: ${bind.reason}")
                    is ArgumentBinder.BindResult.Ready -> {
                        val isScreen = fqn.contains("Screen")
                        val width = if (isScreen) 720 else 480
                        val height = if (isScreen) 1280 else 600
                        // Render every target in BOTH themes so we can
                        // visually compare light vs dark side-by-side.
                        for (theme in com.composepreviewpro.ipc.PreviewTheme.entries) {
                            val renderer = OffscreenRenderer(widthPx = width, heightPx = height, theme = theme)
                            val pngBase64 = renderer.renderToBase64Png(fn, bind.args)
                            val bytes = Base64.getDecoder().decode(pngBase64)
                            val suffix = theme.name.lowercase()
                            File(outDir, "$shortName-$suffix.png").writeBytes(bytes)
                            println("  ✓ $shortName-$suffix  (${bytes.size} bytes, ${width}x${height})")
                        }
                        passed++
                    }
                }
            }
        } catch (t: Throwable) {
            failed++
            println("  ✗ $shortName  ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    println()
    println("[smoke] $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
