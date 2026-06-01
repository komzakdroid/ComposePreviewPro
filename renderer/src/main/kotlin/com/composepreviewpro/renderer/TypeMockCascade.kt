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
        // 1. Pure-Kotlin base layer.
        when (val res = MockEngine.mock(type, MockContext(classLoader = classLoader))) {
            is MockResult.Success -> return res.value
            is MockResult.Unsupported -> Unit
        }
        // 2. @Composable lambda → no-op ComposableLambda.
        ComposableLambdaSynth.tryNoOp(type)?.let { return it }
        // 3. Compose UI standard types (Color, Dp, Brush, …).
        ComposeTypeMocks.tryDefault(type)?.let { return it }
        // 4. State/Flow/Lazy/ViewModel — recurses through me again.
        AdvancedTypeMocks.tryDefault(type, classLoader) { mock(it, classLoader) }?.let { return it }
        // 5. Universal stdlib + Mockito fallback.
        UniversalTypeMocks.tryDefault(type) { mock(it, classLoader) }?.let { return it }
        return null
    }
}
