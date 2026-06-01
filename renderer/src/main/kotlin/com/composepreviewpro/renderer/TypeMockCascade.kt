package com.composepreviewpro.renderer

import com.composepreviewpro.mock.MockContext
import com.composepreviewpro.mock.MockEngine
import com.composepreviewpro.mock.MockResult
import kotlin.reflect.KType

/**
 * The full type-mocking cascade in one place, so every caller fabricates
 * values identically:
 *
 *   MockEngine (pure-Kotlin: primitives, enums, collections, sealed, data
 *   classes) → ComposableLambdaSynth (@Composable lambda slots) →
 *   ComposeTypeMocks (Color, Dp, Brush, ImageVector, …) → AdvancedTypeMocks
 *   (State/Flow/Lazy/ViewModel, recursing here for inner T) → UniversalTypeMocks
 *   (stdlib + Mockito last resort).
 *
 * Extracted from ArgumentBinder so the pure-Java invocation path
 * ([JavaComposableInvoker]) — used for value-class-mangled composables that
 * have no usable KFunction — can still fabricate REAL values for ordinary
 * object parameters (e.g. a `data class DownloadableMedia`) instead of a bare
 * Mockito mock whose String getters return null and NPE inside `Text(...)`.
 */
internal object TypeMockCascade {

    fun mock(type: KType, classLoader: ClassLoader): Any? {
        // 1. Pure-Kotlin base layer. The externalMocker hook lets MockEngine,
        //    while building a data class, fabricate fields of types only the
        //    renderer layers know (Compose UI types, kotlinx.datetime, etc.) —
        //    so the data class builds whole instead of falling back to a
        //    null-field mock. The hook deliberately excludes MockEngine itself
        //    to avoid infinite recursion.
        val ctx = MockContext(
            classLoader = classLoader,
            externalMocker = { t -> rendererLayers(t, classLoader) },
        )
        when (val res = MockEngine.mock(type, ctx)) {
            is MockResult.Success -> return res.value
            is MockResult.Unsupported -> Unit
        }
        return rendererLayers(type, classLoader)
    }

    /** The renderer-specific layers only (everything except MockEngine). */
    private fun rendererLayers(type: KType, classLoader: ClassLoader): Any? {
        // @Composable lambda → no-op ComposableLambda.
        ComposableLambdaSynth.tryNoOp(type)?.let { return it }
        // Compose UI standard types (Color, Dp, Brush, …).
        ComposeTypeMocks.tryDefault(type)?.let { return it }
        // State/Flow/Lazy/ViewModel — inner types recurse through the FULL cascade.
        AdvancedTypeMocks.tryDefault(type, classLoader) { mock(it, classLoader) }?.let { return it }
        // Universal stdlib + Mockito fallback.
        UniversalTypeMocks.tryDefault(type) { mock(it, classLoader) }?.let { return it }
        return null
    }
}
