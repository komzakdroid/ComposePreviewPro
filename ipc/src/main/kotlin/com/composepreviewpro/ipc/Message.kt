package com.composepreviewpro.ipc

import kotlinx.serialization.Serializable

/**
 * Root sealed hierarchy of every message that can travel over the plugin↔
 * renderer IPC channel. Splitting requests and responses into two separate
 * sealed types makes it impossible to send a "result" as a "request" or
 * vice-versa — the compiler enforces direction.
 *
 * Wire format: each message is encoded as a single line of JSON, terminated
 * by '\n'. See [Protocol].
 */
@Serializable
sealed interface ClientMessage

@Serializable
sealed interface ServerMessage

// ─────────────────────────────────────────────────────────────
//  Client → Server  (plugin → renderer)
// ─────────────────────────────────────────────────────────────

/**
 * Render a composable. The renderer must:
 *   1. Build a URLClassLoader from [classpath]
 *   2. Resolve the function named by [target]
 *   3. Use the auto-mock engine to fabricate any missing arguments
 *   4. Render at [size] and reply with [RenderResult] or [ErrorResponse]
 */
@Serializable
data class RenderRequest(
    val requestId: String,
    val target: ComposableId,
    val classpath: ClasspathEntries,
    val size: RenderSize = RenderSize(),
    /** Outer MaterialTheme applied by the renderer (Light by default). */
    val theme: PreviewTheme = PreviewTheme.LIGHT,
    /**
     * User-supplied overrides for individual parameters of the previewed
     * composable, keyed by parameter name. Values are simple textual
     * representations (`"42"`, `"Hello"`, `"true"`) — the renderer
     * type-coerces against the target parameter's declared KType.
     * Params NOT present in this map fall back to the auto-mock engine.
     */
    val argOverrides: Map<String, String> = emptyMap(),
    /**
     * When true and the renderer was started with an `ANTHROPIC_API_KEY`
     * environment variable, the auto-mock engine will consult Claude
     * for *contextual* String mocks instead of falling back to the
     * generic-regex heuristics. For a `User.email` field that means
     * `"alice.chen@acme.com"` rather than `"user@example.com"`.
     */
    val useAiMocks: Boolean = false,
    /**
     * If true, the renderer MUST discard any cached URLClassLoader for
     * this classpath and load classes fresh from disk. Used by the hot
     * reload fallback path: after Gradle recompiles, the on-disk .class
     * files are new but URLClassLoader caches loaded classes by name —
     * reusing the cached loader would silently render the OLD code.
     */
    val freshClassLoader: Boolean = false,
) : ClientMessage

/**
 * In-place redefinition of class bytecodes via [java.lang.instrument.Instrumentation.redefineClasses].
 *
 * Sent by the plugin after an incremental compile detects which classes
 * changed. The renderer applies the new bytecodes WITHOUT discarding its
 * URLClassLoader — that means [java.lang.reflect.Method] identities are
 * preserved, which in turn means the Compose slot table keys still match,
 * which is what preserves mutableStateOf values, scroll positions, and
 * remember { ... } results across the reload.
 *
 * After redefinition the renderer should re-render the previously
 * displayed composable and respond with the new PNG.
 *
 * Constraints (HotSpot's [Instrumentation.redefineClasses] limits):
 *   • Method bodies may change freely.
 *   • Cannot add or remove methods/fields.
 *   • Cannot change class hierarchy or interfaces.
 *
 * If any class fails to redefine, the renderer responds with an
 * [ErrorResponse] of kind [ErrorKind.HOT_SWAP_FAILED] and the plugin
 * falls back to a full [RenderRequest] (URLClassLoader recreation).
 */
@Serializable
data class RedefineClasses(
    val requestId: String,
    /** FQN → Base64-encoded new bytecode. */
    val classes: Map<String, String>,
    /** Re-render after redefinition. Identifies *which* composable to re-invoke. */
    val rerender: RenderRequest? = null,
) : ClientMessage

/**
 * Inject an input event into the currently-rendered composable's live
 * scene, then re-render. Used by the interactive preview path so users
 * can actually click Buttons, scroll LazyColumns, etc. — turning the
 * preview from a static screenshot into a working surface.
 *
 * Coordinates are in *scene* space (the original render's widthPx /
 * heightPx), not the panel's possibly-zoomed pixel space; the plugin
 * is responsible for the translation.
 */
@Serializable
data class Interact(
    val requestId: String,
    val event: InputEvent,
) : ClientMessage

@Serializable
sealed interface InputEvent

/** Press-and-release at the same point (a tap). */
@Serializable
data class Click(val x: Int, val y: Int) : InputEvent

/** Wheel scroll: deltaY is positive when scrolling DOWN. */
@Serializable
data class Scroll(val x: Int, val y: Int, val deltaY: Float) : InputEvent

/** Graceful shutdown — server should close stdout and exit. */
@Serializable
data class Shutdown(val reason: String = "client requested") : ClientMessage

// ─────────────────────────────────────────────────────────────
//  Server → Client  (renderer → plugin)
// ─────────────────────────────────────────────────────────────

/** Server is up and ready to accept requests. Sent once on startup. */
@Serializable
data class Hello(val protocolVersion: Int = 1) : ServerMessage

/**
 * Successful render. [pngBase64] is the rendered surface encoded as a
 * Base64 PNG — chosen over raw bytes so the whole transport stays UTF-8
 * text and we never have to worry about binary framing in NDJSON.
 */
@Serializable
data class RenderResult(
    val requestId: String,
    val pngBase64: String,
    val widthPx: Int,
    val heightPx: Int,
    /**
     * Snapshot of which parameter values were used for THIS render:
     * `{name → displayString, type → typeName, editable → boolean}`.
     * The plugin's parameter-override panel uses it to populate edit
     * fields and decide which rows are user-editable (primitives) vs
     * read-only (lambdas, Modifier, opaque data classes).
     */
    val paramSummary: List<ParamInfo> = emptyList(),
    /**
     * Per-element source positions. Empty when the user's project does
     * not have `composeCompiler.includeSourceInformation = true` or
     * when the inspector failed to walk the composition.
     */
    val hitMap: HitMap = HitMap.EMPTY,
) : ServerMessage

@Serializable
data class ParamInfo(
    val name: String,
    val typeName: String,
    val currentValue: String,
    val editable: Boolean,
)

/** Anything went wrong — invalid target, mock failure, render exception. */
@Serializable
data class ErrorResponse(
    val requestId: String?,
    val kind: ErrorKind,
    val message: String,
    val stackTrace: String? = null,
) : ServerMessage

@Serializable
enum class ErrorKind {
    /** Function not found on the supplied classpath. */
    TARGET_NOT_FOUND,
    /** Function found, but a parameter type cannot be mocked. */
    MOCK_UNSUPPORTED,
    /** Compose host failed to set up (Skiko init, etc.). */
    RENDER_HOST_FAILED,
    /** User composable threw during composition. */
    COMPOSABLE_THREW,
    /** Malformed message on the wire. */
    PROTOCOL_ERROR,
    /**
     * In-place class redefinition failed (structural change, agent not
     * attached, etc.). Plugin should fall back to URLClassLoader recreation.
     */
    HOT_SWAP_FAILED,
}
