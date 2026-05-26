package com.composepreviewpro.ipc

import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Long-lived client for the renderer subprocess. Spawns a JVM running
 * [com.composepreviewpro.renderer.RendererMainKt] once, performs the [Hello]
 * handshake, and from then on streams [RenderRequest]s over the same stdin
 * / stdout pair until the caller closes the subprocess.
 *
 * The old one-shot model (fork-exec per request) was costing us 3 seconds of
 * JVM warm-up on every preview. With this long-lived session, subsequent
 * renders complete in <200ms because the Skiko native libs, Compose
 * runtime, and JIT are already hot.
 *
 * Concurrency: a single [ReentrantLock] serialises [render] calls. We do
 * not attempt to multiplex concurrent renders over one stdio pair because
 * NDJSON framing combined with composition's own non-thread-safety would
 * make that fragile. Callers that need parallelism should keep multiple
 * subprocesses.
 *
 * Crash recovery: if the subprocess dies between requests, [render]
 * transparently restarts it. The caller sees a [Outcome.Crashed] only when
 * the *current* request was in flight at the time of death.
 */
class RendererSubprocess(
    private val launcher: File,
    /** stderr lines from the subprocess are forwarded here (default: null = drop). */
    private val onStderr: ((String) -> Unit)? = null,
) : AutoCloseable {

    sealed interface Outcome {
        data class Success(val result: RenderResult) : Outcome
        data class Errored(val error: ErrorResponse) : Outcome
        data class TimedOut(val requestId: String) : Outcome
        data class Crashed(val reason: String) : Outcome
    }

    private val lock = ReentrantLock()
    private var process: Process? = null
    private var writer: MessageWriter<ClientMessage>? = null
    private var reader: MessageReader<ServerMessage>? = null
    private var stderrPump: Thread? = null

    /**
     * Idempotent. Safe to call repeatedly; only spawns a new process if the
     * previous one has died.
     */
    fun ensureStarted() {
        lock.withLock {
            if (process?.isAlive == true) return
            if (!launcher.exists() || !launcher.canExecute()) {
                throw IllegalStateException(
                    "Renderer launcher not executable at: ${launcher.absolutePath}",
                )
            }
            val p = ProcessBuilder(launcher.absolutePath)
                .redirectErrorStream(false)
                .start()
            val w = MessageWriter(p.outputStream, serializer<ClientMessage>())
            val r = MessageReader(p.inputStream, serializer<ServerMessage>())

            // Pipe stderr to our optional sink so the user can see renderer
            // diagnostics without us blocking on it.
            stderrPump = Thread {
                p.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { onStderr?.invoke(it) }
                }
            }.apply { isDaemon = true; start() }

            // Mandatory handshake — abort cleanly if the renderer prints
            // anything other than Hello before its first response.
            val hello = r.readNext()
            if (hello !is Hello) {
                p.destroy()
                throw IllegalStateException("Expected Hello from renderer, got: $hello")
            }

            process = p
            writer = w
            reader = r
        }
    }

    fun render(request: RenderRequest, timeoutSec: Long = 60): Outcome =
        sendAndAwait(request, request.requestId, timeoutSec)

    /**
     * Send a [RedefineClasses] hot-swap request. Behaviour mirrors
     * [render]: serialises with other in-flight requests via the same
     * lock, waits for the renderer's response, restarts the subprocess
     * on IO error.
     */
    fun redefineClasses(request: RedefineClasses, timeoutSec: Long = 60): Outcome =
        sendAndAwait(request, request.requestId, timeoutSec)

    /**
     * Send an [Interact] event into the renderer's live composition and
     * await the resulting frame. Shorter timeout than render/redefine
     * because there's no compile or classloader work to do.
     */
    fun interact(request: Interact, timeoutSec: Long = 5): Outcome =
        sendAndAwait(request, request.requestId, timeoutSec)

    private fun sendAndAwait(message: ClientMessage, requestId: String, timeoutSec: Long): Outcome {
        lock.withLock {
            try {
                ensureStarted()
            } catch (t: Throwable) {
                return Outcome.Crashed("ensureStarted failed: ${t.message}")
            }
            val w = writer ?: return Outcome.Crashed("writer is null after start")
            val r = reader ?: return Outcome.Crashed("reader is null after start")

            try {
                w.send(message)
            } catch (t: Throwable) {
                forceRestart()
                return Outcome.Crashed("send failed: ${t.message}")
            }

            val response = try {
                readWithTimeout(r, timeoutSec)
            } catch (t: Throwable) {
                forceRestart()
                return Outcome.Crashed("read failed: ${t.message}")
            } ?: return Outcome.TimedOut(requestId)

            return when (response) {
                is RenderResult -> {
                    if (response.requestId != requestId) {
                        forceRestart()
                        Outcome.Crashed(
                            "requestId mismatch: sent $requestId, got ${response.requestId}"
                        )
                    } else {
                        Outcome.Success(response)
                    }
                }
                is ErrorResponse -> Outcome.Errored(response)
                is Hello -> Outcome.Crashed("Unexpected second Hello on the wire")
            }
        }
    }

    override fun close() {
        lock.withLock {
            try {
                writer?.send(Shutdown("client closing"))
            } catch (_: Throwable) { /* best effort */ }
            try { writer?.close() } catch (_: Throwable) {}
            try { reader?.close() } catch (_: Throwable) {}
            process?.let { p ->
                if (p.isAlive) {
                    p.destroy()
                    if (!p.waitFor(2, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                    }
                }
            }
            writer = null
            reader = null
            process = null
            stderrPump?.interrupt()
            stderrPump = null
        }
    }

    private fun forceRestart() {
        try { writer?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        process?.destroyForcibly()
        writer = null
        reader = null
        process = null
    }

    private fun readWithTimeout(reader: MessageReader<ServerMessage>, sec: Long): ServerMessage? {
        var result: ServerMessage? = null
        var error: Throwable? = null
        val thread = Thread {
            try {
                result = reader.readNext()
            } catch (t: Throwable) {
                error = t
            }
        }
        thread.isDaemon = true
        thread.start()
        thread.join(sec * 1000)
        if (thread.isAlive) {
            thread.interrupt()
            return null
        }
        error?.let { throw it }
        return result
    }
}
