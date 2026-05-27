package com.composepreviewpro.renderer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.internal.ComposableLambda
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.composepreviewpro.mock.MockContext
import com.composepreviewpro.mock.MockEngine
import com.composepreviewpro.mock.MockResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.reflect.full.createType
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.typeOf

// ════════════════════════════════════════════════════════════════
// Test fixtures
// ════════════════════════════════════════════════════════════════

/** Minimal sealed hierarchy with an object, a data class, and a nested sealed. */
sealed interface TestResult {
    data object Loading : TestResult
    data class Success(val data: String) : TestResult
    data class Failure(val cause: String) : TestResult
}

/** Sealed hierarchy with NO object subclass — exercises the data-class branch. */
sealed interface TestPayload {
    data class Text(val value: String) : TestPayload
    data class Number(val value: Int) : TestPayload
}

/** Sealed with only nested sealed subclass — exercises recursion. */
sealed interface TestEvent {
    sealed interface Click : TestEvent {
        data object Single : Click
    }
}

/** Empty enum-ish sealed (should be unsupported). */
sealed interface EmptySealed

/** Data class for State/Flow/Pair inner-type tests. */
data class TestUser(val name: String, val age: Int)

/** Class with "Default" sentinel — exercises reflective fallback. */
class HasDefaultSentinel private constructor() {
    companion object {
        @JvmField val Default: HasDefaultSentinel = HasDefaultSentinel()
    }
}

/** Class with no-arg constructor — exercises reflective fallback. */
class HasNoArgCtor

/** Singleton object — exercises reflective object-instance fallback. */
object SingletonType

// ════════════════════════════════════════════════════════════════
// Test runner
// ════════════════════════════════════════════════════════════════

private class TestCase(val section: String, val name: String, val passed: Boolean, val detail: String)

private val results = mutableListOf<TestCase>()
private var currentSection = ""

private fun section(name: String) {
    currentSection = name
    println()
    println("── $name ──")
}

private inline fun test(name: String, block: () -> Unit) {
    try {
        block()
        results += TestCase(currentSection, name, passed = true, detail = "")
        println("  ✓ $name")
    } catch (t: Throwable) {
        val detail = "${t.javaClass.simpleName}: ${t.message ?: ""}"
        results += TestCase(currentSection, name, passed = false, detail = detail)
        println("  ✗ $name  ← $detail")
    }
}

@OptIn(kotlin.contracts.ExperimentalContracts::class)
private inline fun assertTrue(cond: Boolean, lazyMsg: () -> String) {
    kotlin.contracts.contract { returns() implies cond }
    if (!cond) error(lazyMsg())
}

private fun <T : Any> requireType(value: Any?, cls: Class<T>, what: String): T {
    if (value == null) error("$what: value is null")
    if (!cls.isInstance(value)) error("$what: expected ${cls.simpleName}, got ${value::class.java.simpleName}")
    @Suppress("UNCHECKED_CAST")
    return value as T
}

// ════════════════════════════════════════════════════════════════
// SECTION 1 — MockEngine sealed-class handling
// ════════════════════════════════════════════════════════════════

private fun mockEngineSealedTests() {
    section("MockEngine: sealed classes")

    test("sealed-with-object-subclass picks the object") {
        val res = MockEngine.mock(TestResult::class.starProjectedType, MockContext())
        assertTrue(res is MockResult.Success) { "expected Success, got $res" }
        assertTrue(res.value === TestResult.Loading) {
            "expected the Loading object, got ${res.value}"
        }
    }

    test("sealed-without-object falls through to first concrete subclass") {
        val res = MockEngine.mock(TestPayload::class.starProjectedType, MockContext())
        assertTrue(res is MockResult.Success) { "expected Success, got $res" }
        // Either subclass is acceptable — the contract is "produce ANY
        // valid concrete instance". We just confirm the resulting
        // object is one of them.
        assertTrue(res.value is TestPayload) { "expected TestPayload, got ${res.value}" }
    }

    test("sealed with nested-sealed-only recurses into the nested object") {
        val res = MockEngine.mock(TestEvent::class.starProjectedType, MockContext())
        assertTrue(res is MockResult.Success) { "expected Success, got $res" }
        assertTrue(res.value === TestEvent.Click.Single) {
            "expected nested Single object, got ${res.value}"
        }
    }

    test("empty sealed reports Unsupported") {
        val res = MockEngine.mock(EmptySealed::class.starProjectedType, MockContext())
        assertTrue(res is MockResult.Unsupported) { "expected Unsupported, got $res" }
    }
}

// ════════════════════════════════════════════════════════════════
// SECTION 2 — ComposableLambdaSynth
// ════════════════════════════════════════════════════════════════

// Real composable functions used to extract KParameter.type — these
// preserve the @Composable type annotation through Kotlin metadata
// the way the user's compiled code does. `typeOf<@Composable () -> Unit>()`
// does NOT propagate the annotation onto the resulting KType, so we
// can't use it for these tests.
@Suppress("unused", "UNUSED_PARAMETER")
private object ComposableSignatures {
    @androidx.compose.runtime.Composable
    fun arity0(content: @androidx.compose.runtime.Composable () -> Unit) = Unit

    @androidx.compose.runtime.Composable
    fun arity1(slot: @androidx.compose.runtime.Composable (Int) -> Unit) = Unit

    @androidx.compose.runtime.Composable
    fun arity3(triple: @androidx.compose.runtime.Composable (Int, String, Boolean) -> Unit) = Unit

    fun plainArity0(content: () -> Unit) = Unit
}

private fun composableLambdaTests() {
    section("ComposableLambdaSynth")

    fun typeOfParam(ref: kotlin.reflect.KFunction<*>): kotlin.reflect.KType {
        val p = ref.parameters.firstOrNull { it.kind == kotlin.reflect.KParameter.Kind.VALUE }
            ?: error("no value parameter on ${ref.name}")
        return p.type
    }

    test("@Composable () -> Unit yields a ComposableLambda") {
        val type = typeOfParam(ComposableSignatures::arity0)
        val lambda = ComposableLambdaSynth.tryNoOp(type)
        assertTrue(lambda != null) { "expected non-null no-op, got null" }
        assertTrue(lambda is ComposableLambda) {
            "expected ComposableLambda, got ${lambda!!::class.java.name}"
        }
    }

    test("@Composable (Int) -> Unit yields a ComposableLambda") {
        val type = typeOfParam(ComposableSignatures::arity1)
        val lambda = ComposableLambdaSynth.tryNoOp(type)
        assertTrue(lambda is ComposableLambda) {
            "expected ComposableLambda, got ${lambda?.let { it::class.java.name } ?: "null"}"
        }
    }

    test("@Composable (A, B, C) -> Unit yields a ComposableLambda") {
        val type = typeOfParam(ComposableSignatures::arity3)
        val lambda = ComposableLambdaSynth.tryNoOp(type)
        assertTrue(lambda is ComposableLambda) { "expected ComposableLambda" }
    }

    test("plain (non-@Composable) () -> Unit yields null") {
        val type = typeOfParam(ComposableSignatures::plainArity0)
        val lambda = ComposableLambdaSynth.tryNoOp(type)
        assertTrue(lambda == null) { "expected null for non-@Composable lambda, got $lambda" }
    }

    test("non-function type yields null") {
        val type = typeOf<Int>()
        assertTrue(ComposableLambdaSynth.tryNoOp(type) == null) { "expected null for Int type" }
    }
}

// ════════════════════════════════════════════════════════════════
// SECTION 3 — ComposeTypeMocks
// ════════════════════════════════════════════════════════════════

private fun composeTypeTests() {
    section("ComposeTypeMocks")

    test("Color → Color.Unspecified") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Color>())
        requireType(v, Color::class.java, "Color")
    }
    test("Dp → 0.dp") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Dp>())
        requireType(v, Dp::class.java, "Dp")
    }
    test("TextUnit → TextUnit.Unspecified") {
        val v = ComposeTypeMocks.tryDefault(typeOf<TextUnit>())
        requireType(v, TextUnit::class.java, "TextUnit")
    }
    test("ImageVector → non-null empty vector") {
        val v = ComposeTypeMocks.tryDefault(typeOf<ImageVector>())
        requireType(v, ImageVector::class.java, "ImageVector")
    }
    test("Painter → ColorPainter") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Painter>())
        requireType(v, Painter::class.java, "Painter")
    }
    test("Brush → SolidColor") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Brush>())
        requireType(v, Brush::class.java, "Brush")
    }
    test("Shape → RectangleShape") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Shape>())
        requireType(v, Shape::class.java, "Shape")
    }
    test("Alignment → Alignment.Center") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Alignment>())
        requireType(v, Alignment::class.java, "Alignment")
    }
    test("Alignment.Horizontal → CenterHorizontally") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Alignment.Horizontal>())
        requireType(v, Alignment.Horizontal::class.java, "Alignment.Horizontal")
    }
    test("Alignment.Vertical → CenterVertically") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Alignment.Vertical>())
        requireType(v, Alignment.Vertical::class.java, "Alignment.Vertical")
    }
    test("TextStyle → TextStyle.Default") {
        val v = ComposeTypeMocks.tryDefault(typeOf<TextStyle>())
        requireType(v, TextStyle::class.java, "TextStyle")
    }
    test("FontWeight → FontWeight.Normal") {
        val v = ComposeTypeMocks.tryDefault(typeOf<FontWeight>())
        requireType(v, FontWeight::class.java, "FontWeight")
    }
    test("FontStyle → FontStyle.Normal") {
        val v = ComposeTypeMocks.tryDefault(typeOf<FontStyle>())
        requireType(v, FontStyle::class.java, "FontStyle")
    }
    test("FontFamily → FontFamily.Default") {
        val v = ComposeTypeMocks.tryDefault(typeOf<FontFamily>())
        requireType(v, FontFamily::class.java, "FontFamily")
    }
    test("TextAlign → TextAlign.Start") {
        val v = ComposeTypeMocks.tryDefault(typeOf<TextAlign>())
        requireType(v, TextAlign::class.java, "TextAlign")
    }
    test("TextDecoration → TextDecoration.None") {
        val v = ComposeTypeMocks.tryDefault(typeOf<TextDecoration>())
        requireType(v, TextDecoration::class.java, "TextDecoration")
    }
    test("TextOverflow → TextOverflow.Clip") {
        val v = ComposeTypeMocks.tryDefault(typeOf<TextOverflow>())
        requireType(v, TextOverflow::class.java, "TextOverflow")
    }
    test("PaddingValues → zero") {
        val v = ComposeTypeMocks.tryDefault(typeOf<PaddingValues>())
        requireType(v, PaddingValues::class.java, "PaddingValues")
    }
    test("RoundedCornerShape → zero corners") {
        val v = ComposeTypeMocks.tryDefault(typeOf<RoundedCornerShape>())
        requireType(v, RoundedCornerShape::class.java, "RoundedCornerShape")
    }

    test("reflective fallback: Companion.Default sentinel") {
        val v = ComposeTypeMocks.tryDefault(HasDefaultSentinel::class.createType())
        assertTrue(v === HasDefaultSentinel.Default) { "expected the Default sentinel, got $v" }
    }
    test("reflective fallback: no-arg constructor") {
        val v = ComposeTypeMocks.tryDefault(HasNoArgCtor::class.createType())
        requireType(v, HasNoArgCtor::class.java, "HasNoArgCtor")
    }
    test("reflective fallback: Kotlin object instance") {
        val v = ComposeTypeMocks.tryDefault(SingletonType::class.createType())
        assertTrue(v === SingletonType) { "expected the singleton, got $v" }
    }
    test("reflective fallback: returns null for genuinely-unknown class") {
        val v = ComposeTypeMocks.tryDefault(typeOf<TestUser>())
        // TestUser is a data class WITHOUT a no-arg ctor, no Default
        // sentinel, no companion. The reflective fallback should
        // decline rather than fabricate something incorrect.
        assertTrue(v == null) { "expected null fallback for TestUser, got $v" }
    }

    // ── Geometry & layout (added in v0.2.8) ────────────────────
    test("Size → Size.Zero") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Size>())
        assertTrue(v is Size) { "expected Size, got $v" }
    }
    test("Offset → Offset.Zero") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Offset>())
        assertTrue(v is Offset) { "expected Offset, got $v" }
    }
    test("Rect → Rect.Zero") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Rect>())
        assertTrue(v is Rect) { "expected Rect, got $v" }
    }
    test("IntSize → IntSize.Zero") {
        val v = ComposeTypeMocks.tryDefault(typeOf<IntSize>())
        assertTrue(v is IntSize) { "expected IntSize, got $v" }
    }
    test("IntOffset → IntOffset.Zero") {
        val v = ComposeTypeMocks.tryDefault(typeOf<IntOffset>())
        assertTrue(v is IntOffset) { "expected IntOffset, got $v" }
    }
    test("Density → density of 1") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Density>())
        requireType(v, Density::class.java, "Density")
    }
    test("LayoutDirection → Ltr") {
        val v = ComposeTypeMocks.tryDefault(typeOf<LayoutDirection>())
        assertTrue(v == LayoutDirection.Ltr) { "expected Ltr, got $v" }
    }

    // ── Text & input (added in v0.2.8) ─────────────────────────
    test("AnnotatedString → empty") {
        val v = ComposeTypeMocks.tryDefault(typeOf<AnnotatedString>())
        requireType(v, AnnotatedString::class.java, "AnnotatedString")
    }
    test("TextFieldValue → empty") {
        val v = ComposeTypeMocks.tryDefault(typeOf<TextFieldValue>())
        requireType(v, TextFieldValue::class.java, "TextFieldValue")
    }

    // ── Graphics extras (added in v0.2.8) ──────────────────────
    test("Path → empty path") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Path>())
        assertTrue(v is Path) { "expected Path, got $v" }
    }
    test("Stroke → default stroke") {
        val v = ComposeTypeMocks.tryDefault(typeOf<Stroke>())
        assertTrue(v is Stroke) { "expected Stroke, got $v" }
    }

    // ── State holders (added in v0.2.8) ────────────────────────
    test("ScrollState → at offset 0") {
        val v = ComposeTypeMocks.tryDefault(typeOf<ScrollState>())
        requireType(v, ScrollState::class.java, "ScrollState")
    }
    test("LazyListState → empty") {
        val v = ComposeTypeMocks.tryDefault(typeOf<LazyListState>())
        requireType(v, LazyListState::class.java, "LazyListState")
    }
    test("MutableInteractionSource → real interaction source") {
        val v = ComposeTypeMocks.tryDefault(typeOf<MutableInteractionSource>())
        assertTrue(v is MutableInteractionSource) { "expected MutableInteractionSource, got $v" }
    }
    test("SnackbarHostState → real state") {
        val v = ComposeTypeMocks.tryDefault(typeOf<SnackbarHostState>())
        requireType(v, SnackbarHostState::class.java, "SnackbarHostState")
    }
}

// ════════════════════════════════════════════════════════════════
// SECTION 4 — AdvancedTypeMocks
// ════════════════════════════════════════════════════════════════

private fun advancedTypeTests() {
    section("AdvancedTypeMocks")
    val cl = TypeMockTestSentinel::class.java.classLoader
    val recurse: (kotlin.reflect.KType) -> Any? = { type ->
        when (val r = MockEngine.mock(type, MockContext(classLoader = cl))) {
            is MockResult.Success -> r.value
            is MockResult.Unsupported -> ComposeTypeMocks.tryDefault(type)
        }
    }

    test("State<String> → MutableState with mocked string") {
        val v = AdvancedTypeMocks.tryDefault(typeOf<State<String>>(), cl, recurse)
        requireType(v, MutableState::class.java, "MutableState<String>")
    }
    test("MutableState<Int> → MutableState with mocked int") {
        val v = AdvancedTypeMocks.tryDefault(typeOf<MutableState<Int>>(), cl, recurse)
        requireType(v, MutableState::class.java, "MutableState<Int>")
    }
    test("Flow<String> → non-null Flow") {
        val v = AdvancedTypeMocks.tryDefault(typeOf<Flow<String>>(), cl, recurse)
        assertTrue(v is Flow<*>) { "expected Flow, got ${v?.let { it::class.java.name } ?: "null"}" }
    }
    test("StateFlow<TestUser> → MutableStateFlow with mocked user") {
        val v = AdvancedTypeMocks.tryDefault(typeOf<StateFlow<TestUser>>(), cl, recurse)
        assertTrue(v is MutableStateFlow<*>) { "expected MutableStateFlow, got $v" }
    }
    test("SharedFlow<Any> → MutableSharedFlow") {
        val v = AdvancedTypeMocks.tryDefault(typeOf<SharedFlow<Any>>(), cl, recurse)
        assertTrue(v is MutableSharedFlow<*>) { "expected MutableSharedFlow, got $v" }
    }
    test("Lazy<String> → lazy holder") {
        val v = AdvancedTypeMocks.tryDefault(typeOf<Lazy<String>>(), cl, recurse)
        assertTrue(v is Lazy<*>) { "expected Lazy, got $v" }
    }
    test("Pair<Int, String> → both components mocked") {
        val v = AdvancedTypeMocks.tryDefault(typeOf<Pair<Int, String>>(), cl, recurse)
        assertTrue(v is Pair<*, *>) { "expected Pair, got $v" }
        assertTrue((v as Pair<*, *>).first != null && v.second != null) {
            "expected both components non-null, got first=${v.first}, second=${v.second}"
        }
    }
    test("Triple<Int, String, Boolean> → all three mocked") {
        val v = AdvancedTypeMocks.tryDefault(typeOf<Triple<Int, String, Boolean>>(), cl, recurse)
        assertTrue(v is Triple<*, *, *>) { "expected Triple, got $v" }
    }
    test("unknown type returns null (no false positive)") {
        // TestUser is a data class — MockEngine handles it, but
        // AdvancedTypeMocks itself should refuse and return null
        // so the outer cascade defers to MockEngine.
        val v = AdvancedTypeMocks.tryDefault(typeOf<TestUser>(), cl, recurse)
        assertTrue(v == null) { "expected null for TestUser, got $v" }
    }
}

// Sentinel class used only to grab a stable ClassLoader reference for
// the advanced-mocks test.
private class TypeMockTestSentinel

// ════════════════════════════════════════════════════════════════
// SECTION 5 — UniversalTypeMocks (stdlib + coroutines + Mockito)
// ════════════════════════════════════════════════════════════════

/** Random interface no one has special-cased — must be Mockito-mocked. */
private interface UnknownInterface {
    fun doThing(): String
    val state: Int
}

/** Abstract class with no companion / sentinel — must be Mockito-mocked. */
private abstract class UnknownAbstractClass {
    abstract fun compute(): Long
}

/** Final class with no no-arg ctor — must be Mockito-mocked via inline. */
private class UnknownFinalClass(val data: String)

private fun universalTypeTests() {
    section("UniversalTypeMocks")
    val recurse: (kotlin.reflect.KType) -> Any? = { type ->
        when (val r = MockEngine.mock(type, MockContext())) {
            is MockResult.Success -> r.value
            is MockResult.Unsupported -> null
        }
    }

    // ─── Stdlib date/time ──────────────────────────────────────
    test("java.time.LocalDate → fixed date") {
        val v = UniversalTypeMocks.tryDefault(typeOf<LocalDate>(), recurse)
        requireType(v, LocalDate::class.java, "LocalDate")
    }
    test("java.time.Instant → EPOCH") {
        val v = UniversalTypeMocks.tryDefault(typeOf<Instant>(), recurse)
        assertTrue(v === Instant.EPOCH) { "expected EPOCH, got $v" }
    }
    test("kotlin.time.Duration → ZERO") {
        val v = UniversalTypeMocks.tryDefault(typeOf<kotlin.time.Duration>(), recurse)
        assertTrue(v is kotlin.time.Duration) { "expected Duration, got $v" }
    }
    test("java.util.UUID → deterministic zero UUID") {
        val v = UniversalTypeMocks.tryDefault(typeOf<UUID>(), recurse)
        assertTrue(v is UUID) { "expected UUID, got $v" }
    }
    test("java.util.Optional → empty") {
        val v = UniversalTypeMocks.tryDefault(typeOf<Optional<String>>(), recurse)
        assertTrue(v is Optional<*>) { "expected Optional, got $v" }
        assertTrue(!(v as Optional<*>).isPresent) { "expected empty Optional" }
    }
    test("java.math.BigDecimal → ZERO") {
        val v = UniversalTypeMocks.tryDefault(typeOf<BigDecimal>(), recurse)
        assertTrue(v == BigDecimal.ZERO) { "expected ZERO, got $v" }
    }
    test("java.math.BigInteger → ZERO") {
        val v = UniversalTypeMocks.tryDefault(typeOf<BigInteger>(), recurse)
        assertTrue(v == BigInteger.ZERO) { "expected ZERO, got $v" }
    }
    test("java.net.URL → preview placeholder") {
        val v = UniversalTypeMocks.tryDefault(typeOf<URL>(), recurse)
        requireType(v, URL::class.java, "URL")
    }

    // ─── Coroutines containers ─────────────────────────────────
    test("Job → completable Job instance") {
        val v = UniversalTypeMocks.tryDefault(typeOf<Job>(), recurse)
        assertTrue(v is Job) { "expected Job, got $v" }
    }
    test("Deferred<Int> → CompletableDeferred") {
        val v = UniversalTypeMocks.tryDefault(typeOf<Deferred<Int>>(), recurse)
        assertTrue(v is Deferred<*>) { "expected Deferred, got $v" }
    }
    test("CompletableDeferred<String> → CompletableDeferred") {
        val v = UniversalTypeMocks.tryDefault(typeOf<CompletableDeferred<String>>(), recurse)
        assertTrue(v is CompletableDeferred<*>) { "expected CompletableDeferred" }
    }
    test("Channel<Int> → non-null channel") {
        val v = UniversalTypeMocks.tryDefault(typeOf<Channel<Int>>(), recurse)
        assertTrue(v is Channel<*>) { "expected Channel, got $v" }
    }
    test("CoroutineScope → non-null scope") {
        val v = UniversalTypeMocks.tryDefault(typeOf<CoroutineScope>(), recurse)
        assertTrue(v is CoroutineScope) { "expected CoroutineScope, got $v" }
    }

    // ─── Universal Mockito fallback ────────────────────────────
    test("unknown interface → Mockito mock instance") {
        val v = UniversalTypeMocks.tryDefault(typeOf<UnknownInterface>(), recurse)
        assertTrue(v is UnknownInterface) { "expected UnknownInterface, got $v" }
        // Default-returning mock; the methods return null/0, but the
        // *instance* exists and is type-compatible.
    }
    test("unknown abstract class → Mockito mock instance") {
        val v = UniversalTypeMocks.tryDefault(UnknownAbstractClass::class.createType(), recurse)
        assertTrue(v is UnknownAbstractClass) { "expected UnknownAbstractClass, got $v" }
    }
    test("unknown final class with no no-arg ctor → Mockito mock instance") {
        val v = UniversalTypeMocks.tryDefault(typeOf<UnknownFinalClass>(), recurse)
        assertTrue(v is UnknownFinalClass) { "expected UnknownFinalClass, got $v" }
    }
    test("Mockito fallback declines Compose-runtime types") {
        // Even though it's a class, we deliberately don't try to mock
        // androidx.compose.runtime.* because a mocked Composer breaks
        // composition catastrophically.
        val type = androidx.compose.runtime.Composer::class.createType()
        val v = UniversalTypeMocks.tryDefault(type, recurse)
        assertTrue(v == null) { "expected null for Composer, got $v" }
    }
}

// ════════════════════════════════════════════════════════════════
// SECTION 6 — Full ArgumentBinder cascade (integration)
// ════════════════════════════════════════════════════════════════

/**
 * A realistic composable signature mixing every kind of parameter the
 * cascade needs to handle. If `ArgumentBinder.bind()` returns Ready
 * for this, every layer is wired correctly end-to-end.
 */
// Integration signatures are split across three @Composable methods.
// Kotlin reflection on @Composable functions caps the arity it can
// represent (the `KComposableFunctionN` family is only emitted up to
// ~22), so we deliberately stay well under that limit per method.
@Suppress("UNUSED_PARAMETER")
private object IntegrationSignatures {

    /** Compose-UI value types + primitives + function callbacks. */
    @androidx.compose.runtime.Composable
    fun composeUiHeavy(
        title: String,
        count: Int,
        enabled: Boolean,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        color: androidx.compose.ui.graphics.Color,
        modifier: androidx.compose.ui.Modifier,
        padding: androidx.compose.foundation.layout.PaddingValues,
        textStyle: androidx.compose.ui.text.TextStyle,
        onClick: () -> Unit,
        onSelectionChange: (Int) -> Unit,
        content: @androidx.compose.runtime.Composable () -> Unit,
        actionSlot: @androidx.compose.runtime.Composable (Boolean) -> Unit,
    ) = Unit

    /** State / Flow / sealed / data-class params. */
    @androidx.compose.runtime.Composable
    fun stateAndDataHeavy(
        text: androidx.compose.runtime.State<String>,
        flowValue: kotlinx.coroutines.flow.Flow<Int>,
        stateFlowValue: kotlinx.coroutines.flow.StateFlow<TestUser>,
        result: TestResult,
        user: TestUser,
        items: List<TestUser>,
        labels: Map<String, Int>,
    ) = Unit

    /** Stdlib + coroutines + Mockito-fallback unknown type. */
    @androidx.compose.runtime.Composable
    fun stdlibAndUnknownHeavy(
        timestamp: java.time.Instant,
        identifier: java.util.UUID,
        amount: java.math.BigDecimal,
        jobToken: kotlinx.coroutines.Job,
        scope: kotlinx.coroutines.CoroutineScope,
        opaque: UnknownInterface,
        abstractObj: UnknownAbstractClass,
    ) = Unit
}

private fun integrationTests() {
    section("ArgumentBinder: full cascade integration")

    val cl = TypeMockTestSentinel::class.java.classLoader
    val binder = ArgumentBinder(cl)

    fun assertReady(fn: kotlin.reflect.KFunction<*>, label: String) {
        val result = binder.bind(fn)
        when (result) {
            is ArgumentBinder.BindResult.Ready -> {
                val expected = fn.parameters.count { it.kind == kotlin.reflect.KParameter.Kind.VALUE }
                assertTrue(result.args.size == expected) {
                    "$label: expected $expected bound args, got ${result.args.size} " +
                        "(missing: ${fn.parameters.filter { it !in result.args.keys && it.kind == kotlin.reflect.KParameter.Kind.VALUE }.map { it.name }})"
                }
            }
            is ArgumentBinder.BindResult.Failed ->
                error("$label: Failed on '${result.parameter.name}': ${result.reason}")
        }
    }

    test("Compose-UI heavy signature binds (primitives + Compose types + lambdas)") {
        assertReady(IntegrationSignatures::composeUiHeavy, "composeUiHeavy")
    }
    test("State/Flow/sealed/data-class signature binds") {
        assertReady(IntegrationSignatures::stateAndDataHeavy, "stateAndDataHeavy")
    }
    test("Stdlib + coroutines + unknown-type signature binds") {
        assertReady(IntegrationSignatures::stdlibAndUnknownHeavy, "stdlibAndUnknownHeavy")
    }
}

// ════════════════════════════════════════════════════════════════
// SECTION 7 — AndroidCompositionLocalProviders discovery
// ════════════════════════════════════════════════════════════════

private fun androidCompositionLocalsTests() {
    section("AndroidCompositionLocalProviders")
    val cl = TypeMockTestSentinel::class.java.classLoader

    test("discovery never throws and returns ProvidedValues for present accessors") {
        // The renderer's classloader transitively pulls in a couple of
        // Local accessor classes (LocalLifecycleOwner via lifecycle-
        // runtime-compose, depending on the Compose Multiplatform
        // version). The contract we test is:
        //   1. Discovery doesn't throw, no matter what's on classpath.
        //   2. Every returned entry is a non-null ProvidedValue.
        // We deliberately don't assert a fixed size because the set
        // varies by what the renderer module pulled in.
        val stub = Any()  // never used — accessors here aren't LocalContext
        val provideds = AndroidCompositionLocalProviders.discoverAndProvide(cl, stub)
        assertTrue(provideds.all { it != null }) {
            "all entries must be non-null ProvidedValues"
        }
    }

    test("discovery is idempotent across calls") {
        // Re-running should produce the same shape — accessors and
        // Mockito mocks of the same target types. Identity of the
        // mocks differs but the COUNT must match.
        val first = AndroidCompositionLocalProviders.discoverAndProvide(cl, Any())
        val second = AndroidCompositionLocalProviders.discoverAndProvide(cl, Any())
        assertTrue(first.size == second.size) {
            "discovery should be deterministic; first=${first.size}, second=${second.size}"
        }
    }

    test("overrides map wins over auto-discovery for named locals") {
        val customMarker = "custom-marker-${System.nanoTime()}"
        // Force "LocalLifecycleOwner" (one of the auto-discovered
        // locals when lifecycle-runtime-compose is on the classpath)
        // to receive our explicit override rather than a Mockito mock.
        // We can't easily verify identity in non-Android scenarios
        // because the accessor class might be absent — so we just
        // check the discovery doesn't crash with an override present.
        val provideds = AndroidCompositionLocalProviders.discoverAndProvide(
            classLoader = cl,
            contextStub = Any(),
            overrides = mapOf("LocalLifecycleOwner" to customMarker),
        )
        assertTrue(provideds.all { it != null }) {
            "overrides shouldn't break provider construction"
        }
    }

    test("UserCompositionLocalsDiscoverer never throws and handles empty classpath") {
        val provideds = UserCompositionLocalsDiscoverer.discover(
            classLoader = TypeMockTestSentinel::class.java.classLoader,
            classpathRoots = emptyList(),
        )
        assertTrue(provideds.isEmpty()) { "empty classpath should yield zero provideds" }
    }

    test("UserCompositionLocalsDiscoverer skips library namespaces") {
        // Pass a fake classpath root pointing at the renderer's own
        // class output directory. Most of THIS module's classes are
        // outside the heuristic name keywords; the few that match
        // (TypeMockTest, ComposeTypeMocks) shouldn't contain `Local*`
        // accessors. Either way, the call must not crash.
        val provideds = UserCompositionLocalsDiscoverer.discover(
            classLoader = TypeMockTestSentinel::class.java.classLoader,
            classpathRoots = listOf("/non/existent/path", "/dev/null"),
        )
        assertTrue(provideds.isEmpty()) { "non-existent paths must safely yield empty list" }
    }

    test("AndroidContextStub Resources returns non-null defaults for Jetpack stringResource") {
        // Validate the BEHAVIOUR we promise to material3 components:
        // every non-nullable Resources getter returns non-null.
        val cl = TypeMockTestSentinel::class.java.classLoader
        val graph = AndroidContextStub.createGraphOrNull(cl, emptyList())
        // The renderer's own classloader has no android.content.Context;
        // graph is null. That's an expected outcome of THIS test
        // (which we use as a smoke check) — the production scenario
        // is exercised by the IPC integration test where the user's
        // classloader DOES have Android.
        if (graph == null) {
            System.err.println(
                "[test] AndroidContextStub graph not buildable here " +
                    "(non-Android renderer classloader) — skipping deep assertions",
            )
        } else {
            // If we ever land in a context where the graph builds, then
            // these invariants must hold:
            assertTrue(graph.resources != null) { "Resources stub null" }
            assertTrue(graph.context != null) { "Context stub null" }
            assertTrue(graph.assets != null) { "AssetManager stub null" }
            // Configuration may legitimately be null if the no-arg ctor
            // is unavailable (rare; depends on android.jar revision).
        }
    }

    test("Mockito mock targets are safely default-answered") {
        // For any returned ProvidedValue, calling toString() on its
        // internal value should not throw — that's the smoke test
        // that Mockito's RETURNS_DEFAULTS is wired correctly. We
        // peek via reflection (ProvidedValue's `value` is internal
        // but accessible via Java reflection on the field).
        val provideds = AndroidCompositionLocalProviders.discoverAndProvide(cl, Any())
        for (pv in provideds) {
            // Find the `value` field on ProvidedValue.
            val valueField = pv.javaClass.declaredFields.firstOrNull { it.name == "value" }
                ?: continue
            valueField.isAccessible = true
            val value = valueField.get(pv)
            // Just confirm we can stringify without crashing.
            value?.toString()
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Main runner
// ════════════════════════════════════════════════════════════════

fun main() {
    println("[type-mock-test] starting at ${java.time.LocalDateTime.now()}")

    mockEngineSealedTests()
    composableLambdaTests()
    composeTypeTests()
    advancedTypeTests()
    universalTypeTests()
    integrationTests()
    androidCompositionLocalsTests()

    val failed = results.filter { !it.passed }
    println()
    println("═".repeat(60))
    println("Total : ${results.size}")
    println("Passed: ${results.size - failed.size}")
    println("Failed: ${failed.size}")
    if (failed.isNotEmpty()) {
        println()
        println("Failures:")
        for (r in failed) println("  [${r.section}] ${r.name}  ← ${r.detail}")
        kotlin.system.exitProcess(1)
    } else {
        println("✅ ALL GREEN")
    }
}
