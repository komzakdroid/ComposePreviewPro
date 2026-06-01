package com.composepreviewpro.mock

/**
 * Threaded through every recursive mock call.
 *
 * [paramName] — human-readable name of the parameter being mocked, when
 * available from KParameter. Drives smart-string heuristics so an `email:
 * String` field receives `"user@example.com"` instead of a generic stub.
 *
 * [depth] — guards against infinite recursion when (future) data-class
 * mockers reach a cyclic structure (e.g. `data class Node(val next: Node)`).
 * MVP does not recurse, so depth stays at 0, but the field is here so the
 * API never needs to change later.
 *
 * [classLoader] — lambdas are mocked via [java.lang.reflect.Proxy], which
 * needs a ClassLoader that can see the lambda's function interface. The
 * caller (the renderer) supplies the URLClassLoader holding user code so
 * proxies resolve correctly.
 */
data class MockContext(
    val paramName: String? = null,
    val depth: Int = 0,
    val classLoader: ClassLoader = MockContext::class.java.classLoader,
    /**
     * Optional AI-backed mock provider for richer String values. When
     * non-null, the engine consults it before the regex-based smart-string
     * heuristics for `String` parameters. Callers wire this from the
     * renderer's [com.composepreviewpro.renderer.AiMockClient].
     */
    val aiStringMocker: ((paramName: String, typeName: String, hint: String) -> String?)? = null,
    /**
     * Surrounding type that owns this parameter — typically the data class
     * name (`"Booking"`, `"User"`). Improves AI mock realism a lot.
     */
    val callerTypeName: String = "",
    /**
     * Escape hatch for types the pure-Kotlin engine has no recipe for
     * (third-party classes like `kotlinx.datetime.Instant`, Compose UI types,
     * etc.). The renderer wires this to its richer layers (ComposeTypeMocks /
     * AdvancedTypeMocks / Mockito) so that a data-class field of such a type is
     * still fabricated — otherwise the whole data class falls back to a bare
     * mock with null fields, which NPEs the moment a composable reads
     * `model.title`. Must NOT call back into [MockEngine] (infinite loop); the
     * renderer's hook runs only the non-MockEngine layers.
     */
    val externalMocker: ((kotlin.reflect.KType) -> Any?)? = null,
) {
    fun child(name: String?): MockContext =
        copy(paramName = name, depth = depth + 1)

    fun childWithCaller(name: String?, caller: String): MockContext =
        copy(paramName = name, depth = depth + 1, callerTypeName = caller)
}
