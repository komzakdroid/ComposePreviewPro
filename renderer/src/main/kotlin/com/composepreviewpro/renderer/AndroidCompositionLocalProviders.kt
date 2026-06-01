package com.composepreviewpro.renderer

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Reflectively discovers every Android-specific `CompositionLocal`
 * defined across the well-known Compose / lifecycle / activity /
 * savedstate / viewmodel accessor classes, and builds a list of
 * `ProvidedValue<*>` that satisfies them.
 *
 * Two reasons to do this reflectively rather than hard-coding each
 * Local:
 *
 *   1. The set of CompositionLocals an Android Compose composable can
 *      legally consume grows every minor version (e.g. ViewModel
 *      `BackPressedDispatcher` was added in activity-compose 1.6).
 *      Hand-rolling each one made every Compose library update a
 *      manual maintenance task.
 *
 *   2. Each Local's target type is itself either an `interface` or an
 *      abstract Android class — both of which Mockito 5 can mock by
 *      default. So we don't need bespoke logic per type: we lift the
 *      `T` out of the `ProvidableCompositionLocal<T>` generic
 *      signature and mock that class.
 *
 * `LocalContext` is the **one** Local we don't mock with Mockito —
 * the renderer's [AndroidContextStub] gives it an asset-aware Context
 * graph so `stringResource` and `painterResource` can read packaged
 * resources from the user's classpath. Every other Local gets a
 * Mockito mock returning sensible defaults; the composable doesn't
 * crash, and a Real Behaviour mock would only be necessary if the
 * preview actually exercised that path (rare in practice).
 */
internal object AndroidCompositionLocalProviders {

    /**
     * CompositionLocals that must be backed by a REAL instance of their target
     * type (constructed via its no-arg constructor), NOT a Mockito mock. These
     * are resource caches whose methods do real work — a mock returns null and
     * breaks painterResource (`resolveResourcePath` → null → NPE). Their own
     * default throws ("not present"), so we can't skip them either.
     */
    private val REAL_INSTANCE_LOCALS = setOf(
        "LocalResourceIdCache",
        "LocalImageVectorCache",
    )

    /** Accessor classes scanned for `getLocalXxx` static methods. */
    private val accessorClassNames = listOf(
        "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt",
        "androidx.compose.ui.platform.AndroidCompositionLocals_androidxKt",  // alt name in some builds
        "androidx.lifecycle.compose.LocalLifecycleOwnerKt",
        "androidx.savedstate.compose.LocalSavedStateRegistryOwnerKt",
        "androidx.activity.compose.BackHandlerKt",
        "androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwnerKt",
    )

    fun discoverAndProvide(
        classLoader: ClassLoader,
        contextStub: Any,
        overrides: Map<String, Any> = emptyMap(),
    ): List<ProvidedValue<*>> {
        val provideds = mutableListOf<ProvidedValue<*>>()

        for (className in accessorClassNames) {
            val klass = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                continue  // class isn't on the user's classpath — fine
            }
            for (method in klass.declaredMethods) {
                provideOne(method, classLoader, contextStub, overrides)?.let(provideds::add)
            }
        }
        return provideds
    }

    /**
     * Provide `androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner`,
     * which is a Kotlin `object` (not a top-level `val`) and therefore missed
     * by the generic `getLocal*` discovery in [discoverAndProvide].
     *
     * Called UNIVERSALLY (not Android-gated) by the render host, because
     * lifecycle-viewmodel-compose is a multiplatform artifact: a desktop/CMP
     * composable that calls `viewModel()` needs this owner just as much as an
     * Android one does.
     *
     * Its default value throws *"No ViewModelStoreOwner was provided via
     * LocalViewModelStoreOwner"* the instant any composable calls
     * `viewModel()` / `koinViewModel()` / `hiltViewModel()`. We bind a stub
     * owner whose `viewModelStore` is a **real** [androidx.lifecycle.ViewModelStore]:
     *
     *   • `viewModel()` previews of a default-constructible ViewModel now
     *     actually work — the store can cache the created instance.
     *   • DI-backed `koinViewModel()` / `hiltViewModel()` get past the owner
     *     lookup and then fail later at DI resolution with a specific message
     *     that [userFriendlyMessage] maps to actionable guidance, instead of a
     *     raw CompositionLocal panic at the very first frame.
     *
     * Returns null when lifecycle-viewmodel-compose is not on the classpath.
     */
    fun provideViewModelStoreOwner(classLoader: ClassLoader): ProvidedValue<*>? {
        return try {
            val localClass = classLoader.loadClass(
                "androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner",
            )
            val instance = localClass.getField("INSTANCE").get(null)
            val ownerType = classLoader.loadClass("androidx.lifecycle.ViewModelStoreOwner")
            val realStore = classLoader.loadClass("androidx.lifecycle.ViewModelStore")
                .getDeclaredConstructor().newInstance()
            val ownerAnswer = Answer<Any?> { invocation ->
                if (invocation.method.name == "getViewModelStore") realStore else null
            }
            val owner = Mockito.mock(
                ownerType,
                Mockito.withSettings().defaultAnswer(ownerAnswer).stubOnly(),
            )
            // `object LocalViewModelStoreOwner { infix fun provides(owner) }`
            // → JVM instance method `provides(ViewModelStoreOwner)`.
            val providesMethod = localClass.methods.firstOrNull {
                it.name == "provides" && it.parameterCount == 1
            } ?: return null
            providesMethod.invoke(instance, owner) as? ProvidedValue<*>
        } catch (_: Throwable) {
            null  // viewmodel-compose absent — nothing to provide.
        }
    }

    /**
     * Inspect a single `getLocalXxx` static method on an accessor class:
     *
     *   • Skip if the method isn't a zero-arg static getter.
     *   • Invoke to retrieve the `ProvidableCompositionLocal<*>` instance.
     *   • Determine the target type `T` from the method's generic
     *     return type (`ProvidableCompositionLocal<T>`).
     *   • Build a value for `T`: our Context stub for `LocalContext`,
     *     a Mockito mock for everything else.
     *   • Return `local provides value` as a `ProvidedValue<*>`.
     *
     * Returns `null` on any failure — we just skip that Local and
     * continue. Worst case the user sees a more specific
     * "LocalXxx not present" error than the original hard-coded panic.
     */
    private fun provideOne(
        method: Method,
        classLoader: ClassLoader,
        contextStub: Any,
        overrides: Map<String, Any>,
    ): ProvidedValue<*>? {
        if (method.parameterCount != 0) return null
        if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) return null
        if (!method.name.startsWith("getLocal")) return null
        method.isAccessible = true

        val local = try {
            method.invoke(null)
        } catch (_: Throwable) {
            return null
        } ?: return null
        if (local !is ProvidableCompositionLocal<*>) return null

        val localName = method.name.removePrefix("get")
        // Resolution order:
        //   1. Caller override — a precise stub (e.g. our Resources whose
        //      getString returns non-null placeholders).
        //   2. LocalContext fast-path — always our context stub.
        //   3. Resource caches — a REAL instance (a mock breaks painterResource).
        //   4. Mockito mock of the Local's declared target type.
        val value = overrides[localName]
            ?: when {
                localName == "LocalContext" -> contextStub
                localName in REAL_INSTANCE_LOCALS -> realInstanceForLocal(method, classLoader)
                else -> buildMockForLocal(method, classLoader)
            }
            ?: return null

        @Suppress("UNCHECKED_CAST")
        val typedLocal = local as ProvidableCompositionLocal<Any?>
        return try {
            typedLocal provides value
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extract the target type parameter of a `ProvidableCompositionLocal<T>`
     * (or `StaticProvidableCompositionLocal<T>` / `DynamicProvidableCompositionLocal<T>`)
     * from a getter's generic return type, then build a Mockito mock
     * of that type.
     *
     * Returns null when the type isn't introspectable (rare — Kotlin
     * always emits parameterised generic signatures for these) or when
     * Mockito refuses to mock the class (primitive types).
     */
    /**
     * Construct a REAL instance of the Local's target type via its no-arg
     * constructor (e.g. `ResourceIdCache()`, `ImageVectorCache()`), so resource
     * lookups actually work. Falls back to a Mockito mock if there's no usable
     * no-arg constructor.
     */
    private fun realInstanceForLocal(method: Method, classLoader: ClassLoader): Any? {
        val genericType = method.genericReturnType as? ParameterizedType ?: return null
        val targetType: Type = genericType.actualTypeArguments.firstOrNull() ?: return null
        val targetClass: Class<*> = rawClassOf(targetType, classLoader) ?: return null
        return try {
            targetClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        } catch (_: Throwable) {
            buildMockForLocal(method, classLoader)
        }
    }

    private fun buildMockForLocal(method: Method, classLoader: ClassLoader): Any? {
        val genericType = method.genericReturnType as? ParameterizedType ?: return null
        val targetType: Type = genericType.actualTypeArguments.firstOrNull() ?: return null
        val targetClass: Class<*> = rawClassOf(targetType, classLoader) ?: return null
        if (targetClass.isPrimitive) return null
        return try {
            Mockito.mock(
                targetClass,
                Mockito.withSettings()
                    .defaultAnswer(Mockito.RETURNS_DEFAULTS)
                    .stubOnly(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Unwrap a [Type] down to a raw `Class<*>` we can hand to Mockito.
     * Handles:
     *   • Plain Class<*>             — return as-is.
     *   • ParameterizedType<T<A>>   — recurse on raw type.
     *   • WildcardType (? extends T) — recurse on the upper bound.
     */
    private fun rawClassOf(type: Type, classLoader: ClassLoader): Class<*>? {
        return when (type) {
            is Class<*> -> type
            is ParameterizedType -> rawClassOf(type.rawType, classLoader)
            is java.lang.reflect.WildcardType ->
                type.upperBounds.firstOrNull()?.let { rawClassOf(it, classLoader) }
            else -> null
        }
    }
}
