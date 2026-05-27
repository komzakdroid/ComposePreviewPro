package com.composepreviewpro.renderer

import androidx.compose.runtime.Composer
import androidx.compose.runtime.internal.ComposableLambda
import androidx.compose.runtime.internal.composableLambdaInstance
import kotlin.reflect.KType

/**
 * Builds a no-op [ComposableLambda] for any `@Composable` function-type
 * parameter the MockEngine can't satisfy on its own.
 *
 * Why this exists
 * ---------------
 * `@Composable` content slots are extremely common in real Compose code
 * (`Box(content = …)`, `Card { … }`, `NiaButton(content = …)`). Without
 * a fallback, the preview tool would refuse to render any composable
 * that doesn't ship with a default value for its slot — which in
 * practice is most of them.
 *
 * The right behaviour is to synthesise an empty slot: the outer
 * composable still renders (frame, surface, modifier chain), and the
 * slot just emits nothing. That gives the user a meaningful preview
 * — the frame they care about — even when they haven't supplied
 * content. Same idea as Android Studio's Compose preview for default
 * slots.
 *
 * How it works
 * ------------
 * Compose Compiler rewrites every `@Composable Function<N>` into a
 * `Function<N+2>` with extra `(Composer, Int)` parameters. So:
 *
 *   • `@Composable () -> Unit`           → invoked as Function2(Composer, Int)
 *   • `@Composable RowScope.() -> Unit`  → invoked as Function3(R, Composer, Int)
 *   • `@Composable (T) -> Unit`          → invoked as Function3(T, Composer, Int)
 *   • `@Composable (A, B) -> Unit`       → invoked as Function4(A, B, Composer, Int)
 *   • … and so on.
 *
 * [composableLambdaInstance] from the Compose runtime wraps a raw
 * `block: Any` into a proper [ComposableLambda] that participates in
 * recomposition. The block's runtime type must match the arity the
 * caller will use, so we pick an arity-appropriate no-op lambda based
 * on the Kotlin-level [KType] of the parameter.
 *
 * The synth covers arity 0 (no receiver) through 7 — well past what
 * real Compose APIs use. Anything beyond falls through to "unsupported"
 * with a clear message rather than NPE'ing the user.
 *
 * Why we don't draw a placeholder
 * --------------------------------
 * An empty slot is the LEAST surprising default — the user sees the
 * outer composable exactly as they wrote it. A placeholder Text would
 * leak into the rendered preview and confuse readers about what the
 * actual content will look like. If the user wants to test content,
 * they pass it as an override.
 */
internal object ComposableLambdaSynth {

    /**
     * Returns a no-op `ComposableLambda` cast as [Any] if [type] is an
     * `@Composable` function reference; `null` otherwise so the caller
     * can fall back to the regular Unsupported path.
     */
    fun tryNoOp(type: KType): Any? {
        if (!type.isComposable()) return null

        // The Kotlin-level arity is the FunctionN suffix on the
        // classifier (Function0, Function1, …). After Compose Compiler
        // adds Composer + Int, the JVM-level arity is `kotlinArity + 2`.
        val kotlinArity = type.kotlinArity() ?: return null

        // Build a block whose JVM `invoke(...)` matches the expected
        // (args…, Composer, Int) signature. We delegate to one of the
        // pre-instantiated no-op singletons below.
        val block: Any = when (kotlinArity) {
            0 -> NoOp0
            1 -> NoOp1
            2 -> NoOp2
            3 -> NoOp3
            4 -> NoOp4
            5 -> NoOp5
            6 -> NoOp6
            7 -> NoOp7
            else -> return null  // Higher arities are vanishingly rare.
        }
        return composableLambdaInstance(
            // Key is arbitrary but should be stable per synth so
            // recomposition can deduplicate. We use a hash of the
            // type's string representation.
            key = type.toString().hashCode(),
            tracked = false,
            block = block,
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Detection helpers
    // ──────────────────────────────────────────────────────────────

    private fun KType.isComposable(): Boolean =
        annotations.any { it.annotationClass.qualifiedName == "androidx.compose.runtime.Composable" }

    /**
     * Extract the FunctionN arity from a `kotlin.FunctionN<…>`-shaped
     * KType. Returns `null` if the classifier isn't a function type.
     */
    private fun KType.kotlinArity(): Int? {
        val name = (classifier as? kotlin.reflect.KClass<*>)?.qualifiedName ?: return null
        if (!name.startsWith("kotlin.Function")) return null
        return name.removePrefix("kotlin.Function").toIntOrNull()
    }

    // ──────────────────────────────────────────────────────────────
    // Arity-keyed no-op singletons.
    //
    // Each block accepts the user-level args followed by (Composer,
    // Int) and returns Unit. The Composer is ignored — we don't emit
    // anything, which gives the empty-slot semantics. Reusing
    // singletons across mocks lets Compose's structural equality
    // dedupe them at recomposition time.
    // ──────────────────────────────────────────────────────────────

    private val NoOp0: (Composer, Int) -> Unit = { _, _ -> }
    private val NoOp1: (Any?, Composer, Int) -> Unit = { _, _, _ -> }
    private val NoOp2: (Any?, Any?, Composer, Int) -> Unit = { _, _, _, _ -> }
    private val NoOp3: (Any?, Any?, Any?, Composer, Int) -> Unit = { _, _, _, _, _ -> }
    private val NoOp4: (Any?, Any?, Any?, Any?, Composer, Int) -> Unit = { _, _, _, _, _, _ -> }
    private val NoOp5: (Any?, Any?, Any?, Any?, Any?, Composer, Int) -> Unit =
        { _, _, _, _, _, _, _ -> }
    private val NoOp6: (Any?, Any?, Any?, Any?, Any?, Any?, Composer, Int) -> Unit =
        { _, _, _, _, _, _, _, _ -> }
    private val NoOp7: (Any?, Any?, Any?, Any?, Any?, Any?, Any?, Composer, Int) -> Unit =
        { _, _, _, _, _, _, _, _, _ -> }
}
