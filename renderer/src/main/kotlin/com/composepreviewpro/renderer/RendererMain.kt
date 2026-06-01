package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ClientMessage
import com.composepreviewpro.ipc.ErrorKind
import com.composepreviewpro.ipc.ErrorResponse
import com.composepreviewpro.ipc.Hello
import com.composepreviewpro.ipc.Interact
import com.composepreviewpro.ipc.MessageReader
import com.composepreviewpro.ipc.MessageWriter
import com.composepreviewpro.ipc.RedefineClasses
import com.composepreviewpro.ipc.RenderRequest
import com.composepreviewpro.ipc.RenderResult
import com.composepreviewpro.ipc.ServerMessage
import com.composepreviewpro.ipc.Shutdown
import kotlinx.serialization.serializer
import kotlin.system.exitProcess

/**
 * Entry point of the renderer process.
 *
 * Wire protocol:
 *   1. On startup, attach the hot-reload Java Agent and emit Hello.
 *   2. Read ClientMessages from stdin in a loop:
 *      • RenderRequest    — render a composable; cache the session
 *      • RedefineClasses  — hot-swap bytecodes in the cached session,
 *                            then re-render
 *      • Shutdown         — exit cleanly
 *   3. On stdin EOF, exit.
 *
 * All diagnostic output goes to stderr — stdout is reserved for IPC.
 */
fun main() {
    val writer = MessageWriter(System.out, serializer<ServerMessage>())
    val reader = MessageReader(System.`in`, serializer<ClientMessage>())

    // Best-effort: agent attach is not fatal. If it fails we still serve
    // RenderRequests; only RedefineClasses requests will be rejected.
    val instrumentation = AgentLoader.ensureLoaded()
    val agentStatus = if (instrumentation != null) "attached" else "MISSING"

    // Register the framework-native neutraliser BEFORE any render so that the
    // first time the bundled android-all runtime's classes load (e.g.
    // android.os.Build via WindowInsets), their `native` methods are already
    // rewritten to return defaults — no UnsatisfiedLinkError off-device.
    NativeMethodNeutralizer.installInto(instrumentation)

    writer.send(Hello())
    System.err.println("[renderer] up — protocol v1, agent=$agentStatus")

    val sessionCache = SessionCache()
    val sourceMapper = ComposeSourceMapper()

    try {
        while (true) {
            // Two error boundaries inside the main loop, each producing
            // an ErrorResponse + continue instead of killing the process:
            //
            //   1. Deserialisation: a malformed JSON line on stdin (e.g.
            //      the plugin shipped a truncated base64 payload, or a
            //      protocol-version skew sneaked in a field the wire
            //      schema cannot decode). Old behaviour was
            //      exitProcess(1) — the plugin's client then saw the
            //      pipe close, marked the subprocess Crashed, and
            //      respawned a fresh JVM. With several thousand kt
            //      classes that respawn is ~3s of latency, and if the
            //      offending request was retried verbatim, we entered
            //      a permanent restart loop.
            //   2. Handler-throws: any uncaught exception inside a
            //      RenderRequest / RedefineClasses / Interact handler.
            //      Composables that throw during composition already
            //      reach us through ErrorKind.COMPOSABLE_THREW from
            //      performRender, but a handler-level bug (NPE in our
            //      glue code, a static-init failure on a user class we
            //      reflect on, etc.) used to bubble out and kill the
            //      process. Now it produces a PROTOCOL_ERROR and we
            //      stay alive for the next request.
            //
            // Only EOF on stdin (plugin disconnected) and Shutdown
            // (plugin asked us to stop) are valid loop-exit conditions.
            val msg = try {
                reader.readNext() ?: break  // EOF — plugin disconnected
            } catch (t: Throwable) {
                System.err.println("[renderer] PROTOCOL_ERROR while reading: ${t.javaClass.simpleName}: ${t.message}")
                writer.send(t.toErrorResponse(requestId = null, kind = ErrorKind.PROTOCOL_ERROR))
                continue
            }
            try {
                when (msg) {
                    is RenderRequest -> handleRender(msg, writer, sessionCache, sourceMapper)
                    is RedefineClasses -> handleRedefine(msg, writer, sessionCache, instrumentation)
                    is Interact -> handleInteract(msg, writer, sessionCache)
                    is Shutdown -> {
                        System.err.println("[renderer] shutdown: ${msg.reason}")
                        break
                    }
                }
            } catch (t: Throwable) {
                System.err.println("[renderer] handler-level error: ${t.javaClass.simpleName}: ${t.message}")
                t.printStackTrace(System.err)
                writer.send(t.toErrorResponse(requestId = null, kind = ErrorKind.PROTOCOL_ERROR))
            }
        }
    } catch (t: Throwable) {
        // Truly unrecoverable — sessionCache failed to clean up, writer
        // died, etc. We still try to surface the error before exiting so
        // the plugin gets a final breadcrumb.
        try {
            writer.send(t.toErrorResponse(requestId = null, kind = ErrorKind.PROTOCOL_ERROR))
        } catch (_: Throwable) { /* writer already broken */ }
        exitProcess(1)
    } finally {
        sessionCache.close()
        writer.close()
        reader.close()
    }
}

private fun handleRender(
    req: RenderRequest,
    writer: MessageWriter<ServerMessage>,
    sessionCache: SessionCache,
    sourceMapper: ComposeSourceMapper,
) {
    writer.send(performRender(req, sessionCache, sourceMapper))
}

/**
 * Pure function: execute a render and return the response. Split out from
 * [handleRender] so [handleRedefine] can reuse it without writing twice
 * to stdout (which would confuse the wire protocol).
 */
private fun performRender(
    req: RenderRequest,
    sessionCache: SessionCache,
    sourceMapper: ComposeSourceMapper,
): ServerMessage = try {
    val resolver = sessionCache.getOrCreate(req.classpath, forceFresh = req.freshClassLoader)
    if (req.freshClassLoader) {
        System.err.println("[renderer] forced fresh classloader for ${req.target.fqn}")
    }
    // Kotlin-reflect path first. When it returns null, fall back to the raw
    // Java method — this is the value-class-mangled composable case
    // (`accent: Color`), which kotlin-reflect cannot model at all.
    val fn = resolver.resolve(req.target)
    val javaMethod = if (fn == null) resolver.resolveMethod(req.target) else null
    when {
        fn == null && javaMethod == null -> ErrorResponse(
            requestId = req.requestId,
            kind = ErrorKind.TARGET_NOT_FOUND,
            message = "Composable ${req.target.fqn} not found on supplied classpath",
        )

        // ── Java path: value-class-mangled composable, no usable KFunction ──
        fn == null -> {
            val session = sessionCache.getOrCreateSession(
                fqn = req.target.fqn,
                widthPx = req.size.widthPx,
                heightPx = req.size.heightPx,
                theme = req.theme,
            )
            val base64 = session.mountJava(javaMethod!!, req.classpath.paths)
            val funcMap = sourceMapper.mapFor(req.classpath.paths)
            RenderResult(
                requestId = req.requestId,
                pngBase64 = base64,
                widthPx = req.size.widthPx,
                heightPx = req.size.heightPx,
                paramSummary = JavaComposableInvoker.summarize(javaMethod),
                hitMap = session.computeHitMap(funcMap),
            )
        }

        // ── Kotlin path: usable KFunction → full mock-engine binding ──
        else -> {
            val aiMocker: ((String, String, String) -> String?)? =
                if (req.useAiMocks) AiMockClient.FROM_ENV?.let { client -> client::generateString } else null
            when (val bind = ArgumentBinder(resolver.classLoader, req.argOverrides, aiMocker).bind(fn)) {
                is ArgumentBinder.BindResult.Failed -> ErrorResponse(
                    requestId = req.requestId,
                    kind = ErrorKind.MOCK_UNSUPPORTED,
                    message = "Cannot mock parameter '${bind.parameter.name}': ${bind.reason}",
                )
                is ArgumentBinder.BindResult.Ready -> {
                    val session = sessionCache.getOrCreateSession(
                        fqn = req.target.fqn,
                        widthPx = req.size.widthPx,
                        heightPx = req.size.heightPx,
                        theme = req.theme,
                    )
                    // Hand the classpath roots to the session so the
                    // synthetic AssetManager (Android scenarios) can index
                    // compose-resource files outside the URLClassLoader's
                    // direct knowledge.
                    val base64 = session.mount(fn, bind.args, req.classpath.paths)
                    // Parse user classpath ONCE per (classpath signature)
                    // and cache; this is the bridge that lets us pair each
                    // composition slot key with its source file:line.
                    val funcMap = sourceMapper.mapFor(req.classpath.paths)
                    RenderResult(
                        requestId = req.requestId,
                        pngBase64 = base64,
                        widthPx = req.size.widthPx,
                        heightPx = req.size.heightPx,
                        paramSummary = bind.summary,
                        hitMap = session.computeHitMap(funcMap),
                    )
                }
            }
        }
    }
} catch (t: Throwable) {
    t.toErrorResponse(req.requestId, ErrorKind.COMPOSABLE_THREW)
}

private fun handleInteract(
    req: Interact,
    writer: MessageWriter<ServerMessage>,
    sessionCache: SessionCache,
) {
    val session = sessionCache.currentSession()
    if (session == null) {
        writer.send(
            ErrorResponse(
                requestId = req.requestId,
                kind = ErrorKind.RENDER_HOST_FAILED,
                message = "No active interactive session — issue a RenderRequest first",
            )
        )
        return
    }
    val response: ServerMessage = try {
        val base64 = session.interact(req.event)
        RenderResult(
            requestId = req.requestId,
            pngBase64 = base64,
            widthPx = session.widthPx,
            heightPx = session.heightPx,
        )
    } catch (t: Throwable) {
        t.toErrorResponse(req.requestId, ErrorKind.COMPOSABLE_THREW)
    }
    writer.send(response)
}

private fun handleRedefine(
    req: RedefineClasses,
    writer: MessageWriter<ServerMessage>,
    sessionCache: SessionCache,
    instrumentation: java.lang.instrument.Instrumentation?,
) {
    if (instrumentation == null) {
        return writer.send(
            ErrorResponse(
                requestId = req.requestId,
                kind = ErrorKind.HOT_SWAP_FAILED,
                message = "Java Agent not attached — hot swap unavailable",
            )
        )
    }
    val resolver = sessionCache.currentResolver()
        ?: return writer.send(
            ErrorResponse(
                requestId = req.requestId,
                kind = ErrorKind.HOT_SWAP_FAILED,
                message = "No active session — issue a RenderRequest first",
            )
        )

    when (val outcome = HotSwap.redefine(instrumentation, resolver.classLoader, req.classes)) {
        is HotSwap.Outcome.Failed -> {
            writer.send(
                ErrorResponse(
                    requestId = req.requestId,
                    kind = ErrorKind.HOT_SWAP_FAILED,
                    message = outcome.reason,
                    stackTrace = outcome.stackTrace,
                )
            )
            return
        }
        is HotSwap.Outcome.Success -> {
            System.err.println("[renderer] hot-swap OK: " +
                "${outcome.redefinedFqns.size} redefined, " +
                "${outcome.skipped.size} skipped (not loaded yet)")
        }
    }

    // If the caller asked us to re-render after the swap, do so on the
    // SAME classloader so any retained state is preserved. CRITICAL: the
    // wire response carries the RedefineClasses requestId, not the
    // embedded RenderRequest's id — clients correlate against the
    // originating request.
    val rerender = req.rerender
    if (rerender != null) {
        val rendered = performRender(rerender, sessionCache, ComposeSourceMapper())
        writer.send(when (rendered) {
            is RenderResult -> rendered.copy(requestId = req.requestId)
            is ErrorResponse -> rendered.copy(requestId = req.requestId)
            else -> rendered
        })
    } else {
        // Acknowledge with a synthetic result so the caller can correlate.
        writer.send(
            RenderResult(
                requestId = req.requestId,
                pngBase64 = "",
                widthPx = 0,
                heightPx = 0,
            )
        )
    }
}
