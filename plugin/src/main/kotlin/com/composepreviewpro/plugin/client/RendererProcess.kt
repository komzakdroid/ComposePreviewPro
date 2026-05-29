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

    /**
     * Search order, most-specific first:
     *
     *   1. Explicit override — system property or env var (developer
     *      tooling, CI, custom installs).
     *   2. **The renderer bundled inside this plugin's install directory.**
     *      Resolved via our own class's [java.security.CodeSource] — the
     *      IDE always loads plugin JARs from a per-plugin `lib/` folder,
     *      so walking two parents up from the JAR gives the plugin root.
     *      Pure JVM stdlib, no IntelliJ Platform internal API needed.
     *   3. Dev locations relative to `user.dir` — for running an
     *      unpackaged plugin directly out of the source tree.
     *
     * The first match wins. If none exists, [getOrCreateSubprocess]
     * throws with a self-describing error so the user immediately knows
     * what's missing.
     */
    private fun resolveLauncher(): File? {
        val candidates = mutableListOf<File>()

        System.getProperty("composepreviewpro.renderer.home")?.let {
            candidates += File(it, "bin/renderer")
        }
        System.getenv("COMPOSE_PREVIEW_RENDERER_HOME")?.let {
            candidates += File(it, "bin/renderer")
        }

        // Plugin install directory — the production case.
        pluginInstallDir()?.let { dir ->
            candidates += File(dir, "renderer/bin/renderer")
        }

        // Common dev locations relative to user.dir.
        val cwd = File(System.getProperty("user.dir"))
        candidates += File(cwd, "renderer/build/install/renderer/bin/renderer")
        candidates += File(cwd, "../ComposePreviewPro/renderer/build/install/renderer/bin/renderer")

        thisLogger().info("[ComposePreview] renderer launcher candidates:\n  " +
            candidates.joinToString("\n  ") { "${it.absolutePath} exists=${it.exists()}" })

        // First check: take any launcher that's already executable.
        candidates.firstOrNull { it.exists() && it.canExecute() }?.let { return it }

        // Fallback: a launcher was extracted from the plugin ZIP without
        // the Unix execute bit (some ZIP extractors drop file attrs).
        // If we find it sitting on disk read-only, restore the bit
        // ourselves — owner-only is plenty since the IDE runs as the
        // user. Without this rescue, the plugin reports "launcher not
        // found" even though the file is right there.
        candidates.firstOrNull { it.exists() }?.let { launcher ->
            val ok = launcher.setExecutable(true, /* ownerOnly = */ false)
            thisLogger().warn(
                "[ComposePreview] launcher ${launcher.absolutePath} was not executable; " +
                    "setExecutable=true returned $ok"
            )
            if (launcher.canExecute()) return launcher
        }
        return null
    }

    /**
     * Resolve the on-disk directory IntelliJ unpacked this plugin into.
     * For sandbox runs (./gradlew :plugin:runIde) that's
     * `plugin/build/idea-sandbox/.../plugins-prepared/<pluginName>/`.
     * For a real user install it's somewhere under
     * `~/Library/Application Support/<IDE>/plugins/<pluginName>/` on
     * macOS, or the platform-equivalent path.
     *
     * We resolve it by inspecting our own [Class.getProtectionDomain]
     * `CodeSource`. The IDE loads plugin classes from
     * `<plugin-root>/lib/<jar>.jar`, so the CodeSource location is that
     * JAR, and walking two `parentFile`s up gives the plugin root.
     * This avoids `PluginManagerCore.getPlugin(...)` and
     * `PluginManager.findEnabledPlugin(...)`, both of which are marked
     * `@ApiStatus.Internal` and flagged by the Marketplace verifier.
     */
    private fun pluginInstallDir(): File? {
        return try {
            val source = RendererProcess::class.java.protectionDomain?.codeSource ?: return null
            val location = source.location ?: return null
            // location is a file: URL pointing at .../lib/<plugin>.jar
            val jarFile = File(location.toURI())
            val libDir = jarFile.parentFile ?: return null
            libDir.parentFile
        } catch (t: Throwable) {
            thisLogger().warn(
                "[ComposePreview] could not resolve plugin install dir from CodeSource", t
            )
            null
        }
    }
}
