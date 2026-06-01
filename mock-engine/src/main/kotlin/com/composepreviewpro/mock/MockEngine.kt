package com.composepreviewpro.mock

import androidx.compose.ui.Modifier
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.full.starProjectedType

/**
 * Reflection-based auto-mocker.
 *
 * Type coverage:
 *   • Nullable T?            → null
 *   • String, Int, Long, Float, Double, Boolean, Char, Byte, Short
 *   • Modifier               → Modifier (companion)
 *   • Function0..N returning Unit → no-op Proxy
 *   • Enum                   → first declared constant
 *   • data class             → primary constructor with each parameter mocked recursively
 *   • List, Collection       → 3 mocked elements
 *   • Map                    → 3 mocked entries
 *   • Set                    → 3 mocked elements
 *
 * Recursion guard: [MockContext.depth] is bumped at every nested mock
 * call. Depth > 6 returns Unsupported to prevent infinite recursion on
 * self-referential data classes (`data class Node(val next: Node?)`)
 * — the nullable variant of which terminates naturally at depth 0.
 *
 * Out-of-scope (returns Unsupported):
 *   • Functions with non-Unit return types
 *   • @Composable lambdas (need Composer + group keys)
 *   • Sealed types, value classes, abstract classes without a sole impl
 *   • Flow, StateFlow, MutableState (planned)
 */
object MockEngine {

    /**
     * Package prefixes whose `value class` types are NOT mocked by
     * [valueClassMockOrNull]. See its KDoc for why — short version:
     * framework value classes pack bit fields in ways that random
     * primitives violate, leading to crashes deep inside Skia / Compose
     * runtime. We let the renderer's specialised handlers
     * (`ComposeTypeMocks`, `UniversalTypeMocks`) build correct instances.
     */
    private val FRAMEWORK_VALUE_CLASS_PREFIXES = listOf(
        "androidx.",
        "kotlin.time.",
        "kotlin.ranges.",
        "kotlin.UInt",
        "kotlin.ULong",
        "kotlin.UShort",
        "kotlin.UByte",
    )

    fun mock(type: KType, context: MockContext = MockContext()): MockResult {
        // 1. Nullable shortcut — null is always a valid value for T?.
        //    We prefer it over fabricating a value because user code is
        //    almost always written defensively against null.
        if (type.isMarkedNullable) return MockResult.Success(null)

        // 2. Reject @Composable lambdas explicitly with a clear message —
        //    they require Composer wiring that the runtime alone provides.
        if (type.isComposableFunction()) {
            return MockResult.Unsupported(
                type = type,
                reason = "@Composable lambda parameters are not supported in MVP. " +
                    "They need a Composer + group key from the runtime."
            )
        }

        // Wrap the `classifier as? KClass<*>` cast AND every subsequent
        // KClass property read in try/catch — pathological user types whose
        // companion-object or static-init blocks throw at first touch can
        // bubble out of `type.classifier` and abort the entire render. We
        // treat such throwers as Unsupported with a clear message rather
        // than crashing the renderer subprocess.
        val classifier = try {
            type.classifier as? KClass<*>
        } catch (t: Throwable) {
            return MockResult.Unsupported(
                type = type,
                reason = "Type classifier inspection threw: ${t.javaClass.simpleName}: ${t.message}",
            )
        } ?: return MockResult.Unsupported(
            type = type,
            reason = "Type has no KClass classifier (likely a type parameter)."
        )

        // 3. Primitive & String fast path (AI-augmented if context has it).
        primitiveOrNull(classifier, context)?.let {
            return MockResult.Success(it)
        }

        // 4. Compose Modifier — companion object is a valid empty modifier.
        if (classifier == Modifier::class) {
            return MockResult.Success(Modifier)
        }

        // 5. Function types: Function0<Unit>, Function1<*, Unit>, ...
        functionMockOrNull(type, classifier, context)?.let { return it }

        // 6. Recursion guard before the structural fallbacks.
        if (context.depth > 6) {
            return MockResult.Unsupported(
                type = type,
                reason = "Recursion depth ${context.depth} exceeded — likely a cyclic data class.",
            )
        }

        // 7. Collections (List, Set, Map, Iterable, Collection).
        collectionMockOrNull(type, classifier, context)?.let { return it }

        // 8. Enums.
        enumMockOrNull(classifier)?.let { return it }

        // 9. Sealed classes / interfaces — pick the simplest subclass.
        sealedMockOrNull(classifier, context)?.let { return it }

        // 10. Data classes.
        dataClassMockOrNull(classifier, context)?.let { return it }

        // 11. Inline value classes (`@JvmInline value class Foo(val x: Int)`).
        //     Reflection still sees the wrapper class; at runtime its values
        //     are erased to the underlying primary-constructor type. We mock
        //     the underlying value, then invoke the value class's primary
        //     constructor to wrap it. This handles the very common modern-Kotlin
        //     pattern of `value class UserId(val id: Long)` parameters.
        valueClassMockOrNull(classifier, context)?.let { return it }

        // Last resort before giving up: the renderer's richer layers (Compose
        // UI types, stdlib, Mockito). Lets a data-class field of a third-party
        // type (kotlinx.datetime.Instant, etc.) still be fabricated so the
        // whole data class builds with non-null fields.
        context.externalMocker?.invoke(type)?.let { return MockResult.Success(it) }

        return MockResult.Unsupported(
            type = type,
            reason = "No mocker registered for ${classifier.qualifiedName ?: classifier}",
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  Primitive heuristics
    // ─────────────────────────────────────────────────────────────

    private fun primitiveOrNull(cls: KClass<*>, paramName: String?): Any? = when (cls) {
        String::class  -> smartString(paramName)
        Int::class     -> 42
        Long::class    -> 42L
        Float::class   -> 3.14f
        Double::class  -> 3.14
        Boolean::class -> true
        Char::class    -> 'A'
        Byte::class    -> 0.toByte()
        Short::class   -> 0.toShort()
        else -> null
    }

    /**
     * Same as [primitiveOrNull] but tries the AI mock provider first
     * for `String` parameters before falling back to the regex
     * heuristics. Other primitives stay deterministic — calling Claude
     * for "what's a good Int default" would be silly.
     */
    private fun primitiveOrNull(cls: KClass<*>, ctx: MockContext): Any? {
        if (cls == String::class && ctx.paramName != null) {
            ctx.aiStringMocker?.invoke(ctx.paramName, "String", ctx.callerTypeName)?.let { return it }
        }
        return primitiveOrNull(cls, ctx.paramName)
    }

    /**
     * Parameter-name aware string defaults. The patterns are intentionally
     * loose (case-insensitive substring) so `userEmail` matches `email`.
     * Order matters: first match wins.
     */
    private val stringHints: List<Pair<Regex, String>> = listOf(
        Regex("(?i)email")               to "user@example.com",
        Regex("(?i)url|link|href|avatar|image|photo") to "https://example.com",
        Regex("(?i)phone|tel")           to "+1 555-0123",
        Regex("(?i)first.?name")         to "Jane",
        Regex("(?i)last.?name|surname")  to "Doe",
        Regex("(?i)name|author|user")    to "Jane Doe",
        Regex("(?i)title|header|label")  to "Sample Title",
        Regex("(?i)desc(ription)?|summary") to "Sample description text",
        Regex("(?i)body|content|message|text") to "Lorem ipsum dolor sit amet.",
        Regex("(?i)password|secret|token") to "********",
        Regex("(?i)date|time(stamp)?")   to "2026-05-26",
        Regex("(?i)id|uuid")             to "00000000-0000-0000-0000-000000000000",
        Regex("(?i)currency")            to "USD",
        Regex("(?i)city|country|address|location") to "San Francisco",
        Regex("(?i)tag|category|status") to "Featured",
    )

    private fun smartString(paramName: String?): String {
        if (paramName == null) return "Sample"
        for ((regex, value) in stringHints) {
            if (regex.containsMatchIn(paramName)) return value
        }
        // Fall-through: bracket the param name so the user immediately sees
        // which field on screen corresponds to which parameter.
        return "<$paramName>"
    }

    // ─────────────────────────────────────────────────────────────
    //  Function type mocking via JDK Proxy
    // ─────────────────────────────────────────────────────────────

    private fun functionMockOrNull(
        type: KType,
        classifier: KClass<*>,
        context: MockContext,
    ): MockResult? {
        val fqn = classifier.qualifiedName ?: return null
        // Both names exist in the wild — Kotlin's public alias and the JVM
        // bytecode form. Accept either.
        val isFunction =
            fqn.startsWith("kotlin.Function") ||
            fqn.startsWith("kotlin.jvm.functions.Function")
        if (!isFunction) return null

        // Reject non-Unit return types — without recursing into the return
        // type we cannot produce a valid value.
        val returnType = type.arguments.lastOrNull()?.type
        val returnClass = returnType?.classifier as? KClass<*>
        if (returnClass != Unit::class) {
            return MockResult.Unsupported(
                type = type,
                reason = "Only () -> Unit style lambdas are mockable in MVP. " +
                    "Return type was: ${returnClass?.qualifiedName ?: "unknown"}",
            )
        }

        // Build a Proxy implementing the function interface. The handler
        // ignores all arguments and returns Unit — the only legal return for
        // a Unit-typed function in Kotlin.
        return try {
            // CRITICAL: handle Object methods (toString / hashCode /
            // equals) explicitly — otherwise the Proxy returns `Unit`
            // for those too, and any caller that does e.g.
            // `proxy.toString()` would attempt `Unit cast to String`
            // and crash.
            val proxy = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(classifier.java),
            ) { receiver, method, methodArgs ->
                when (method.name) {
                    "toString" -> "<mocked ${classifier.simpleName ?: "function"}>"
                    "hashCode" -> System.identityHashCode(receiver)
                    "equals" -> methodArgs?.getOrNull(0) === receiver
                    else -> Unit
                }
            }
            MockResult.Success(proxy)
        } catch (e: Throwable) {
            MockResult.Unsupported(
                type = type,
                reason = "Proxy construction failed: ${e.message}",
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * A `@Composable` annotation on a function type marks it as a Compose
     * slot/content lambda. These need special runtime support (Composer,
     * group keys) that a plain Proxy cannot provide.
     *
     * We match by qualified name so this module need not compile-time
     * depend on androidx.compose.runtime's Composable class symbol — keeps
     * the dependency surface minimal.
     */
    private fun KType.isComposableFunction(): Boolean =
        annotations.any { it.annotationClass.qualifiedName == "androidx.compose.runtime.Composable" }

    // ─────────────────────────────────────────────────────────────
    //  Collection mocking
    // ─────────────────────────────────────────────────────────────

    private fun collectionMockOrNull(
        type: KType,
        classifier: KClass<*>,
        context: MockContext,
    ): MockResult? {
        val fqn = classifier.qualifiedName ?: return null
        // Handle the most common stdlib container types. We match by
        // qualified name rather than `is` checks so that subtypes that
        // *implement* List etc. don't get mishandled.
        return when {
            fqn == "kotlin.collections.List" ||
            fqn == "kotlin.collections.Collection" ||
            fqn == "kotlin.collections.Iterable" ||
            fqn == "java.util.List" -> {
                val elemType = type.arguments.firstOrNull()?.type
                    ?: return MockResult.Unsupported(type, "$fqn has no type argument")
                buildItems(elemType, context).fold(
                    success = { MockResult.Success(it) },
                    failure = { reason -> MockResult.Unsupported(type, reason) },
                )
            }
            fqn == "kotlin.collections.Set" -> {
                val elemType = type.arguments.firstOrNull()?.type
                    ?: return MockResult.Unsupported(type, "Set has no type argument")
                buildItems(elemType, context).fold(
                    success = { MockResult.Success(it.toSet()) },
                    failure = { reason -> MockResult.Unsupported(type, reason) },
                )
            }
            fqn == "kotlin.collections.Map" -> {
                val keyType = type.arguments.getOrNull(0)?.type
                    ?: return MockResult.Unsupported(type, "Map has no key type argument")
                val valueType = type.arguments.getOrNull(1)?.type
                    ?: return MockResult.Unsupported(type, "Map has no value type argument")
                val keys = buildItems(keyType, context, n = 3)
                val values = buildItems(valueType, context, n = 3)
                when {
                    keys is BuildResult.Failure -> MockResult.Unsupported(type, keys.reason)
                    values is BuildResult.Failure -> MockResult.Unsupported(type, values.reason)
                    keys is BuildResult.Success && values is BuildResult.Success ->
                        MockResult.Success(keys.items.zip(values.items).toMap())
                    else -> MockResult.Unsupported(type, "internal: unreachable Map mock state")
                }
            }
            else -> null
        }
    }

    private sealed interface BuildResult {
        data class Success(val items: List<Any?>) : BuildResult
        data class Failure(val reason: String) : BuildResult

        fun <T> fold(success: (List<Any?>) -> T, failure: (String) -> T): T = when (this) {
            is Success -> success(items)
            is Failure -> failure(reason)
        }
    }

    private fun buildItems(elemType: KType, context: MockContext, n: Int = 3): BuildResult {
        val out = mutableListOf<Any?>()
        for (i in 0 until n) {
            val childCtx = context.child("item$i")
            when (val sub = mock(elemType, childCtx)) {
                is MockResult.Success -> out += sub.value
                is MockResult.Unsupported -> return BuildResult.Failure(
                    "element type $elemType not mockable: ${sub.reason}"
                )
            }
        }
        return BuildResult.Success(out)
    }

    // ─────────────────────────────────────────────────────────────
    //  Enum mocking
    // ─────────────────────────────────────────────────────────────

    private fun enumMockOrNull(classifier: KClass<*>): MockResult? {
        if (!classifier.java.isEnum) return null
        val constants = classifier.java.enumConstants
        if (constants.isNullOrEmpty()) {
            return MockResult.Unsupported(
                type = classifier.starProjectedType,
                reason = "enum ${classifier.simpleName} has no constants",
            )
        }
        // Pick the first declared constant — deterministic, visible-by-
        // default behaviour. Tests and screenshots compare reliably.
        return MockResult.Success(constants[0])
    }

    // ─────────────────────────────────────────────────────────────
    //  Sealed hierarchy mocking
    // ─────────────────────────────────────────────────────────────

    /**
     * For a sealed class/interface, pick the cheapest valid instance
     * we can produce:
     *
     *   1. Any subclass that's a Kotlin `object` (singleton — zero cost).
     *   2. Any non-sealed subclass that we can mock as a data class.
     *   3. Recursively dive into nested sealed subclasses.
     *
     * This handles the common "result wrapper" pattern (`Result.Success`,
     * `Result.Failure`, `Result.Loading`) without forcing the user to
     * supply an override — Loading/empty is usually the right preview.
     */
    private fun sealedMockOrNull(classifier: KClass<*>, context: MockContext): MockResult? {
        if (!classifier.isSealed) return null
        val subs = classifier.sealedSubclasses
        if (subs.isEmpty()) return null

        // (1) Prefer objects — no construction overhead.
        for (sub in subs) {
            sub.objectInstance?.let { return MockResult.Success(it) }
        }
        // (2) Then non-sealed concrete subclasses.
        for (sub in subs) {
            if (sub.isSealed) continue
            val res = mock(sub.starProjectedType, context.child(null))
            if (res is MockResult.Success) return res
        }
        // (3) Recurse into nested sealed children.
        for (sub in subs) {
            if (!sub.isSealed) continue
            val res = mock(sub.starProjectedType, context.child(null))
            if (res is MockResult.Success) return res
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────
    //  Data class mocking
    // ─────────────────────────────────────────────────────────────

    private fun dataClassMockOrNull(classifier: KClass<*>, context: MockContext): MockResult? {
        if (!classifier.isData) return null
        val ctor = classifier.primaryConstructor
            ?: return MockResult.Unsupported(
                type = classifier.starProjectedType,
                reason = "data class ${classifier.simpleName} has no primary constructor",
            )

        // Real-world data classes routinely have `internal` primary
        // constructors (NiA's `UserNewsResource`, many domain models). Without
        // this, callBy throws IllegalAccessException → the class falls back to a
        // bare mock with null fields → NPE inside the composable.
        ctor.isAccessible = true

        val callerName = classifier.simpleName ?: ""
        val args = mutableMapOf<KParameter, Any?>()
        for (param in ctor.parameters) {
            if (param.kind != KParameter.Kind.VALUE) continue
            val childCtx = context.childWithCaller(param.name, callerName)
            when (val sub = mock(param.type, childCtx)) {
                is MockResult.Success -> args[param] = sub.value
                is MockResult.Unsupported -> {
                    if (param.isOptional) continue
                    return MockResult.Unsupported(
                        type = classifier.starProjectedType,
                        reason = "cannot mock ${classifier.simpleName}.${param.name}: ${sub.reason}",
                    )
                }
            }
        }

        return try {
            MockResult.Success(ctor.callBy(args))
        } catch (t: Throwable) {
            MockResult.Unsupported(
                type = classifier.starProjectedType,
                reason = "${classifier.simpleName} constructor threw: ${t.message}",
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Inline value class mocking — `@JvmInline value class Foo(val x: T)`
    // ─────────────────────────────────────────────────────────────

    /**
     * Mock a `value class` by recursively mocking its underlying parameter
     * type and invoking the primary constructor. Returns `null` (so the
     * dispatcher falls through to the catch-all Unsupported) if the class
     * is not a value class or has no usable primary constructor.
     *
     * Inline classes show up everywhere in modern Kotlin (`UserId`,
     * `EmailAddress`, `Duration` wrappers, ULID, etc.) and the absence of
     * this branch was the single most-common reason composables with
     * type-safe ID parameters fell through to "No mocker registered".
     *
     * **Framework value classes are intentionally skipped** so the
     * specialised handlers downstream (`ComposeTypeMocks`,
     * `AdvancedTypeMocks`, kotlin.time / kotlin.ranges defaults inside
     * `UniversalTypeMocks`) get a chance to produce a semantically
     * meaningful instance. `androidx.compose.ui.graphics.Color`, for
     * example, is a `value class Color(val value: ULong)` whose
     * underlying ULong packs RGBA + color-space index — passing `ULong(42)`
     * to its constructor produces a Color whose colorSpace lookup throws
     * `ArrayIndexOutOfBoundsException` deep inside the Skia path. Same
     * shape of bug exists for `kotlin.time.Duration`, `Dp`, `Sp`, `Px`,
     * `Offset`, `Size`, `IntOffset`, `TextUnit`, etc. We exclude those
     * package prefixes entirely; user value classes (which never live
     * under `androidx.*` / `kotlin.*` / `kotlinx.*`) still get the
     * recursive constructor treatment.
     */
    private fun valueClassMockOrNull(classifier: KClass<*>, context: MockContext): MockResult? {
        if (!classifier.isValue) return null
        val qn = classifier.qualifiedName
        if (qn != null && FRAMEWORK_VALUE_CLASS_PREFIXES.any { qn.startsWith(it) }) {
            return null
        }
        val ctor = classifier.primaryConstructor ?: return null
        ctor.isAccessible = true
        val params = ctor.parameters.filter { it.kind == KParameter.Kind.VALUE }
        // value classes have exactly one underlying property, but treat the
        // general case so future N-ary value records (Kotlin proposal) keep
        // working.
        val args = mutableMapOf<KParameter, Any?>()
        for (param in params) {
            val childCtx = context.childWithCaller(param.name, classifier.simpleName ?: "")
            when (val sub = mock(param.type, childCtx)) {
                is MockResult.Success -> args[param] = sub.value
                is MockResult.Unsupported -> {
                    if (param.isOptional) continue
                    return MockResult.Unsupported(
                        type = classifier.starProjectedType,
                        reason = "cannot mock value class ${classifier.simpleName}.${param.name}: ${sub.reason}",
                    )
                }
            }
        }
        return try {
            MockResult.Success(ctor.callBy(args))
        } catch (t: Throwable) {
            MockResult.Unsupported(
                type = classifier.starProjectedType,
                reason = "value class ${classifier.simpleName} constructor threw: ${t.message}",
            )
        }
    }
}
