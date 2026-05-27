package com.composepreviewpro.renderer

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue
import org.mockito.Mockito
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Scans the USER's classpath for `CompositionLocal`s the application
 * code defines itself, and binds each one to a Mockito mock of its
 * declared target type.
 *
 * Why this exists
 * ----------------
 * Real-world apps define their own theme-shaped composition locals:
 *
 *   ```
 *   val LocalAuroraColors = compositionLocalOf<AuroraColors> {
 *       error("AuroraColors not provided — wrap in MultiTaskTheme { ... }")
 *   }
 *   ```
 *
 * When the user clicks ▶ on a composable that reads `LocalAuroraColors
 * .current` without wrapping in `MultiTaskTheme { }`, the default
 * factory throws and the preview dies.
 *
 * Hand-rolled accessor lists (the [AndroidCompositionLocalProviders]
 * style) can't cover user-defined locals because we don't know the
 * package names ahead of time. This class walks the user's classpath
 * looking for any static `getLocal*()` method that returns a
 * `ProvidableCompositionLocal`, then provides each with a
 * default-answering Mockito mock of `T` (the local's target type).
 *
 * The Mockito mock approach trades fidelity for survival: the
 * composable doesn't crash; nested values like `colors.accentTimer`
 * return Mockito's default (typically `0`/`null`), which Compose
 * tolerates well enough for preview rendering. The user can still
 * write a `@Preview` wrapper composable that calls their real Theme
 * for a higher-fidelity render — this just removes the cliff.
 *
 * Performance bounds
 * ------------------
 * To keep the scan fast on multi-module projects we filter classpath
 * `.class` files by name — only files whose name hints at a Local
 * definition (`*Local*`, `*Theme*`, `*Colors*`, `*Style*`,
 * `*Provider*`) are loaded and inspected. In practice this brings the
 * scan from ~10,000 candidate files down to ~50, completing in
 * sub-second on NowInAndroid-sized projects.
 */
internal object UserCompositionLocalsDiscoverer {

    /**
     * Class-name suffixes / substrings that strongly suggest a file
     * containing `CompositionLocal` definitions. Filtering on these
     * keeps the scan to a manageable handful of class files per
     * classpath root.
     */
    private val likelyHostKeywords = listOf(
        "Local", "Theme", "Colors", "Color", "Style", "Provider", "Typography",
    )

    fun discover(
        classLoader: ClassLoader,
        classpathRoots: List<String>,
    ): List<ProvidedValue<*>> {
        val provideds = mutableListOf<ProvidedValue<*>>()
        val seenClasses = mutableSetOf<String>()
        var classesInspected = 0

        for (root in classpathRoots) {
            val rootFile = File(root)
            if (!rootFile.isDirectory) continue  // skip JARs for now
            rootFile.walkTopDown()
                .filter { it.isFile && it.name.endsWith("Kt.class") && looksLikeLocalHost(it.name) }
                .take(500)
                .forEach { classFile ->
                    val rel = classFile.toRelativeString(rootFile)
                        .removeSuffix(".class")
                    val fqn = rel.replace(File.separatorChar, '/').replace('/', '.')
                    // Skip platform/library namespaces so we don't reinvent
                    // wheels the dedicated discoverers handle.
                    if (isLibraryNamespace(fqn)) return@forEach
                    if (!seenClasses.add(fqn)) return@forEach
                    val klass = try {
                        classLoader.loadClass(fqn)
                    } catch (_: Throwable) {
                        return@forEach
                    }
                    classesInspected++
                    for (method in klass.declaredMethods) {
                        provideUserLocal(method, classLoader)?.let(provideds::add)
                    }
                }
        }
        System.err.println(
            "[UserCompositionLocalsDiscoverer] discovered ${provideds.size} user-defined " +
                "Locals from ${classesInspected} candidate classes",
        )
        return provideds
    }

    private fun looksLikeLocalHost(filename: String): Boolean =
        likelyHostKeywords.any { filename.contains(it, ignoreCase = false) }

    private fun isLibraryNamespace(fqn: String): Boolean =
        fqn.startsWith("androidx.") ||
            fqn.startsWith("android.") ||
            fqn.startsWith("kotlin.") ||
            fqn.startsWith("kotlinx.") ||
            fqn.startsWith("java.") ||
            fqn.startsWith("javax.") ||
            fqn.startsWith("com.android.") ||
            fqn.startsWith("org.jetbrains.compose.") ||
            fqn.startsWith("org.intellij.")

    /**
     * Inspect a single `getLocalXxx` static method. Returns a
     * `ProvidedValue<*>` that binds the local to a Mockito mock of its
     * declared target type, or null when:
     *
     *   • The method isn't a zero-arg static getter named `getLocalXxx`.
     *   • The returned object isn't a `ProvidableCompositionLocal`
     *     (some `getLocal*` methods are unrelated to Compose).
     *   • The target type can't be introspected (raw types from very
     *     old Java code, type parameters, etc.).
     *   • The target type is primitive — Mockito refuses primitives.
     *   • Mockito itself refuses the mock for any reason.
     */
    private fun provideUserLocal(method: Method, classLoader: ClassLoader): ProvidedValue<*>? {
        if (method.parameterCount != 0) return null
        if (!Modifier.isStatic(method.modifiers)) return null
        if (!method.name.startsWith("getLocal")) return null
        method.isAccessible = true

        val local = try {
            method.invoke(null)
        } catch (_: Throwable) {
            return null
        } ?: return null
        if (local !is ProvidableCompositionLocal<*>) return null

        val targetClass = rawTargetClass(method, classLoader) ?: return null
        if (targetClass.isPrimitive) return null

        val mock = try {
            Mockito.mock(
                targetClass,
                Mockito.withSettings()
                    .defaultAnswer(Mockito.RETURNS_DEFAULTS)
                    .stubOnly(),
            )
        } catch (_: Throwable) {
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val typedLocal = local as ProvidableCompositionLocal<Any?>
        return try {
            typedLocal provides mock
        } catch (_: Throwable) {
            null
        }
    }

    private fun rawTargetClass(method: Method, classLoader: ClassLoader): Class<*>? {
        val genericType = method.genericReturnType as? ParameterizedType ?: return null
        val targetType = genericType.actualTypeArguments.firstOrNull() ?: return null
        return rawOf(targetType, classLoader)
    }

    private fun rawOf(type: Type, classLoader: ClassLoader): Class<*>? {
        return when (type) {
            is Class<*> -> type
            is ParameterizedType -> rawOf(type.rawType, classLoader)
            is java.lang.reflect.WildcardType ->
                type.upperBounds.firstOrNull()?.let { rawOf(it, classLoader) }
            else -> null
        }
    }
}
