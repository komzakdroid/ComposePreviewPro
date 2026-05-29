package com.composepreviewpro.plugin.reload

import com.composepreviewpro.ipc.ErrorKind
import com.composepreviewpro.ipc.RedefineClasses
import com.composepreviewpro.plugin.client.GradleCompiler
import com.composepreviewpro.plugin.client.RendererProcess
import com.composepreviewpro.plugin.service.PreviewService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Watches the project's virtual file system for changes to Kotlin source
 * files. When the file belongs to the module whose composable is currently
 * being previewed, kicks off an incremental compile and — on success —
 * re-issues the last render request.
 *
 * Lifecycle: the [cs] parameter is injected by the platform (IJPL-83,
 * stable since IDEA 2023.2). The platform cancels it automatically when
 * this service unloads — project close, plugin disable, or IDE shutdown
 * — so we no longer implement [com.intellij.openapi.Disposable] or
 * manage a manual [SupervisorJob]. The same `cs` is also passed to
 * [com.intellij.util.messages.MessageBus.connect] so the VFS
 * subscription tears down on the same signal.
 */
@Service(Service.Level.PROJECT)
class HotReloadCoordinator(
    private val project: Project,
    private val cs: CoroutineScope,
) {

    private val previewService: PreviewService
        get() = project.getService(PreviewService::class.java)

    @Volatile
    private var debounceJob: Job? = null

    init {
        thisLogger().info("[ComposePreview] HotReloadCoordinator init for project: ${project.name}")
        try {
            project.messageBus
                .connect(cs)
                .subscribe(VirtualFileManager.VFS_CHANGES, FileChangeBus())
            thisLogger().info("[ComposePreview] VFS_CHANGES subscription installed (parent=cs)")
        } catch (t: Throwable) {
            thisLogger().error("[ComposePreview] Failed to subscribe to VFS_CHANGES", t)
        }
    }

    private inner class FileChangeBus : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            val touched = events.asSequence()
                .filter { it is VFileContentChangeEvent || it is VFileCreateEvent }
                .mapNotNull { it.file }
                .filter { it.extension == "kt" || it.extension == "kts" }
                .toList()
            if (touched.isEmpty()) return
            onKotlinFilesChanged(touched)
        }
    }

    private fun onKotlinFilesChanged(files: List<VirtualFile>) {
        // Bounded log: a refactor across a monorepo can touch thousands
        // of files. Logging "1 file(s): A.kt" is useful; logging
        // "4711 file(s): A.kt, B.kt, …, ZZZ.kt" blows the log allocation
        // budget and never gets read anyway.
        val preview = files.take(5).joinToString(", ") { it.name }
        val ellipsis = if (files.size > 5) ", … (+${files.size - 5} more)" else ""
        thisLogger().info("[ComposePreview] VFS change: ${files.size} .kt file(s): $preview$ellipsis")

        val target = previewService.lastRenderTarget()
        if (target == null) {
            thisLogger().info("[ComposePreview] no lastTarget — ignoring change (user hasn't clicked ▶ yet)")
            return
        }

        val moduleOfChange = ApplicationManager.getApplication().runReadAction<Module?> {
            files.firstNotNullOfOrNull { f -> ModuleUtilCore.findModuleForFile(f, project) }
        }
        if (moduleOfChange == null) {
            thisLogger().info("[ComposePreview] file is outside any module — ignoring")
            return
        }

        val isSameModule = moduleOfChange.name == target.moduleName
        val isUpstream = target.dependsOnModuleName(moduleOfChange.name, project)
        if (!isSameModule && !isUpstream) {
            thisLogger().info("[ComposePreview] change is in module ${moduleOfChange.name} " +
                "but lastTarget is in ${target.moduleName} (not same, not upstream) — ignoring")
            return
        }

        thisLogger().info("[ComposePreview] change is relevant (sameModule=$isSameModule, " +
            "upstream=$isUpstream) — scheduling rebuild for ${target.fqn}")
        scheduleRebuild(target)
    }

    private fun scheduleRebuild(target: PreviewService.RenderTarget) {
        debounceJob?.cancel()
        debounceJob = cs.launch(Dispatchers.IO) {
            // 500ms debounce — coalesces bursts of saves into one compile.
            delay(500)
            triggerCompileThenRerender(target)
        }
    }

    /**
     * Compile via Gradle in a background thread (NOT EDT — Gradle takes
     * 500ms-3s and would freeze the UI). Why Gradle instead of
     * CompilerManager? The IDE's bundled Kotlin compiler version may
     * lag the project's Kotlin/Compose pairing by months; loading our
     * Compose 1.10 (Kotlin 2.3) plugin into the sandbox's bundled
     * Kotlin 2.0 compiler crashes with NoSuchFieldError. Gradle always
     * uses the toolchain pinned in `libs.versions.toml`, so we get a
     * consistent build regardless of IDE version.
     */
    private fun triggerCompileThenRerender(target: PreviewService.RenderTarget) {
        cs.launch(Dispatchers.IO) {
            val module = target.resolveModule(project)
            if (module == null) {
                thisLogger().warn("[ComposePreview] cannot resolve module ${target.moduleName}")
                return@launch
            }
            thisLogger().info("[ComposePreview] starting Gradle compile of module ${module.name}")
            previewService.showRebuilding(target.fqn)

            val compiler = GradleCompiler(project)
            when (val res = compiler.compileModule(module)) {
                is GradleCompiler.Result.Success -> {
                    thisLogger().info("[ComposePreview] gradle compile OK — attempting hot swap")
                    attemptHotSwapOrFallback(target)
                }
                is GradleCompiler.Result.Failed -> {
                    previewService.showError(
                        target = target.fqn,
                        message = "Gradle compile failed (exit ${res.exitCode}). " +
                            "Check the Build tool window for details.",
                        stackTrace = res.output.take(4000),
                    )
                }
                is GradleCompiler.Result.LaunchFailed -> {
                    previewService.showError(
                        target = target.fqn,
                        message = "Could not launch Gradle: ${res.reason}",
                        stackTrace = null,
                    )
                }
            }
        }
    }

    /**
     * Post-compile decision:
     *
     *   • If we have a primed snapshot AND non-empty diff → ship the
     *     changed class bytes via [RedefineClasses]. The renderer hot-swaps
     *     in place; Compose's slot table preserves state.
     *
     *   • If hot swap fails (HOT_SWAP_FAILED — typically a structural
     *     change such as added method/field) → fall back to a normal
     *     [com.composepreviewpro.ipc.RenderRequest] which recreates the
     *     URLClassLoader. State is lost but the preview still updates.
     *
     *   • If no snapshot is primed (first compile after target change) →
     *     just do a full re-render; the success path in PreviewService
     *     will prime the snapshot for next time.
     */
    private fun attemptHotSwapOrFallback(target: PreviewService.RenderTarget) {
        cs.launch(Dispatchers.IO) {
            if (!previewService.isSnapshotPrimed()) {
                thisLogger().info("[ComposePreview] snapshot not primed → full rerender")
                previewService.rerenderLast()
                return@launch
            }

            val roots = previewService.moduleClassOutputRoots(target)
            val changed = previewService.classpathSnapshot().diff(roots)
            if (changed.isEmpty) {
                thisLogger().info("[ComposePreview] no .class diff after compile → full rerender")
                previewService.rerenderLast()
                return@launch
            }

            val rerenderRequest = previewService.buildRenderRequest(target)
            if (rerenderRequest == null) {
                thisLogger().warn("[ComposePreview] cannot build rerender request → full rerender")
                previewService.rerenderLast()
                return@launch
            }

            thisLogger().info("[ComposePreview] hot-swap: ${changed.classes.size} class(es) changed")
            val redefineRequest = RedefineClasses(
                requestId = UUID.randomUUID().toString(),
                classes = changed.classes,
                rerender = rerenderRequest,
            )

            val rendererService = project.getService(RendererProcess::class.java)
            when (val outcome = rendererService.redefineClasses(redefineRequest)) {
                is RendererProcess.Outcome.Success -> {
                    thisLogger().info("[ComposePreview] hot-swap SUCCESS — state preserved")
                    val r = outcome.result
                    ApplicationManager.getApplication().invokeLater(
                        {
                            // Reuse PreviewService's panel via showImage helper.
                            previewService.showImageDirect(target.fqn, r.pngBase64, r.widthPx, r.heightPx)
                        },
                        ModalityState.defaultModalityState(),
                        project.disposed,
                    )
                }
                is RendererProcess.Outcome.Errored -> {
                    if (outcome.error.kind == ErrorKind.HOT_SWAP_FAILED) {
                        thisLogger().info(
                            "[ComposePreview] hot-swap rejected (${outcome.error.message}) → " +
                            "falling back to full URLClassLoader recreation"
                        )
                        previewService.rerenderLast()
                    } else {
                        thisLogger().warn("[ComposePreview] hot-swap errored: ${outcome.error.kind}")
                        previewService.showError(
                            target.fqn,
                            "${outcome.error.kind}: ${outcome.error.message}",
                            outcome.error.stackTrace,
                        )
                    }
                }
                is RendererProcess.Outcome.LaunchFailed -> {
                    thisLogger().error("[ComposePreview] hot-swap renderer launch failed: ${outcome.reason}")
                    previewService.rerenderLast()
                }
            }
        }
    }
}
