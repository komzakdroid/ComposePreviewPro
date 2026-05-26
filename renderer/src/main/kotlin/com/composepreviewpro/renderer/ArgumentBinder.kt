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
                    if (param.isOptional) continue
                    return BindResult.Failed(param, res.reason)
                }
            }
        }
        return BindResult.Ready(args, summary)
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
