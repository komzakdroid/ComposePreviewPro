package com.composepreviewpro.plugin.client

import com.composepreviewpro.ipc.ErrorKind
import com.composepreviewpro.ipc.ErrorResponse
import com.composepreviewpro.ipc.Interact
import com.composepreviewpro.ipc.RedefineClasses
import com.composepreviewpro.ipc.RenderRequest
import com.composepreviewpro.ipc.RenderResult
import com.composepreviewpro.ipc.RendererSubprocess
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Project-scoped, long-lived renderer subprocess.
 *
 * Old design (deprecated): a singleton `object` that fork/exec'd a fresh
 * JVM for every render request. That cost ~3 seconds of JVM startup per
 * preview and burned the user's CPU on a permanent treadmill of Skiko
 * native-library loading.
 *
 * New design: a per-project service holding a single [RendererSubprocess]
 * instance. The JVM is spawned lazily on the first render and reused for
 * every subsequent request — typical render latency drops to <100ms once
 * the Compose/Skiko caches are warm. On project close we send a clean
 * Shutdown and reap the process.
 *
 * Crash recovery: [RendererSubprocess.render] transparently restarts the
 * subprocess if it died between requests, so a single broken render does
 * not poison the session.
 */
@Service(Service.Level.PROJECT)
class RendererProcess(private val project: Project) : Disposable {

    sealed interface Outcome {
        data class Success(val result: RenderResult) : Outcome
        data class Errored(val error: ErrorResponse) : Outcome
        data class LaunchFailed(val reason: String) : Outcome
    }

    @Volatile
    private var subprocess: RendererSubprocess? = null

    /**
     * Hot-swap a set of class bytecodes in the running renderer and
     * (optionally) re-render the previously displayed composable on the
     * same classloader. Falls back to [Outcome.LaunchFailed] if the agent
     * is not attached or the subprocess has not received its first
     * RenderRequest yet (no classloader to redefine against).
     */
    fun redefineClasses(request: RedefineClasses, timeoutSec: Long = 60): Outcome {
        val sub = try {
            getOrCreateSubprocess()
        } catch (t: Throwable) {
            thisLogger().error("[ComposePreview] Renderer launch failed", t)
            return Outcome.LaunchFailed(t.message ?: t.toString())
        }
        return when (val outcome = sub.redefineClasses(request, timeoutSec)) {
            is RendererSubprocess.Outcome.Success -> Outcome.Success(outcome.result)
            is RendererSubprocess.Outcome.Errored -> Outcome.Errored(outcome.error)
            is RendererSubprocess.Outcome.TimedOut -> Outcome.Errored(
                ErrorResponse(
                    requestId = request.requestId,
                    kind = ErrorKind.RENDER_HOST_FAILED,
                    message = "Renderer did not respond within ${timeoutSec}s",
                    stackTrace = null,
                )
            )
            is RendererSubprocess.Outcome.Crashed -> Outcome.LaunchFailed(
                "Renderer subprocess crashed: ${outcome.reason}"
            )
        }
    }

    /**
     * Send a single pointer event into the renderer's live composition.
     * Used by the interactive preview path so clicks on the panel
     * actually fire Button.onClick lambdas in the rendered composable.
     */
    fun interact(request: Interact, timeoutSec: Long = 5): Outcome {
        val sub = try {
            getOrCreateSubprocess()
        } catch (t: Throwable) {
            return Outcome.LaunchFailed(t.message ?: t.toString())
        }
        return when (val outcome = sub.interact(request, timeoutSec)) {
            is RendererSubprocess.Outcome.Success -> Outcome.Success(outcome.result)
            is RendererSubprocess.Outcome.Errored -> Outcome.Errored(outcome.error)
            is RendererSubprocess.Outcome.TimedOut -> Outcome.Errored(
                ErrorResponse(
                    requestId = request.requestId,
                    kind = ErrorKind.RENDER_HOST_FAILED,
                    message = "Renderer did not respond to Interact within ${timeoutSec}s",
                    stackTrace = null,
                )
            )
            is RendererSubprocess.Outcome.Crashed -> Outcome.LaunchFailed(
                "Renderer crashed during Interact: ${outcome.reason}"
            )
        }
    }

    fun render(request: RenderRequest, timeoutSec: Long = 60): Outcome {
        val sub = try {
            getOrCreateSubprocess()
        } catch (t: Throwable) {
            thisLogger().error("[ComposePreview] Renderer launch failed", t)
            return Outcome.LaunchFailed(t.message ?: t.toString())
        }

        return when (val outcome = sub.render(request, timeoutSec)) {
            is RendererSubprocess.Outcome.Success -> Outcome.Success(outcome.result)
            is RendererSubprocess.Outcome.Errored -> Outcome.Errored(outcome.error)
            is RendererSubprocess.Outcome.TimedOut -> Outcome.Errored(
                ErrorResponse(
                    requestId = request.requestId,
                    kind = ErrorKind.RENDER_HOST_FAILED,
                    message = "Renderer did not respond within ${timeoutSec}s",
                    stackTrace = null,
                )
            )
            is RendererSubprocess.Outcome.Crashed -> Outcome.LaunchFailed(
                "Renderer subprocess crashed: ${outcome.reason}"
            )
        }
    }

    override fun dispose() {
        thisLogger().info("[ComposePreview] Disposing renderer subprocess")
        try {
            subprocess?.close()
        } catch (t: Throwable) {
            thisLogger().warn("[ComposePreview] Subprocess close failed", t)
        }
        subprocess = null
    }

    @Synchronized
    private fun getOrCreateSubprocess(): RendererSubprocess {
        subprocess?.let { return it }

        val launcher = resolveLauncher()
            ?: throw IllegalStateException(
                "Renderer launcher not found. Set system property " +
                "'composepreviewpro.renderer.home' or env " +
                "COMPOSE_PREVIEW_RENDERER_HOME to a directory containing bin/renderer."
            )
        thisLogger().info("[ComposePreview] Launching renderer subprocess: $launcher")

        val sub = RendererSubprocess(
            launcher = launcher,
            onStderr = { line -> thisLogger().info("[renderer-stderr] $line") },
        )
        sub.ensureStarted()
        thisLogger().info("[ComposePreview] Renderer subprocess up — protocol handshake OK")
        subprocess = sub
        return sub
    }

    private fun resolveLauncher(): File? {
        val candidates = mutableListOf<File>()

        System.getProperty("composepreviewpro.renderer.home")?.let {
            candidates += File(it, "bin/renderer")
        }
        System.getenv("COMPOSE_PREVIEW_RENDERER_HOME")?.let {
            candidates += File(it, "bin/renderer")
        }
        // Common dev locations relative to user.dir.
        val cwd = File(System.getProperty("user.dir"))
        candidates += File(cwd, "renderer/build/install/renderer/bin/renderer")
        candidates += File(cwd, "../ComposePreviewPro/renderer/build/install/renderer/bin/renderer")

        return candidates.firstOrNull { it.exists() && it.canExecute() }
    }
}
