package com.composepreviewpro.plugin.service

import com.composepreviewpro.ipc.ClasspathEntries
import com.composepreviewpro.ipc.ComposableId
import com.composepreviewpro.ipc.ErrorResponse
import com.composepreviewpro.ipc.HitMap
import com.composepreviewpro.ipc.InputEvent
import com.composepreviewpro.ipc.Interact
import com.composepreviewpro.ipc.ParamInfo
import com.composepreviewpro.ipc.PreviewTheme
import com.composepreviewpro.ipc.RedefineClasses
import com.composepreviewpro.ipc.RenderRequest
import com.composepreviewpro.ipc.RenderResult
import com.composepreviewpro.ipc.RenderSize
import com.composepreviewpro.plugin.client.ClasspathSnapshot
import com.composepreviewpro.plugin.client.RendererProcess
import com.composepreviewpro.plugin.inspector.CallExpressionInspector
import com.composepreviewpro.plugin.nav.SourceNavigator
import com.composepreviewpro.plugin.reload.HotReloadCoordinator
import com.composepreviewpro.plugin.toolwindow.PreviewPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.io.File
import java.util.UUID

/**
 * Project-scoped service that orchestrates a render request from the gutter
 * icon click through to the tool-window display.
 *
 * Responsibilities (post-hot-reload):
 *   1. Resolve the user's module classpath via IntelliJ's OrderEnumerator
 *   2. Spawn the [RendererProcess] and exchange IPC messages
 *   3. Push the resulting PNG / error to the [PreviewPanel]
 *   4. Remember the *last* render so [HotReloadCoordinator] can re-issue it
 *      automatically after a source change is recompiled
 *
 * The service is the single source of truth for "what is currently shown
 * in the preview"; the coordinator and the panel are both passive
 * collaborators that this service drives.
 */
@Service(Service.Level.PROJECT)
class PreviewService(private val project: Project) {

    /**
     * Snapshot of the inputs needed to reproduce a render. Held as plain
     * data — no PSI references — because PSI elements become invalid as
     * soon as the underlying file is edited.
     */
    data class RenderTarget(
        val fqn: String,
        val moduleName: String,
        val size: RenderSize = RenderSize(),
        val theme: PreviewTheme = PreviewTheme.LIGHT,
        /** User-typed argument overrides, name → string value. */
        val argOverrides: Map<String, String> = emptyMap(),
        /** If true, the renderer consults Claude for String mocks. */
        val useAiMocks: Boolean = false,
    ) {
        fun resolveModule(project: Project): Module? =
            ModuleManager.getInstance(project).findModuleByName(moduleName)

        /**
         * True iff the named module is part of [moduleName]'s compile
         * classpath — covers the case where the user edited a shared
         * library that the previewed module depends on.
         */
        fun dependsOnModuleName(otherModuleName: String, project: Project): Boolean {
            val module = resolveModule(project) ?: return false
            val rootMgr = ModuleRootManager.getInstance(module)
            return rootMgr.dependencies.any { it.name == otherModuleName }
        }
    }

    @Volatile
    private var panel: PreviewPanel? = null

    @Volatile
    private var lastTarget: RenderTarget? = null

    @Volatile
    var multiFrameMode: Boolean = false
        private set

    @Volatile
    var liveUiMode: Boolean = false
        private set

    /**
     * Snapshot of the user's compile-output .class files at the time of
     * the last successful render. Used by [HotReloadCoordinator] to
     * compute the minimal set of bytecodes to ship to the renderer for
     * in-place [RedefineClasses] swaps. Reset whenever [lastTarget]
     * changes (different composable selected → different render context).
     */
    private val snapshot = ClasspathSnapshot()
    @Volatile
    private var snapshotPrimed = false

    fun attachPanel(panel: PreviewPanel) {
        this.panel = panel
        // Touching HotReloadCoordinator here forces the platform to
        // instantiate it. The coordinator subscribes to VFS events in its
        // own init block. We swallow any initialization error here so a
        // broken coordinator does not prevent the (already-attached) panel
        // from rendering normal preview clicks.
        try {
            project.getService(HotReloadCoordinator::class.java)
        } catch (t: Throwable) {
            thisLogger().error("Hot reload coordinator failed to initialise", t)
        }
    }

    fun lastRenderTarget(): RenderTarget? = lastTarget

    /** Initial render path: called when the user clicks the gutter icon. */
    fun renderComposable(function: KtNamedFunction, fqn: String) {
        thisLogger().info("[ComposePreview] renderComposable: $fqn")
        val module = ModuleUtilCore.findModuleForPsiElement(function)
            ?: return reportError("No module found for ${function.name}")
        val target = RenderTarget(fqn = fqn, moduleName = module.name)
        val targetChanged = lastTarget?.fqn != target.fqn
        lastTarget = target
        if (targetChanged) {
            // Reset the snapshot — a new target may pull in different
            // classes, so the diff baseline must start fresh.
            snapshotPrimed = false
        }
        thisLogger().info("[ComposePreview] lastTarget set: $fqn in module ${module.name}")
        executeRender(target)
    }

    /**
     * Build a [RenderRequest] for [target] using the current classpath.
     * Exposed for [HotReloadCoordinator] to embed inside a
     * [RedefineClasses] payload.
     */
    fun buildRenderRequest(target: RenderTarget): RenderRequest? {
        val module = target.resolveModule(project) ?: return null
        val classpathPaths = OrderEnumerator.orderEntries(module)
            .recursively()
            .productionOnly()
            .pathsList
            .pathList
            .map { it.toString() }
        if (classpathPaths.isEmpty()) return null
        return RenderRequest(
            requestId = UUID.randomUUID().toString(),
            target = ComposableId(target.fqn),
            classpath = ClasspathEntries(classpathPaths),
            size = target.size,
            theme = target.theme,
            argOverrides = target.argOverrides,
            useAiMocks = target.useAiMocks,
        )
    }

    fun setUseAiMocks(enabled: Boolean) {
        val current = lastTarget ?: return
        if (current.useAiMocks == enabled) return
        val updated = current.copy(useAiMocks = enabled)
        lastTarget = updated
        thisLogger().info("[ComposePreview] AI mocks = $enabled")
        executeRender(updated, freshClassLoader = false)
    }

    /** Apply a single parameter override and re-render. */
    fun setArgOverride(name: String, value: String) {
        val current = lastTarget ?: return
        val updated = current.copy(argOverrides = current.argOverrides + (name to value))
        lastTarget = updated
        thisLogger().info("[ComposePreview] override $name=$value → re-render")
        executeRender(updated, freshClassLoader = false)
    }

    /** Clear all overrides, return params to their auto-mocked defaults. */
    fun clearArgOverrides() {
        val current = lastTarget ?: return
        if (current.argOverrides.isEmpty()) return
        val updated = current.copy(argOverrides = emptyMap())
        lastTarget = updated
        thisLogger().info("[ComposePreview] cleared all overrides → re-render")
        executeRender(updated, freshClassLoader = false)
    }

    /**
     * Navigate the IDE editor to the source declaration of the
     * currently-previewed composable. No-op if nothing has been
     * rendered yet or the FQN cannot be resolved.
     */
    fun navigateToCurrentSource(): Boolean {
        val target = lastTarget ?: return false
        return SourceNavigator(project).navigate(target.fqn)
    }

    /**
     * Per-element navigation: given a click in scene-pixel coordinates,
     * find the smallest hit-map entry that contains the point, and
     * navigate to that node's source file:line. Falls back to
     * [navigateToCurrentSource] if no hit map or no hit was found —
     * better to land somewhere useful than show a popup error.
     */
    fun navigateAtPoint(sceneX: Int, sceneY: Int): Boolean {
        // Two-stage resolution: (1) find the smallest source-mapped hit
        // by bounds, (2) refine its approximate line via PSI by
        // locating the nearest actual KtCallExpression. Stage 2 cures
        // the off-by-a-few-lines noise the bytecode mapping leaves
        // behind.
        val inspector = CallExpressionInspector(project)
        val containing = currentHitMap.entries.filter { it.contains(sceneX, sceneY) }
        for (hit in containing) {
            val file = hit.sourceFile ?: continue
            val line = hit.sourceLine ?: continue
            val refined = inspector.callAtLine(file, line)
            if (refined != null) {
                thisLogger().info("[ComposePreview] navigate @($sceneX,$sceneY) → " +
                    "${refined.functionName} at ${refined.file}:${refined.line}")
                return SourceNavigator(project).navigateToFileLine(refined.file, refined.line)
            }
        }
        // Fall back to raw line if PSI didn't find a call — still
        // better than landing nowhere.
        val raw = containing.firstOrNull { it.sourceFile != null && it.sourceLine != null }
        if (raw?.sourceFile != null && raw.sourceLine != null) {
            return SourceNavigator(project).navigateToFileLine(raw.sourceFile!!, raw.sourceLine!!)
        }
        thisLogger().info("[ComposePreview] no source-annotated hit at ($sceneX,$sceneY) — falling back to top-level")
        return navigateToCurrentSource()
    }

    @Volatile
    private var currentHitMap: HitMap = HitMap.EMPTY

    @Volatile
    private var selectedCall: CallExpressionInspector.CallInfo? = null

    /**
     * When the user has explicitly selected an element via Cmd-click or
     * the Inspect toolbar action, every subsequent render keeps the
     * Parameters panel showing THAT element's arguments. Without this
     * flag, the next interactive click would re-render and overwrite
     * the panel with the function-level summary, making the editor
     * feel broken.
     */
    @Volatile
    private var elementSelectionActive: Boolean = false

    fun isElementSelectionActive(): Boolean = elementSelectionActive

    fun clearElementSelection() {
        if (!elementSelectionActive) return
        elementSelectionActive = false
        selectedCall = null
        thisLogger().info("[ComposePreview] element selection cleared")
        lastTarget?.let { executeRender(it, freshClassLoader = false) }
    }

    fun setHitMap(hitMap: HitMap) {
        currentHitMap = hitMap
        thisLogger().info("[ComposePreview] hit map updated: ${hitMap.entries.size} entries")
    }

    /**
     * Select the rendered element under the given scene coordinates,
     * resolve it to a Kotlin source call expression, and push its
     * arguments to the panel for live editing. The most-specific
     * source-mapped hit wins.
     */
    fun selectAtPoint(sceneX: Int, sceneY: Int) {
        // Walk every hit containing the click, smallest to largest.
        // Try each one — the first that resolves to a Kotlin call
        // expression wins. This is more robust than picking the
        // single smallest hit, because positional matching sometimes
        // assigns the same source line to multiple groups and the
        // smallest one might point at a line where no call lives.
        val containing = currentHitMap.entries
            .filter { it.contains(sceneX, sceneY) }
        thisLogger().info("[ComposePreview] selectAtPoint @($sceneX,$sceneY): " +
            "${containing.size} hits containing the point, " +
            "${containing.count { it.sourceFile != null }} with source info")

        val inspector = CallExpressionInspector(project)
        for (hit in containing) {
            val file = hit.sourceFile
            val line = hit.sourceLine
            if (file == null || line == null) continue
            val call = inspector.callAtLine(file, line) ?: continue
            selectedCall = call
            elementSelectionActive = true
            thisLogger().info("[ComposePreview] selected: ${call.functionName} " +
                "at ${call.file}:${call.line}  (${call.args.size} args)")
            val params = buildParamView(call)
            ApplicationManager.getApplication().invokeLater {
                panel?.showSelectedElement(call.functionName, params, call.file, call.line)
            }
            return
        }
        thisLogger().info("[ComposePreview] selectAtPoint: no resolvable element under cursor")
    }

    /**
     * Translates a [CallExpressionInspector.CallInfo] into the
     * [ParamInfo] list shown in the side panel.
     *
     * Two filtering rules baked in:
     *
     *   1. Drop the trailing **content lambda** (`{ … }`). Showing its
     *      raw body as a single-line string was a UX disaster — it dumped
     *      `{ LazyColumn { item { Text(text =` etc. on one line. The
     *      inner composables are individually clickable on the canvas
     *      anyway, so editing them by name from the parent panel adds
     *      nothing.
     *   2. Drop other `{ … }` lambdas (e.g. `onClick`). Editing them via
     *      a one-line text field never worked — the user can edit them
     *      directly in source.
     *
     * Everything else (primitives, enums, modifier chains, references
     * like `MaterialTheme.colorScheme.background`) stays editable.
     */
    private fun buildParamView(call: CallExpressionInspector.CallInfo): List<ParamInfo> {
        return call.args.mapNotNull { arg ->
            val text = arg.currentValueText.trimStart()
            if (text.startsWith("{")) return@mapNotNull null
            // Editable iff we have *some* name — either explicitly named at
            // the call site, or resolved from the declaration. The
            // writer in updateArgument() will dispatch on which kind
            // we have and rewrite as a named argument when needed.
            val displayName = arg.name ?: arg.resolvedName ?: "<positional>"
            val editable = arg.name != null || arg.resolvedName != null
            ParamInfo(
                name = displayName,
                typeName = inferLooseType(arg.currentValueText),
                currentValue = arg.currentValueText,
                editable = editable,
            )
        }
    }

    /**
     * Commit a value edit from the Parameters panel back into the
     * user's source code via PSI. After the file is saved, our hot
     * reload pipeline picks it up automatically.
     *
     * CRITICAL: we ALWAYS re-resolve the call from disk before each
     * write. The previously-captured [CallExpressionInspector.CallInfo]
     * holds direct PSI references (KtValueArgument). Those references
     * are invalidated the moment any prior edit caused IntelliJ to
     * reparse the file (which happens on every save → hot-reload
     * cycle). Using a stale reference silently no-ops the replace()
     * call, so the user's second edit appears to do nothing.
     *
     * Re-resolving every time is cheap (one tree walk over one file)
     * and bullet-proof against PSI invalidation.
     */
    fun setSelectedArgValue(argName: String, newValueText: String): Boolean {
        val stale = selectedCall ?: return false
        val inspector = CallExpressionInspector(project)
        val fresh = inspector.callAtLine(stale.file, stale.line) ?: run {
            thisLogger().warn("[ComposePreview] setSelectedArgValue: could not re-resolve " +
                "${stale.file}:${stale.line} (file changed underneath?)")
            return false
        }
        val ok = inspector.updateArgument(fresh, argName, newValueText)
        if (ok) {
            // Pull the post-edit version one more time so the panel's
            // next render reads the JUST-WRITTEN text, not the old value.
            selectedCall = inspector.callAtLine(fresh.file, fresh.line) ?: fresh
            thisLogger().info("[ComposePreview] selectedArg $argName ← $newValueText  " +
                "(re-resolved before write)")
        } else {
            thisLogger().warn("[ComposePreview] setSelectedArgValue: PSI write reported failure " +
                "for $argName ← $newValueText")
        }
        return ok
    }

    /**
     * Cheap textual classification — good enough to show "Color" /
     * "Modifier" / "String" / "Int" / "expr" next to the field. Real
     * type resolution would require Kotlin Analysis API; not worth the
     * extra complexity for a panel hint.
     */
    private fun inferLooseType(text: String): String = when {
        text.startsWith("\"") -> "String"
        text == "true" || text == "false" -> "Boolean"
        text.toIntOrNull() != null -> "Int"
        text.toDoubleOrNull() != null -> "Double"
        text.startsWith("Modifier") -> "Modifier"
        text.contains("Color.") || text.startsWith("Color(") -> "Color"
        text.contains(".dp") -> "Dp"
        text.contains(".sp") -> "TextUnit"
        text.startsWith("{") -> "Lambda"
        else -> "expr"
    }

    /**
     * Forward a pointer event from the panel into the renderer's live
     * composition. Runs the IPC call on a background thread and pushes
     * the new PNG back to the panel on EDT.
     */
    fun sendInteraction(event: InputEvent) {
        val target = lastTarget ?: return
        ProgressManager.runInBackground(project, "Interacting with preview") {
            val rendererService = project.getService(RendererProcess::class.java)
            val request = Interact(requestId = UUID.randomUUID().toString(), event = event)
            when (val outcome = rendererService.interact(request)) {
                is RendererProcess.Outcome.Success -> {
                    val r = outcome.result
                    ApplicationManager.getApplication().invokeLater {
                        panel?.showImage(target.fqn, r.pngBase64, r.widthPx, r.heightPx)
                    }
                }
                is RendererProcess.Outcome.Errored -> {
                    thisLogger().warn("[ComposePreview] interact errored: ${outcome.error.message}")
                }
                is RendererProcess.Outcome.LaunchFailed -> {
                    thisLogger().warn("[ComposePreview] interact launch failed: ${outcome.reason}")
                }
            }
        }
    }

    /**
     * Update the render parameters (size, theme) on the last target and
     * re-render. Called from the [PreviewPanel] toolbar.
     */
    fun updateRenderParams(size: RenderSize? = null, theme: PreviewTheme? = null) {
        val current = lastTarget ?: return
        val updated = current.copy(
            size = size ?: current.size,
            theme = theme ?: current.theme,
        )
        lastTarget = updated
        thisLogger().info("[ComposePreview] re-render with size=${updated.size}, theme=${updated.theme}")
        executeRender(updated, freshClassLoader = false)
    }

    /**
     * Collect the on-disk roots that contain the user's compiled
     * `.class` files for the target module *and its transitive module
     * dependencies*. Used as input to [snapshot].
     *
     * Why not just `CompilerModuleExtension.compilerOutputPath`?
     * For Gradle-imported Kotlin modules that returns ONLY the Java
     * output (`build/classes/java/main`) — Kotlin's own
     * `build/classes/kotlin/main` is missing, which causes hot reload
     * to never see any .class diff. Instead we walk every module's
     * `build/classes/` and pick up every per-language subdirectory.
     */
    fun moduleClassOutputRoots(target: RenderTarget): List<File> {
        val module = target.resolveModule(project) ?: return emptyList()
        val modules = mutableSetOf<Module>().apply {
            add(module)
            ModuleRootManager.getInstance(module).orderEntries()
                .recursively()
                .forEachModule { m -> add(m); true }
        }

        val roots = mutableListOf<File>()
        for (m in modules) {
            val cmePath = CompilerModuleExtension.getInstance(m)
                ?.compilerOutputPath
                ?.toNioPath()
                ?.toFile()
            // Two sources of class output dirs:
            //   (a) CompilerModuleExtension — typically java/main only
            //   (b) sibling dirs under build/classes — kotlin/main, etc.
            if (cmePath?.isDirectory == true) roots += cmePath

            // Walk up to <module>/build/classes and list every
            // <lang>/main subdirectory. This captures Kotlin, Java,
            // Groovy, etc. without us hard-coding language names.
            val classesDir = cmePath?.parentFile?.parentFile
                ?: findGradleClassesDir(m)
            if (classesDir != null && classesDir.isDirectory) {
                classesDir.listFiles()?.forEach { perLang ->
                    val mainDir = File(perLang, "main")
                    if (mainDir.isDirectory && mainDir !in roots) {
                        roots += mainDir
                    }
                }
            }
        }
        return roots
    }

    private fun findGradleClassesDir(module: Module): File? {
        // Last-resort: derive from any content root by walking to its
        // closest ancestor `build/classes` directory.
        val roots = ModuleRootManager.getInstance(module).contentRoots
        for (r in roots) {
            val moduleDir = r.toNioPath().toFile()
            val candidate = File(moduleDir, "build/classes")
            if (candidate.isDirectory) return candidate
        }
        return null
    }

    fun classpathSnapshot(): ClasspathSnapshot = snapshot
    fun isSnapshotPrimed(): Boolean = snapshotPrimed
    fun primeSnapshot() { snapshotPrimed = true }

    /**
     * Toggle multi-frame mode: when enabled, every render fans out to
     * three device sizes (phone / tablet / desktop) and pushes them as
     * a row to the panel. Lets responsive composables get verified at
     * a glance.
     */
    fun setMultiFrameMode(enabled: Boolean) {
        multiFrameMode = enabled
        thisLogger().info("[ComposePreview] multi-frame mode = $enabled")
        // Re-render under the new mode so the panel updates immediately.
        lastTarget?.let { executeRender(it, freshClassLoader = false) }
    }

    /**
     * Toggle live (native ComposePanel) mode. When enabled, the user's
     * composable is mounted directly into a Swing-hosted Compose
     * surface inside the plugin's JVM — no PNG round-trip, no IPC.
     * Disabled by default because of higher classpath fragility.
     */
    fun setLiveUiMode(enabled: Boolean) {
        liveUiMode = enabled
        thisLogger().info("[ComposePreview] live UI mode = $enabled")
        lastTarget?.let { executeRender(it, freshClassLoader = false) }
    }

    companion object {
        val MULTI_FRAME_SIZES: List<Pair<String, RenderSize>> = listOf(
            "Phone" to RenderSize(360, 800),
            "Tablet" to RenderSize(768, 1024),
            "Desktop" to RenderSize(1280, 800),
        )
    }

    /**
     * Re-issue the previously rendered request without rebuilding the
     * RenderTarget. Called by [HotReloadCoordinator] after a successful
     * recompile. Forces a fresh URLClassLoader so the newly-emitted
     * .class files on disk are actually loaded (otherwise URLClassLoader
     * would serve cached classes from before the recompile).
     */
    fun rerenderLast() {
        val target = lastTarget
        if (target == null) {
            thisLogger().warn("[ComposePreview] rerenderLast called but lastTarget is null")
            return
        }
        thisLogger().info("[ComposePreview] rerenderLast: ${target.fqn} (fresh classloader)")
        executeRender(target, freshClassLoader = true)
    }

    fun showRebuilding(fqn: String) {
        ApplicationManager.getApplication().invokeLater {
            panel?.showLoading("Rebuilding: ${fqn.substringAfterLast('.')}")
        }
    }

    fun showError(target: String, message: String, stackTrace: String?) {
        ApplicationManager.getApplication().invokeLater {
            panel?.showError(target, message, stackTrace)
        }
    }

    /**
     * Push a successful render result directly to the panel from outside
     * [executeRender]. Used by [HotReloadCoordinator] when a
     * [RedefineClasses] returns a fresh PNG without going through a new
     * RenderRequest. After this call, also refresh the snapshot baseline.
     */
    fun showImageDirect(fqn: String, pngBase64: String, widthPx: Int, heightPx: Int) {
        // Hot-swap path uses the existing target's class roots.
        lastTarget?.let { t ->
            val roots = moduleClassOutputRoots(t)
            snapshot.diff(roots)
            snapshotPrimed = true
        }
        ApplicationManager.getApplication().invokeLater {
            panel?.showImage(fqn, pngBase64, widthPx, heightPx)
        }
    }

    // ──────────────────────────────────────────────────────────────

    private fun executeRender(target: RenderTarget, freshClassLoader: Boolean = false) {
        if (liveUiMode) {
            executeLiveRender(target)
            return
        }
        if (multiFrameMode) {
            executeMultiFrameRender(target, freshClassLoader)
            return
        }
        executeSingleRender(target, freshClassLoader)
    }

    /**
     * Native-Compose rendering path. Computes the module's production
     * classpath via [OrderEnumerator] (same as the IPC path), hands it
     * to [PreviewPanel.showLive] which loads the class in a fresh
     * URLClassLoader and mounts it inside the embedded ComposePanel.
     */
    private fun executeLiveRender(target: RenderTarget) {
        val module = target.resolveModule(project)
            ?: return reportError("Module ${target.moduleName} not found")
        val classpathPaths = OrderEnumerator.orderEntries(module)
            .recursively()
            .productionOnly()
            .pathsList
            .pathList
            .map { it.toString() }
        if (classpathPaths.isEmpty()) return reportError("Empty production classpath")
        revealToolWindow()
        ApplicationManager.getApplication().invokeLater {
            panel?.showLive(target.fqn, classpathPaths, target.theme)
        }
    }

    private fun executeSingleRender(target: RenderTarget, freshClassLoader: Boolean) {
        // Recompute classpath each render — covers cases where the user
        // changed dependencies between renders.
        val module = target.resolveModule(project)
            ?: return reportError("Module ${target.moduleName} not found")

        val classpathPaths = OrderEnumerator.orderEntries(module)
            .recursively()
            .productionOnly()
            .pathsList
            .pathList
            .map { it.toString() }

        if (classpathPaths.isEmpty()) {
            return reportError(
                "Module ${module.name} has no production classpath. " +
                "Build the project first (Build → Build Project)."
            )
        }

        thisLogger().info("[ComposePreview] executeRender: ${target.fqn}, " +
            "classpath entries=${classpathPaths.size}")

        revealToolWindow()
        panel?.showLoading(target.fqn)

        ProgressManager.runInBackground(project, "Rendering Compose preview: ${target.fqn.substringAfterLast('.')}") {
            val request = RenderRequest(
                requestId = UUID.randomUUID().toString(),
                target = ComposableId(target.fqn),
                classpath = ClasspathEntries(classpathPaths),
                size = target.size,
                theme = target.theme,
                argOverrides = target.argOverrides,
                useAiMocks = target.useAiMocks,
                freshClassLoader = freshClassLoader,
            )
            thisLogger().info("[ComposePreview] sending RenderRequest id=${request.requestId}" +
                " size=${target.size.widthPx}x${target.size.heightPx} theme=${target.theme}" +
                " overrides=${target.argOverrides.size}" +
                if (freshClassLoader) " (fresh classloader)" else "")

            val rendererService = project.getService(RendererProcess::class.java)
            when (val result = rendererService.render(request)) {
                is RendererProcess.Outcome.Success -> {
                    val r: RenderResult = result.result
                    thisLogger().info("[ComposePreview] render SUCCESS: ${target.fqn}, " +
                        "png=${r.pngBase64.length} chars, size=${r.widthPx}x${r.heightPx}, " +
                        "params=${r.paramSummary.size}")
                    // Prime / refresh the hot-swap snapshot AFTER a
                    // successful render so the next save can diff against
                    // exactly what the renderer just loaded.
                    val roots = moduleClassOutputRoots(target)
                    snapshot.diff(roots)
                    snapshotPrimed = true
                    thisLogger().info("[ComposePreview] snapshot primed " +
                        "(${roots.size} root(s) for hot-swap diffs)")
                    setHitMap(r.hitMap)
                    ApplicationManager.getApplication().invokeLater {
                        panel?.showImage(target.fqn, r.pngBase64, r.widthPx, r.heightPx)
                        // Don't clobber an active element selection — the
                        // user is editing one specific call site and the
                        // panel must keep showing its arguments across
                        // every interactive re-render.
                        if (!elementSelectionActive) {
                            panel?.showParameters(r.paramSummary)
                        }
                    }
                }
                is RendererProcess.Outcome.Errored -> {
                    val e: ErrorResponse = result.error
                    thisLogger().warn("[ComposePreview] render ERRORED: ${e.kind}: ${e.message}")
                    ApplicationManager.getApplication().invokeLater {
                        panel?.showError(target.fqn, "${e.kind}: ${e.message}", e.stackTrace)
                    }
                }
                is RendererProcess.Outcome.LaunchFailed -> {
                    thisLogger().error("[ComposePreview] launch FAILED: ${result.reason}")
                    ApplicationManager.getApplication().invokeLater {
                        panel?.showError(target.fqn, "Renderer launch failed", result.reason)
                    }
                }
            }
        }
    }

    /**
     * Multi-frame fan-out: render the SAME composable at three device
     * sizes sequentially (still on one background job to avoid
     * overwhelming the renderer), then push them to the panel as a
     * horizontal row.
     */
    private fun executeMultiFrameRender(target: RenderTarget, freshClassLoader: Boolean) {
        val module = target.resolveModule(project)
            ?: return reportError("Module ${target.moduleName} not found")
        val classpathPaths = OrderEnumerator.orderEntries(module)
            .recursively()
            .productionOnly()
            .pathsList
            .pathList
            .map { it.toString() }
        if (classpathPaths.isEmpty()) return reportError("Module ${module.name} has no classpath")

        revealToolWindow()
        panel?.showLoading(target.fqn + " (multi-frame)")

        ProgressManager.runInBackground(project, "Multi-frame: ${target.fqn.substringAfterLast('.')}") {
            val frames = mutableListOf<PreviewPanel.MultiFrameImage>()
            val rendererService = project.getService(RendererProcess::class.java)
            for ((label, size) in MULTI_FRAME_SIZES) {
                val request = RenderRequest(
                    requestId = UUID.randomUUID().toString(),
                    target = ComposableId(target.fqn),
                    classpath = ClasspathEntries(classpathPaths),
                    size = size,
                    theme = target.theme,
                    freshClassLoader = freshClassLoader,
                )
                when (val out = rendererService.render(request)) {
                    is RendererProcess.Outcome.Success -> {
                        val r = out.result
                        frames += PreviewPanel.MultiFrameImage(label, r.pngBase64, r.widthPx, r.heightPx)
                    }
                    is RendererProcess.Outcome.Errored -> {
                        thisLogger().warn("[ComposePreview] multi-frame $label failed: ${out.error.message}")
                    }
                    is RendererProcess.Outcome.LaunchFailed -> {
                        thisLogger().error("[ComposePreview] multi-frame launch failed: ${out.reason}")
                        return@runInBackground
                    }
                }
            }
            ApplicationManager.getApplication().invokeLater {
                panel?.showMultiFrame(target.fqn, frames)
            }
        }
    }

    private fun reportError(message: String) {
        revealToolWindow()
        ApplicationManager.getApplication().invokeLater {
            panel?.showError(target = "", message = message, stackTrace = null)
        }
    }

    private fun revealToolWindow() {
        ApplicationManager.getApplication().invokeLater {
            ToolWindowManager.getInstance(project).getToolWindow("ComposePreview")?.show()
        }
    }
}

/**
 * Tiny wrapper around IntelliJ's ProgressManager so [PreviewService] stays
 * readable. We deliberately use the backgroundable variant — never block EDT.
 */
private object ProgressManager {
    fun runInBackground(project: Project, title: String, block: (ProgressIndicator) -> Unit) {
        val task = object : Task.Backgroundable(project, title, /* canBeCancelled = */ true) {
            override fun run(indicator: ProgressIndicator) {
                block(indicator)
            }
        }
        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }
}
