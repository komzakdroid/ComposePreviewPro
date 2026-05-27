package com.composepreviewpro.renderer

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.primaryConstructor

/**
 * Advanced (composable-runtime aware) mock factories for types the
 * pure-Kotlin [com.composepreviewpro.mock.MockEngine] cannot reach.
 *
 * Where [ComposeTypeMocks] hand-rolls *static* defaults for Compose UI
 * value types (Color, Dp, ImageVector…), this object handles **state
 * containers** and **lifecycle holders** that wrap arbitrary user
 * types — `State<T>`, `Flow<T>`, `Lazy<T>`, `ViewModel`. Producing a
 * realistic mock requires (a) compose-runtime / coroutines on the
 * classpath (which the renderer has) and (b) recursively mocking the
 * inner type, which we expose as a callback.
 *
 * Coverage:
 *
 *   • [State] / [MutableState]              → mutableStateOf(inner mock)
 *   • [SnapshotStateList] / [SnapshotStateMap] → empty snapshot list/map
 *   • [Flow] / [StateFlow] / [SharedFlow]   → wrapped or empty
 *   • [Lazy]                                → lazyOf(inner mock)
 *   • [kotlin.Pair] / [kotlin.Triple]       → fabricate components
 *   • `androidx.lifecycle.ViewModel` subclass → no-arg ctor or constructor
 *                                              with recursively-mocked args
 *
 * Anything not handled here falls back through the regular Unsupported
 * path in [ArgumentBinder]. The user can still override via the panel.
 */
internal object AdvancedTypeMocks {

    /**
     * @param recursiveMock callback that mocks an inner [KType] using the
     *   full MockEngine + ComposeTypeMocks + AdvancedTypeMocks layer
     *   stack. Returns `null` when no layer could produce a value.
     */
    fun tryDefault(
        type: KType,
        classLoader: ClassLoader,
        recursiveMock: (KType) -> Any?,
    ): Any? {
        val classifier = type.classifier as? KClass<*> ?: return null
        val fqn = classifier.qualifiedName ?: return null

        val firstArg = type.arguments.firstOrNull()?.type
        val secondArg = type.arguments.getOrNull(1)?.type

        return when (fqn) {
            // ── Compose State containers ───────────────────────────
            "androidx.compose.runtime.State",
            "androidx.compose.runtime.MutableState" ->
                mutableStateOf<Any?>(firstArg?.let(recursiveMock))

            "androidx.compose.runtime.snapshots.SnapshotStateList" ->
                emptyList<Any?>().toMutableStateList()

            "androidx.compose.runtime.snapshots.SnapshotStateMap" ->
                SnapshotStateMap<Any?, Any?>()

            // ── Coroutines flows ───────────────────────────────────
            "kotlinx.coroutines.flow.Flow" -> {
                val inner = firstArg?.let(recursiveMock)
                if (inner != null) flowOf(inner) else emptyFlow<Any?>()
            }

            "kotlinx.coroutines.flow.StateFlow",
            "kotlinx.coroutines.flow.MutableStateFlow" -> {
                // StateFlow REQUIRES a non-null initial value (typed).
                // If recursion can't produce one, swap in a stub.
                val inner = firstArg?.let(recursiveMock) ?: Any()
                MutableStateFlow(inner)
            }

            "kotlinx.coroutines.flow.SharedFlow",
            "kotlinx.coroutines.flow.MutableSharedFlow" ->
                MutableSharedFlow<Any?>()

            // ── Kotlin stdlib ─────────────────────────────────────
            "kotlin.Lazy" -> {
                val inner = firstArg?.let(recursiveMock)
                lazy { inner }
            }

            "kotlin.Pair" -> {
                val a = firstArg?.let(recursiveMock)
                val b = secondArg?.let(recursiveMock)
                Pair(a, b)
            }

            "kotlin.Triple" -> {
                val a = firstArg?.let(recursiveMock)
                val b = secondArg?.let(recursiveMock)
                val c = type.arguments.getOrNull(2)?.type?.let(recursiveMock)
                Triple(a, b, c)
            }

            else -> tryViewModel(classifier, classLoader, recursiveMock)
        }
    }

    /**
     * Detect `androidx.lifecycle.ViewModel` subclasses and synthesise
     * an instance:
     *
     *   1. Public no-arg constructor — most preview-friendly ViewModels.
     *   2. Primary constructor where every parameter can itself be
     *      mocked recursively. Covers Hilt-friendly classes that take
     *      a `SavedStateHandle`, a repository, etc. as long as the
     *      transitive types are mockable.
     *
     * Returns `null` for classes that aren't ViewModels or that have
     * a constructor we can't fully satisfy.
     */
    private fun tryViewModel(
        classifier: KClass<*>,
        classLoader: ClassLoader,
        recursiveMock: (KType) -> Any?,
    ): Any? {
        val viewModelClass = try {
            classLoader.loadClass("androidx.lifecycle.ViewModel")
        } catch (_: ClassNotFoundException) {
            return null
        }
        if (!viewModelClass.isAssignableFrom(classifier.java)) return null

        // (1) No-arg constructor.
        val noArg = runCatching {
            classifier.java.getDeclaredConstructor().apply { isAccessible = true }
        }.getOrNull()
        if (noArg != null) {
            runCatching { noArg.newInstance() }.getOrNull()?.let { return it }
        }

        // (2) Primary constructor with recursively-mocked args.
        val primary = classifier.primaryConstructor ?: return null
        val args = mutableMapOf<kotlin.reflect.KParameter, Any?>()
        for (p in primary.parameters) {
            val mocked = recursiveMock(p.type)
            when {
                mocked != null -> args[p] = mocked
                p.isOptional -> continue  // skip — Kotlin default applies
                p.type.isMarkedNullable -> args[p] = null
                else -> return null  // can't satisfy non-optional non-nullable
            }
        }
        return runCatching { primary.callBy(args) }.getOrNull()
    }
}
