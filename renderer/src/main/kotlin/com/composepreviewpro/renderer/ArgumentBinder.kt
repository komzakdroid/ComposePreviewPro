package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ParamInfo
import com.composepreviewpro.mock.MockContext
import com.composepreviewpro.mock.MockEngine
import com.composepreviewpro.mock.MockResult
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType

/**
 * Translates a [KFunction]'s parameter list into a `Map<KParameter, Any?>`
 * suitable for [kotlin.reflect.KCallable.callBy].
 *
 * Why `callBy` rather than `call`? Because `call` requires arguments in
 * declaration order and rejects defaulted-but-omitted parameters. `callBy`
 * takes a Map and respects @Default — so any param the mocker fails on but
 * which has a default value can be silently omitted. This widens the set of
 * composables we can render without explicit support.
 */
class ArgumentBinder(
    private val classLoader: ClassLoader,
    /** User overrides keyed by parameter name; checked before auto-mock. */
    private val overrides: Map<String, String> = emptyMap(),
    /**
     * Optional AI string-mocker. The renderer passes
     * [AiMockClient.generateString] here when the request has
     * useAiMocks=true AND the env var is set.
     */
    private val aiStringMocker: ((String, String, String) -> String?)? = null,
) {
    sealed interface BindResult {
        data class Ready(
            val args: Map<KParameter, Any?>,
            val summary: List<ParamInfo>,
        ) : BindResult
        data class Failed(val parameter: KParameter, val reason: String) : BindResult
    }

    fun bind(fn: KFunction<*>): BindResult {
        val args = mutableMapOf<KParameter, Any?>()
        val summary = mutableListOf<ParamInfo>()
        for (param in fn.parameters) {
            if (param.kind != KParameter.Kind.VALUE) continue
            val name = param.name

            // 1. Try user override first.
            if (name != null && name in overrides) {
                val parsed = parseOverride(overrides[name]!!, param.type)
                if (parsed != null) {
                    args[param] = parsed
                    summary += ParamInfo(
                        name = name,
                        typeName = typeName(param.type),
                        currentValue = overrides[name]!!,
                        editable = isEditableType(param.type),
                    )
                    continue
                }
                // override existed but failed to parse — fall through to mock
            }

            // 2. Auto-mock (AI-augmented if configured).
            val ctx = MockContext(
                paramName = name,
                classLoader = classLoader,
                aiStringMocker = aiStringMocker,
            )
            when (val res = MockEngine.mock(param.type, ctx)) {
                is MockResult.Success -> {
                    args[param] = res.value
                    if (name != null) summary += ParamInfo(
                        name = name,
                        typeName = typeName(param.type),
                        currentValue = formatForDisplay(res.value),
                        editable = isEditableType(param.type),
                    )
                }
                is MockResult.Unsupported -> {
                    // The MockEngine has no compose-runtime dep so it
                    // can't fabricate `@Composable` lambdas or build
                    // values for the standard Compose UI types. The
                    // renderer module DOES have those deps — try our
                    // own fallbacks in order, only giving up if none
                    // of them produce a value AND the parameter has
                    // no default.

                    // 1. @Composable lambda slot → no-op ComposableLambda.
                    val noOp = ComposableLambdaSynth.tryNoOp(param.type)
                    if (noOp != null) {
                        args[param] = noOp
                        if (name != null) summary += ParamInfo(
                            name = name,
                            typeName = typeName(param.type),
                            currentValue = "{ /* no-op @Composable */ }",
                            editable = false,
                        )
                        continue
                    }

                    // 2. Compose-standard type with a known default
                    // (ImageVector, Painter, Color, Brush, Shape,
                    // TextStyle, Dp, PaddingValues, *Defaults, …) plus
                    // a reflective fallback for third-party types that
                    // follow `Companion.Default` / `XxxDefaults.foo()`
                    // conventions.
                    val composeDefault = ComposeTypeMocks.tryDefault(param.type)
                    if (composeDefault != null) {
                        args[param] = composeDefault
                        if (name != null) summary += ParamInfo(
                            name = name,
                            typeName = typeName(param.type),
                            currentValue = formatForDisplay(composeDefault),
                            editable = false,
                        )
                        continue
                    }

                    // 3. Advanced runtime types — State<T>, Flow<T>,
                    // SnapshotStateList, Lazy<T>, Pair/Triple,
                    // ViewModel subclasses. Recursion goes back through
                    // mockTypeRecursively so the inner T benefits from
                    // every layer above.
                    val advanced = AdvancedTypeMocks.tryDefault(
                        param.type,
                        classLoader,
                    ) { innerType -> mockTypeRecursively(innerType) }
                    if (advanced != null) {
                        args[param] = advanced
                        if (name != null) summary += ParamInfo(
                            name = name,
                            typeName = typeName(param.type),
                            currentValue = formatForDisplay(advanced),
                            editable = false,
                        )
                        continue
                    }

                    // 4. Universal layer — stdlib types (java.time,
                    // java.util, java.math, java.io, java.net, kotlin
                    // .time), coroutines containers (Job, Deferred,
                    // Channel, CoroutineScope), and a Mockito-based
                    // last resort that mocks any remaining class with
                    // sane default-answer behaviour. This is the
                    // "make the preview render no matter what" net.
                    val universal = UniversalTypeMocks.tryDefault(param.type) { innerType ->
                        mockTypeRecursively(innerType)
                    }
                    if (universal != null) {
                        args[param] = universal
                        if (name != null) summary += ParamInfo(
                            name = name,
                            typeName = typeName(param.type),
                            currentValue = formatForDisplay(universal),
                            editable = false,
                        )
                        continue
                    }

                    if (param.isOptional) continue
                    return BindResult.Failed(param, res.reason)
                }
            }
        }
        return BindResult.Ready(args, summary)
    }

    /**
     * Run a type through every layer of the cascade — MockEngine →
     * ComposableLambdaSynth → ComposeTypeMocks → AdvancedTypeMocks
     * (which itself may recurse here via its callback). Used by
     * AdvancedTypeMocks to fill inner generic parameters of
     * `State<T>`, `Flow<T>`, `Pair<A, B>`, ViewModel constructor
     * arguments, and so on.
     *
     * Returns `null` when no layer could produce a value — the caller
     * decides whether to substitute `null`, an unsupported sentinel,
     * or to give up.
     */
    private fun mockTypeRecursively(type: KType): Any? {
        // 1. Pure-Kotlin base layer (primitives, enums, collections,
        //    sealed hierarchies, data classes).
        when (val res = MockEngine.mock(type, MockContext(classLoader = classLoader))) {
            is MockResult.Success -> return res.value
            is MockResult.Unsupported -> Unit  // fall through
        }
        // 2. @Composable lambda → no-op ComposableLambda.
        ComposableLambdaSynth.tryNoOp(type)?.let { return it }
        // 3. Compose UI standard types (Color, Dp, ImageVector, …).
        ComposeTypeMocks.tryDefault(type)?.let { return it }
        // 4. State/Flow/Lazy/ViewModel — recurses through me again.
        AdvancedTypeMocks.tryDefault(type, classLoader) { mockTypeRecursively(it) }?.let { return it }
        // 5. Universal stdlib + Mockito fallback.
        UniversalTypeMocks.tryDefault(type) { mockTypeRecursively(it) }?.let { return it }
        return null
    }

    private fun parseOverride(raw: String, type: KType): Any? {
        if (type.isMarkedNullable && raw.isBlank()) return null
        return when (val cls = type.classifier as? KClass<*>) {
            String::class -> raw
            Int::class -> raw.trim().toIntOrNull()
            Long::class -> raw.trim().toLongOrNull()
            Float::class -> raw.trim().toFloatOrNull()
            Double::class -> raw.trim().toDoubleOrNull()
            Boolean::class -> raw.trim().toBooleanStrictOrNull()
            Char::class -> raw.firstOrNull()
            Byte::class -> raw.trim().toByteOrNull()
            Short::class -> raw.trim().toShortOrNull()
            else -> {
                // Enums: parse by constant name.
                if (cls != null && cls.java.isEnum) {
                    @Suppress("UNCHECKED_CAST")
                    val enumClass = cls.java as Class<Enum<*>>
                    try {
                        @Suppress("UNCHECKED_CAST")
                        java.lang.Enum.valueOf(enumClass as Class<out Enum<*>>, raw.trim()) as Any
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                } else null
            }
        }
    }

    private fun isEditableType(type: KType): Boolean {
        val cls = type.classifier as? KClass<*> ?: return false
        if (cls.java.isEnum) return true
        return cls in EDITABLE_PRIMITIVES
    }

    private fun typeName(type: KType): String {
        val cls = type.classifier as? KClass<*>
        val base = cls?.simpleName ?: type.toString()
        return if (type.isMarkedNullable) "$base?" else base
    }

    private fun formatForDisplay(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Boolean, is Number, is Char -> value.toString()
        else -> {
            // Truncate complex toStrings so the panel stays compact.
            val s = value.toString()
            if (s.length > 80) s.substring(0, 77) + "…" else s
        }
    }

    private companion object {
        val EDITABLE_PRIMITIVES: Set<KClass<*>> = setOf(
            String::class, Int::class, Long::class, Float::class, Double::class,
            Boolean::class, Char::class, Byte::class, Short::class,
        )
    }
}
