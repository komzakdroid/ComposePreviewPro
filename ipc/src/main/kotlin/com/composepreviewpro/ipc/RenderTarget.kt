package com.composepreviewpro.ipc

import kotlinx.serialization.Serializable

/**
 * Fully-qualified name of a top-level @Composable function, e.g.
 * "com.example.ui.GreetingKt.Greeting".
 *
 * Note the trailing `Kt` on the file class — top-level Kotlin functions live
 * inside a synthetic class named after the source file. The renderer needs
 * this exact name to resolve the function via reflection.
 */
@JvmInline
@Serializable
value class ComposableId(val fqn: String) {
    /** "com.example.ui.GreetingKt" — the synthetic file class. */
    val className: String get() = fqn.substringBeforeLast('.')

    /** "Greeting" — the function name within that class. */
    val functionName: String get() = fqn.substringAfterLast('.')
}

/**
 * Absolute paths to JAR files and class directories that together form the
 * classpath the renderer must use to load the user's composable.
 *
 * Why a list of strings, not URLs or Files? IPC must be serialisable and
 * cross-process. The receiver converts these to URL[] for URLClassLoader.
 */
@Serializable
data class ClasspathEntries(val paths: List<String>)

/** Desired pixel dimensions of the rendered output. */
@Serializable
data class RenderSize(val widthPx: Int = 480, val heightPx: Int = 800)

/**
 * Light or dark Material theme applied as an outer wrapper to the user's
 * composable. If the user's code already supplies its own MaterialTheme,
 * that inner wrap takes precedence — this just sets the default.
 */
@Serializable
enum class PreviewTheme { LIGHT, DARK }
