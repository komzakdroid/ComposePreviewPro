package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ParamInfo
import org.mockito.Answers
import org.mockito.Mockito
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Invokes a `@Composable` purely through `java.lang.reflect.Method`, with NO
 * kotlin-reflect dependency.
 *
 * Why this exists
 * ----------------
 * `@Composable` functions that take an inline **value class** parameter (the
 * single most common being `androidx.compose.ui.graphics.Color`) are compiled
 * to a JVM method with:
 *   • a **mangled name** — `AccentChip-RPmYEkk` (the `-<hash>` suffix), and
 *   • the value-class parameter **unboxed** to its underlying primitive
 *     (`Color` → `long`).
 *
 * kotlin-reflect cannot model these on a top-level file facade: `KClass
 * .declaredFunctions` silently drops them, and `Method.kotlinFunction` returns
 * a *poisoned* `KFunction` whose `.name`, `.parameters`, and `.javaMethod` all
 * throw `KotlinReflectionInternalError: Inconsistent number of parameters …`.
 * Every binding path the renderer normally uses ([ArgumentBinder],
 * [buildComposableJvmArgs]) goes through those members, so value-class
 * composables are unrenderable via the Kotlin path — which is exactly the
 * `accent: Color` case users hit.
 *
 * This invoker sidesteps kotlin-reflect entirely. It reconstructs the Compose
 * calling convention from the raw JVM signature:
 *
 * ```
 * [ N source slots (value classes already unboxed) ] [ Composer ] [ $changed int… ] [ $default int…? ]
 * ```
 *
 * The **Composer slot** is found by type (`androidx.compose.runtime.Composer`),
 * which splits source slots (everything before it) from the trailing synthetic
 * ints. `$changed` int count follows Compose's packing (≤10 params per int);
 * whatever ints remain are `$default`. We set **every** `$default` bit, so
 * optional parameters fall back to their author-declared default (always
 * visually correct) and required parameters use a best-effort value mocked by
 * JVM type. The result: the composable renders instead of crashing, even
 * though no usable `KFunction` exists.
 */
internal object JavaComposableInvoker {

    private const val COMPOSER_FQN = "androidx.compose.runtime.Composer"

    /** True when [method] is a value-class-mangled name (`foo-<hash>`). */
    fun isMangled(method: Method): Boolean = method.name.contains('-')

    /**
     * Build the full positional JVM argument array for [method], including the
     * Composer, `$changed`, and `$default` synthetic slots.
     */
    fun buildJvmArgs(
        method: Method,
        classLoader: ClassLoader,
        composer: Any?,
    ): Array<Any?> {
        val types = method.parameterTypes
        val total = types.size

        // Source-param count = index of the Composer slot. If somehow absent
        // (non-Compose method), treat everything but the last slot as source.
        val composerIdx = types.indexOfFirst { it.name == COMPOSER_FQN }
            .let { if (it >= 0) it else (total - 1).coerceAtLeast(0) }
        val n = composerIdx

        val args = arrayOfNulls<Any?>(total)
        for (i in 0 until n) {
            args[i] = mockForJvmType(types[i], classLoader)
        }
        if (composerIdx in 0 until total) args[composerIdx] = composer

        val trailing = total - n - 1
        // Compose packs ≤10 parameters per $changed int; at least one int.
        val changedIntCount = if (n == 0) 1 else (n - 1) / 10 + 1
        val defaultIntCount = (trailing - changedIntCount).coerceAtLeast(0)

        var idx = n + 1
        // $changed ints → 0 ("uncertain / first composition" for every param).
        var changedLeft = changedIntCount
        while (changedLeft > 0 && idx < total) {
            args[idx++] = 0
            changedLeft--
        }
        // $default ints → set the bit of EVERY source param. Optionals then use
        // their author default; required params ignore the bit (the compiler
        // emits no default-expr for them) and use the slot value we mocked.
        for (d in 0 until defaultIntCount) {
            if (idx >= total) break
            var mask = 0
            for (bit in 0 until 32) {
                val paramIndex = d * 32 + bit
                if (paramIndex < n) mask = mask or (1 shl bit)
            }
            args[idx++] = mask
        }
        // Safety net — any remaining trailing int slot gets 0.
        while (idx < total) args[idx++] = 0

        return args
    }

    /** Lightweight parameter summary for the IDE panel (read-only rows). */
    fun summarize(method: Method): List<ParamInfo> {
        val types = method.parameterTypes
        val composerIdx = types.indexOfFirst { it.name == COMPOSER_FQN }
            .let { if (it >= 0) it else types.size }
        return (0 until composerIdx).map { i ->
            ParamInfo(
                name = "arg$i",
                typeName = types[i].simpleName ?: types[i].name,
                currentValue = "(auto)",
                editable = false,
            )
        }
    }

    /**
     * Best-effort value for a single JVM parameter type. Covers the shapes a
     * source-level Compose parameter compiles to: unboxed value classes &
     * primitives, String/CharSequence, enums, Kotlin function types
     * (callbacks AND `@Composable` lambdas), and arbitrary objects (Mockito).
     */
    private fun mockForJvmType(type: Class<*>, classLoader: ClassLoader): Any? = when {
        type == java.lang.String::class.java || type == CharSequence::class.java -> "Preview"
        type.isPrimitive -> when (type) {
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L                 // unboxed Color/Money/etc. → 0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            java.lang.Boolean.TYPE -> false
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> 0
        }
        type.isEnum -> type.enumConstants?.firstOrNull()
        // Arrays — including the `String[]` a `vararg` compiles to, which is a
        // REQUIRED non-null slot (the caller always passes an array, even
        // empty; it is NOT defaulted via the $default bitmask). Return an
        // empty array of the component type.
        type.isArray -> java.lang.reflect.Array.newInstance(type.componentType, 0)
        // Kotlin function interfaces (Function0..FunctionN) — callbacks and
        // @Composable content lambdas alike. A no-op proxy returns Unit, so
        // callbacks do nothing and composable slots emit nothing (no crash).
        type.name.startsWith("kotlin.jvm.functions.Function") -> noOpFunction(type)
        type == java.lang.Object::class.java -> Any()
        else -> tryMockObject(type)
    }

    private fun noOpFunction(functionType: Class<*>): Any =
        Proxy.newProxyInstance(functionType.classLoader, arrayOf(functionType)) { _, method, _ ->
            when (method.name) {
                "invoke" -> Unit
                "toString" -> "{}"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        }

    private fun tryMockObject(type: Class<*>): Any? = try {
        // Mockito's inline mock maker handles final classes (data classes,
        // sealed leaves) and interfaces; stubOnly keeps it memory-light.
        Mockito.mock(
            type,
            Mockito.withSettings()
                .defaultAnswer(Answers.RETURNS_DEFAULTS)
                .stubOnly(),
        )
    } catch (_: Throwable) {
        null
    }
}
