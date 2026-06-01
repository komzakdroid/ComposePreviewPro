package com.composepreviewpro.renderer

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.ColorPainter
import org.mockito.Mockito
import org.mockito.stubbing.Answer
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
            // Directory roots ARE the user's own module class-output (jars —
            // the dependencies — are skipped above). A module's own code is
            // small (tens–hundreds of files), so we scan every top-level
            // `*Kt.class` facade rather than keyword-filtering: a user can
            // declare a CompositionLocal in ANY file, not only `*Theme*` /
            // `*Color*` ones. The keyword filter was an over-cautious perf
            // guard that silently missed Locals in plain feature files.
            rootFile.walkTopDown()
                .filter { it.isFile && it.name.endsWith("Kt.class") }
                .take(2000)
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
                    // `declaredMethods` resolves every type in each method's
                    // signature; a class referencing a type absent from this
                    // classpath (optional dep, a sibling module not on the
                    // preview's classpath) throws NoClassDefFoundError here.
                    // Skip such classes — they simply yield no Locals — rather
                    // than letting the whole render fail.
                    val methods = try {
                        klass.declaredMethods
                    } catch (_: Throwable) {
                        return@forEach
                    }
                    for (method in methods) {
                        try {
                            provideUserLocal(method, classLoader)?.let(provideds::add)
                        } catch (_: Throwable) {
                            // One unintrospectable method must not abort the scan.
                        }
                    }
                }
        }
        System.err.println(
            "[UserCompositionLocalsDiscoverer] discovered ${provideds.size} user-defined " +
                "Locals from ${classesInspected} candidate classes",
        )
        return provideds
    }

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

        // Value-class-typed Locals (e.g. `compositionLocalOf<Color>`,
        // `<Dp>`) are common in design systems. Mockito cannot mock a Kotlin
        // value class — it produces a broken instance — so use the real
        // canonical default for those before falling back to a Mockito mock
        // of an ordinary class (data class / interface theme bundle).
        val value = knownValueDefault(targetClass)
            ?: try {
                // A plain RETURNS_DEFAULTS mock returns null for every object
                // getter — fatal for a theme bundle, because
                // `palette.heroGradient` (a non-null Brush) then NPEs at
                // `Modifier.background(brush)`. ComposeAwareAnswer returns a
                // sensible non-null value for the common non-null Compose
                // return types (Brush, Shape, Painter, String) so widgets that
                // read the bundle render instead of crashing. This is the
                // real-app `LocalAuroraColors`/`LocalAppColors` case.
                Mockito.mock(
                    targetClass,
                    Mockito.withSettings()
                        .defaultAnswer(ComposeAwareAnswer)
                        .stubOnly(),
                )
            } catch (_: Throwable) {
                return null
            }

        @Suppress("UNCHECKED_CAST")
        val typedLocal = local as ProvidableCompositionLocal<Any?>
        return try {
            typedLocal provides value
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Canonical default for Compose value-class types that Mockito cannot
     * fabricate. Returns null for ordinary classes (handled by Mockito).
     */
    private fun knownValueDefault(targetClass: Class<*>): Any? = when (targetClass.name) {
        "androidx.compose.ui.graphics.Color" -> Color.Unspecified
        "androidx.compose.ui.unit.Dp" -> androidx.compose.ui.unit.Dp.Unspecified
        "androidx.compose.ui.unit.TextUnit" -> androidx.compose.ui.unit.TextUnit.Unspecified
        else -> null
    }

    /**
     * Mockito default-answer that fabricates non-null values for the Compose
     * types a theme bundle commonly exposes as non-null properties. Value-class
     * getters (Color, Dp) are unboxed to primitives at the JVM level, so
     * Mockito's `0L` already yields a valid `Color(0)`/`Dp(0)` — only OBJECT
     * return types need help here. Falls back to Mockito's standard defaults
     * (null / 0 / false) for everything else.
     */
    private val ComposeAwareAnswer = Answer<Any?> { invocation ->
        when (invocation.method.returnType.name) {
            "androidx.compose.ui.graphics.Brush" -> SolidColor(Color.Transparent)
            "androidx.compose.ui.graphics.Shape" -> RectangleShape
            "androidx.compose.ui.graphics.painter.Painter" -> ColorPainter(Color.Transparent)
            "java.lang.String", "java.lang.CharSequence" -> ""
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
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
