package com.composepreviewpro.ipc

import kotlinx.serialization.serializer
import java.io.File
import java.util.ArrayDeque
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
     * Bounded ring of the most recent stderr lines from the current
     * subprocess. When startup fails, the renderer's JVM-level error
     * (bad -javaagent path, missing java, OOM, classpath fault) lands
     * here, and we splice it into the thrown exception so the failure is
     * self-describing instead of surfacing as a cryptic deserialization
     * error from a half-spoken protocol stream. Guarded by its own
     * monitor because the stderr pump thread writes while the launch
     * thread reads.
     */
    private val stderrTail = ArrayDeque<String>()

    private fun recordStderr(line: String) {
        synchronized(stderrTail) {
            stderrTail.addLast(line)
            while (stderrTail.size > STDERR_TAIL_MAX) stderrTail.removeFirst()
        }
    }

    private fun stderrTailSnapshot(): String =
        synchronized(stderrTail) {
            if (stderrTail.isEmpty()) "(no stderr output)" else stderrTail.joinToString("\n")
        }

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
            synchronized(stderrTail) { stderrTail.clear() }
            val pb = ProcessBuilder(launcher.absolutePath)
                .redirectErrorStream(false)
            // Pin the renderer to the SAME JVM as this process. Inside the
            // IDE that's the bundled JBR (Java 21); the renderer + agent are
            // compiled for 21, so relying on the user's ambient `java` /
            // JAVA_HOME is a latent UnsupportedClassVersionError (a Java 17
            // on PATH fails with "class file version 65.0 … up to 61.0").
            // The Gradle start script honours JAVA_HOME over PATH, so
            // exporting our own java.home makes the launch deterministic
            // regardless of the spawning environment.
            System.getProperty("java.home")?.let { pb.environment()["JAVA_HOME"] = it }
            val p = pb.start()
            val w = MessageWriter(p.outputStream, serializer<ClientMessage>())
            val r = MessageReader(p.inputStream, serializer<ServerMessage>())

            // Pipe stderr to our optional sink so the user can see renderer
            // diagnostics, AND retain a bounded tail for failure reporting.
            stderrPump = Thread {
                p.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        recordStderr(line)
                        onStderr?.invoke(line)
                    }
                }
            }.apply { isDaemon = true; start() }

            // Mandatory handshake. The first line on stdout MUST be a Hello.
            // Anything else — a deserialization fault (the renderer printed
            // non-protocol text and died), EOF (the JVM exited before
            // speaking), or a non-Hello message — means startup failed. We
            // diagnose it richly rather than letting a raw
            // SerializationException or "got null" bubble up: wait briefly
            // for the process to report its exit code, and splice in the
            // captured stderr tail so the true cause (e.g. a bad -javaagent
            // path) is visible at the call site.
            val hello: ServerMessage? = try {
                r.readNext()
            } catch (t: Throwable) {
                p.destroyForcibly()
                throw IllegalStateException(startupFailureMessage(p, "could not parse handshake (${t.message})"), t)
            }
            if (hello !is Hello) {
                p.destroyForcibly()
                val detail = if (hello == null) "stdout closed before handshake" else "expected Hello, got $hello"
                throw IllegalStateException(startupFailureMessage(p, detail))
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

    /**
     * Build a self-describing startup-failure message: the process exit
     * code (waited for briefly, since the JVM may still be flushing
     * stderr when stdout already hit EOF) plus the captured stderr tail.
     * Turns "Expected JsonObject … JSON input: Error" into something a
     * user can act on, e.g. "Error opening zip file or JAR manifest
     * missing : …/Application".
     */
    private fun startupFailureMessage(p: Process, detail: String): String {
        val exited = try { p.waitFor(2, TimeUnit.SECONDS) } catch (_: Throwable) { false }
        val exitPart =
            if (exited) "renderer process exited with code ${runCatching { p.exitValue() }.getOrNull()}"
            else "renderer process still alive but handshake failed"
        return buildString {
            append("Renderer failed to start — $detail; $exitPart.\n")
            append("--- renderer stderr ---\n")
            append(stderrTailSnapshot())
        }
    }

    private companion object {
        /** Cap on retained stderr lines for failure diagnostics. */
        const val STDERR_TAIL_MAX = 50
    }

    /**
     * Read a single response with a timeout. The reader runs on a daemon
     * thread so the caller's coroutine can give up after [sec] seconds
     * even if the subprocess has died mid-message and its stdout pipe is
     * stuck waiting for a byte that will never arrive.
     *
     * The previous implementation only called `thread.interrupt()` on
     * timeout. `Thread.interrupt()` does NOT unblock a thread parked
     * inside `BufferedReader.readLine()` — that's a blocking I/O syscall
     * that ignores the Java interrupt flag. The daemon thread stayed
     * alive until JVM exit, holding a reference to the reader (and
     * therefore to the dead subprocess's pipe handle).
     *
     * The robust fix on timeout is to close the underlying stream from
     * the OUTSIDE — that makes the read throw `IOException`, unparks the
     * thread, and lets it terminate cleanly. We also explicitly close
     * the reader on the success path so a thread that became blocked on
     * a later readLine() (we already have what we needed) doesn't linger.
     */
    private fun readWithTimeout(reader: MessageReader<ServerMessage>, sec: Long): ServerMessage? {
        var result: ServerMessage? = null
        var error: Throwable? = null
        val thread = Thread({
            try {
                result = reader.readNext()
            } catch (t: Throwable) {
                error = t
            }
        }, "renderer-stdin-reader")
        thread.isDaemon = true
        thread.start()
        thread.join(sec * 1000)
        if (thread.isAlive) {
            // Close the stream from this thread; that unblocks the
            // daemon's BufferedReader.readLine() with IOException so it
            // can terminate. Without this the daemon survived as long as
            // the JVM did.
            try { reader.close() } catch (_: Throwable) {}
            thread.interrupt()
            // Give the unblocked thread a moment to clean up; not strictly
            // necessary for correctness but it makes thread dumps cleaner.
            thread.join(200)
            return null
        }
        error?.let { throw it }
        return result
    }
}
