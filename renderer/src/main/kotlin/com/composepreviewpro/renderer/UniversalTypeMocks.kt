package com.composepreviewpro.renderer

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import org.mockito.Mockito
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import java.util.Optional
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.time.Duration

/**
 * The catch-all final layer of the type-mocking cascade.
 *
 * It does three things, in order:
 *
 *   1. [tryStdlibDefault] — produces real instances for ~25 widely-used
 *      JVM stdlib types (Java time, java.util, kotlin.time, file/net,
 *      BigDecimal/BigInteger). These are types that appear all the
 *      time in real composables but that MockEngine can't construct
 *      from reflection alone.
 *
 *   2. [tryCoroutineDefault] — kotlinx.coroutines containers that
 *      mirror the [AdvancedTypeMocks] treatment for Flow/State: real
 *      empty/completable instances of `Job`, `Deferred<T>`,
 *      `Channel<T>`, `CoroutineScope`.
 *
 *   3. [tryMockitoFallback] — **the ultimate universal fallback**.
 *      When nothing above resolved, we ask Mockito to mock the class.
 *      Mockito 5's inline mock maker handles interfaces, abstract
 *      classes, and final classes via bytecode redefinition (the same
 *      machinery we already use for `android.content.Context`). With
 *      `RETURNS_DEFAULTS`, methods return null for objects, 0 for
 *      numbers, false for booleans, empty collections — sane defaults
 *      that let the composable render rather than crash.
 *
 * The Mockito fallback is what makes "any composable, any project,
 * just works" achievable. The trade-off is that if the user's
 * composable calls methods on the mocked argument that actually need
 * meaningful behaviour, the render will still fail. We don't try to
 * paper over that — failure with a clear stack trace is more useful
 * than a render that silently shows the wrong thing.
 */
internal object UniversalTypeMocks {

    fun tryDefault(type: KType, recursiveMock: (KType) -> Any?): Any? {
        val classifier = type.classifier as? KClass<*> ?: return null
        tryStdlibDefault(classifier)?.let { return it }
        tryCoroutineDefault(type, classifier, recursiveMock)?.let { return it }
        tryMockitoFallback(classifier)?.let { return it }
        return null
    }

    // ──────────────────────────────────────────────────────────────
    // Stdlib types
    // ──────────────────────────────────────────────────────────────

    private fun tryStdlibDefault(classifier: KClass<*>): Any? {
        return when (classifier.qualifiedName) {
            // ─── java.time ──────────────────────────────────────────
            "java.time.LocalDate" -> LocalDate.of(2026, 1, 1)
            "java.time.LocalDateTime" -> LocalDateTime.of(2026, 1, 1, 12, 0)
            "java.time.LocalTime" -> LocalTime.of(12, 0)
            "java.time.Instant" -> Instant.EPOCH
            "java.time.ZonedDateTime" -> ZonedDateTime.now()
            "java.time.Duration" -> java.time.Duration.ZERO
            // ─── kotlin.time ────────────────────────────────────────
            "kotlin.time.Duration" -> Duration.ZERO
            // ─── java.util ──────────────────────────────────────────
            "java.util.UUID" -> UUID(0L, 0L)  // deterministic, not random
            "java.util.Optional" -> Optional.empty<Any>()
            "java.util.Date" -> Date(0)
            "java.util.Calendar" -> Calendar.getInstance().apply { timeInMillis = 0 }
            // ─── java.io / java.net ─────────────────────────────────
            "java.io.File" -> File("")
            "java.net.URL" -> URL("https://preview.invalid/")
            "java.net.URI" -> URI("preview://")
            // ─── java.math ──────────────────────────────────────────
            "java.math.BigDecimal" -> BigDecimal.ZERO
            "java.math.BigInteger" -> BigInteger.ZERO
            else -> null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Coroutines
    // ──────────────────────────────────────────────────────────────

    private fun tryCoroutineDefault(
        type: KType,
        classifier: KClass<*>,
        recursiveMock: (KType) -> Any?,
    ): Any? {
        val firstArg = type.arguments.firstOrNull()?.type
        return when (classifier.qualifiedName) {
            "kotlinx.coroutines.Job" -> Job()
            "kotlinx.coroutines.Deferred",
            "kotlinx.coroutines.CompletableDeferred" -> {
                val inner = firstArg?.let(recursiveMock)
                CompletableDeferred(inner)
            }
            "kotlinx.coroutines.channels.Channel" -> Channel<Any?>()
            "kotlinx.coroutines.CoroutineScope" -> CoroutineScope(EmptyCoroutineContext)
            else -> null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Universal Mockito fallback
    // ──────────────────────────────────────────────────────────────

    /**
     * Last-resort mock for any class we don't have a recipe for.
     *
     * Why Mockito and not Java's `Proxy`?
     *   • `Proxy` only handles interfaces; Mockito 5's inline mock
     *     maker handles concrete classes, abstract classes, and even
     *     `final` classes via bytecode redefinition.
     *   • We already depend on Mockito for the synthesised Android
     *     `Context` graph, so no new dependency is being added.
     *
     * Excluded:
     *   • Primitive boxed types (`Int`, `Boolean`, …) — MockEngine
     *     already handles these and Mockito doesn't accept primitives.
     *   • Compose-runtime types that would be misleading if mocked
     *     (Composer, Recomposer, Compositions, snapshots) — we
     *     decline so an upstream error surfaces cleanly instead of
     *     producing a do-nothing object that breaks the composition
     *     later.
     *   • Function types — those are handled by MockEngine /
     *     ComposableLambdaSynth specifically; a Mockito mock of a
     *     `Function2` would not behave like a callable lambda.
     *
     * Returns null when the mock can't be built (e.g. classloader
     * issue, sealed class without permitted subclasses).
     */
    private fun tryMockitoFallback(classifier: KClass<*>): Any? {
        val java = classifier.java
        if (java.isPrimitive) return null
        if (classifier.qualifiedName?.startsWith("androidx.compose.runtime.") == true) return null
        if (java.isInterface && java.name.startsWith("kotlin.Function")) return null
        if (java.isAnnotation) return null
        return try {
            Mockito.mock(
                java,
                Mockito.withSettings()
                    .defaultAnswer(Mockito.RETURNS_DEFAULTS)
                    .stubOnly(),
            )
        } catch (_: Throwable) {
            null
        }
    }
}
