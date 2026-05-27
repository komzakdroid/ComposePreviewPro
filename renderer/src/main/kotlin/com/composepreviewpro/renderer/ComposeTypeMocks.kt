package com.composepreviewpro.renderer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.staticFunctions
import kotlin.reflect.full.staticProperties

/**
 * Hand-rolled mock factories for the standard Compose types that crop
 * up in 95%+ of real composable signatures.
 *
 * The base [com.composepreviewpro.mock.MockEngine] can't handle these
 * because:
 *   • They're abstract / sealed and need specific factory functions to
 *     build instances (`ImageVector.Builder(...).build()`).
 *   • They have private constructors and rely on `Companion.Default` /
 *     `XxxDefaults.foo()`-style entry points.
 *   • They live in the user-classloader Compose modules — the
 *     mock-engine module can't even reference them at compile time.
 *
 * The renderer module DOES depend on Compose, so we handle them here.
 *
 * For types we don't recognise explicitly, [tryReflectiveDefault]
 * inspects the class for common "sensible default" patterns:
 *   • A companion-object property literally named `Default`, `Normal`,
 *     `Unspecified`, or `Empty`.
 *   • A `<TypeName>Defaults` companion class with a no-arg factory.
 *   • A no-arg public constructor.
 * That covers most third-party Compose libraries without requiring us
 * to hardcode every type.
 */
internal object ComposeTypeMocks {

    /**
     * Resolve a default value for [type]. Returns null when no recipe
     * matches — the caller falls back to the regular Unsupported error.
     *
     * The function MUST be safe to call from any thread, MUST NOT
     * allocate heavy resources, and MUST NOT touch the user's
     * classloader (the Compose types we reference are loaded by the
     * renderer's own classloader, which is fine because the user's
     * code is compiled against the same artifact ABI).
     */
    fun tryDefault(type: KType): Any? {
        val classifier = type.classifier as? KClass<*> ?: return null
        val fqn = classifier.qualifiedName ?: return null

        // Hand-rolled defaults for the well-known Compose types. Listed
        // by package for readability — each entry returns a value that
        // is visually neutral so the user sees the structure of their
        // composable even when an argument was not supplied.
        return when (fqn) {
            // ─── Foundation ─────────────────────────────────────────
            "androidx.compose.foundation.layout.PaddingValues" -> PaddingValues(0.dp)
            "androidx.compose.foundation.shape.RoundedCornerShape" -> RoundedCornerShape(0.dp)

            // ─── UI: graphics primitives ────────────────────────────
            "androidx.compose.ui.graphics.Color" -> Color.Unspecified
            "androidx.compose.ui.graphics.Brush" -> SolidColor(Color.Transparent)
            "androidx.compose.ui.graphics.Shape" -> RectangleShape
            "androidx.compose.ui.graphics.painter.Painter" -> ColorPainter(Color.Transparent)
            "androidx.compose.ui.graphics.vector.ImageVector" -> emptyImageVector()

            // ─── UI: alignment & sizing ─────────────────────────────
            "androidx.compose.ui.Alignment" -> Alignment.Center
            // Kotlin reflection reports nested-type qualified names with
            // `.` (not the JVM `$`). Match accordingly.
            "androidx.compose.ui.Alignment.Horizontal" -> Alignment.CenterHorizontally
            "androidx.compose.ui.Alignment.Vertical" -> Alignment.CenterVertically
            "androidx.compose.ui.unit.Dp" -> 0.dp
            "androidx.compose.ui.unit.TextUnit" -> TextUnit.Unspecified

            // ─── UI: text ───────────────────────────────────────────
            "androidx.compose.ui.text.TextStyle" -> TextStyle.Default
            "androidx.compose.ui.text.font.FontWeight" -> FontWeight.Normal
            "androidx.compose.ui.text.font.FontStyle" -> FontStyle.Normal
            "androidx.compose.ui.text.font.FontFamily" -> FontFamily.Default
            "androidx.compose.ui.text.style.TextAlign" -> TextAlign.Start
            "androidx.compose.ui.text.style.TextDecoration" -> TextDecoration.None
            "androidx.compose.ui.text.style.TextOverflow" -> TextOverflow.Clip

            // ─── UI: geometry & layout ──────────────────────────────
            "androidx.compose.ui.geometry.Size" -> Size.Zero
            "androidx.compose.ui.geometry.Offset" -> Offset.Zero
            "androidx.compose.ui.geometry.Rect" -> Rect.Zero
            "androidx.compose.ui.unit.IntSize" -> IntSize.Zero
            "androidx.compose.ui.unit.IntOffset" -> IntOffset.Zero
            "androidx.compose.ui.unit.Density" -> Density(1f)
            "androidx.compose.ui.unit.LayoutDirection" -> LayoutDirection.Ltr

            // ─── UI: text & input ───────────────────────────────────
            "androidx.compose.ui.text.AnnotatedString" -> AnnotatedString("")
            "androidx.compose.ui.text.input.TextFieldValue" -> TextFieldValue("")

            // ─── UI: graphics (more) ────────────────────────────────
            "androidx.compose.ui.graphics.Path" -> Path()
            "androidx.compose.ui.graphics.drawscope.Stroke" -> Stroke()

            // ─── Foundation interaction & scrolling ─────────────────
            "androidx.compose.foundation.interaction.MutableInteractionSource",
            "androidx.compose.foundation.interaction.InteractionSource" ->
                MutableInteractionSource()
            "androidx.compose.foundation.ScrollState" -> ScrollState(0)
            "androidx.compose.foundation.lazy.LazyListState" -> LazyListState()

            // ─── Material 3 ─────────────────────────────────────────
            // The colorScheme / typography defaults are wrapped in a
            // MaterialTheme call at the renderer level, but components
            // can request them by parameter — we hand them through.
            "androidx.compose.material3.ColorScheme" -> lightColorScheme()
            "androidx.compose.material3.Typography" -> Typography()
            "androidx.compose.material3.SnackbarHostState" -> SnackbarHostState()
            // Material3 *Colors / *Elevation / *Defaults are
            // themselves @Composable factory functions
            // (`ButtonDefaults.buttonColors()`, `CardDefaults
            // .cardElevation()`, …) — they can only be evaluated
            // inside a composition. ArgumentBinder runs BEFORE the
            // scene starts composing, so we can't synthesise them
            // here. Real Compose components almost universally
            // declare these parameters with default values, so the
            // KCallable.callBy(...) path inside the binder will fill
            // them in for us. Falling through to the reflective
            // default (and then to "skip if optional") is the right
            // behaviour.
            else -> tryReflectiveDefault(classifier)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Hand-rolled factories
    // ──────────────────────────────────────────────────────────────

    /**
     * Build an empty 24×24 placeholder ImageVector — no paths, so it
     * renders as a transparent space. We use a stable "name" so two
     * mocks compare equal and Compose can skip redundant recomposes.
     */
    private fun emptyImageVector(): ImageVector =
        ImageVector.Builder(
            name = "compose.preview.empty",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).build()

    // ──────────────────────────────────────────────────────────────
    // Reflective fallback
    // ──────────────────────────────────────────────────────────────

    /**
     * For types we don't recognise explicitly, look for:
     *
     *   1. A companion-object property whose name is one of the common
     *      "sentinel" names (Default, Normal, Unspecified, Empty).
     *   2. A static method on `<TypeName>Defaults` with no parameters.
     *   3. A public no-arg constructor.
     *
     * This handles third-party Compose libraries that follow the
     * conventional patterns (most do) without us having to enumerate
     * every type.
     */
    private fun tryReflectiveDefault(classifier: KClass<*>): Any? {
        // (1) Kotlin `object` declaration — singleton. Check BEFORE the
        // no-arg constructor branch, because objects have a private
        // no-arg ctor that, when accessed via setAccessible(true),
        // happily fabricates a NEW instance, breaking singleton identity.
        runCatching { classifier.objectInstance }.getOrNull()?.let { return it }

        // (2) Companion sentinel — `Default`, `Normal`, `Unspecified`,
        // `Empty`, `None`. Three lookup strategies per name:
        //    a) The companion's static property (Kotlin reflection).
        //    b) A field declared on the companion's Java class (covers
        //       regular `val` backing fields).
        //    c) A field declared on the OUTER class (Kotlin emits these
        //       for `@JvmField val` in a companion object — they live
        //       on the outer type, not the companion).
        val sentinels = listOf("Default", "Normal", "Unspecified", "Empty", "None")
        val companion = runCatching { classifier.companionObjectInstance }.getOrNull()
        if (companion != null) {
            val companionClass = companion::class
            for (sentinel in sentinels) {
                // (2a)
                companionClass.staticProperties
                    .firstOrNull { it.name == sentinel }
                    ?.let { prop ->
                        runCatching { prop.getter.call() }.getOrNull()?.let { return it }
                    }
                // (2b) companion-instance field
                runCatching {
                    companion.javaClass.getDeclaredField(sentinel).apply { isAccessible = true }.get(companion)
                }.getOrNull()?.let { return it }
                // (2c) outer-class static field
                runCatching {
                    classifier.java.getDeclaredField(sentinel).apply { isAccessible = true }.get(null)
                }.getOrNull()?.let { return it }
            }
            // No-arg factory in the companion — `Defaults.foo()` shape.
            val factory = companionClass.staticFunctions
                .firstOrNull { it.parameters.size <= 1 && it.returnType.classifier == classifier }
            if (factory != null) {
                runCatching { factory.call(companion) }.getOrNull()?.let { return it }
            }
        }

        // (3) Sibling `<Type>Defaults` class with no-arg factory.
        val fqn = classifier.qualifiedName ?: return null
        val defaultsFqn = "${fqn}Defaults"
        val defaultsClass = runCatching {
            Class.forName(defaultsFqn, true, classifier.java.classLoader).kotlin
        }.getOrNull()
        if (defaultsClass != null) {
            val factory = defaultsClass.staticFunctions
                .firstOrNull { it.parameters.isEmpty() && it.returnType.classifier == classifier }
            if (factory != null) {
                runCatching { factory.call() }.getOrNull()?.let { return it }
            }
        }

        // (4) Public no-arg constructor — LAST resort, fabricates a fresh
        // instance. Tried after object/sentinel branches so we don't
        // overrule canonical singletons.
        val noArgCtor = runCatching {
            classifier.java.getDeclaredConstructor().apply { isAccessible = true }
        }.getOrNull()
        if (noArgCtor != null) {
            runCatching { noArgCtor.newInstance() }.getOrNull()?.let { return it }
        }

        return null
    }

    private inline fun <T> safe(crossinline block: () -> T): T? =
        try { block() } catch (_: Throwable) { null }
}
