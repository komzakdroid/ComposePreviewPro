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
import com.composepreviewpro.ipc.Scroll
import com.composepreviewpro.plugin.client.ClasspathSnapshot
import com.composepreviewpro.plugin.client.RendererProcess
import com.composepreviewpro.plugin.inspector.CallExpressionInspector
import com.composepreviewpro.plugin.nav.SourceNavigator
import com.composepreviewpro.plugin.reload.HotReloadCoordinator
import com.composepreviewpro.plugin.toolwindow.PreviewPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
class PreviewService(private val project: Project) : Disposable {

    /**
     * Single-thread pump for live interactions (click / scroll).
     *
     * Scroll events are CONFLATED. A trackpad momentum-scroll fires
     * dozens-to-hundreds of [MouseWheelEvent]s in a fraction of a second.
     * The previous code spawned one [Task.Backgroundable] per event — each
     * registered as a separate background process in the IDE (the "200–300
     * processes" the user saw) and each blocked on the renderer's single
     * IPC lock, saturating the thread pool and freezing the EDT. Here a
     * burst collapses to "at most one render in flight + one accumulated
     * pending delta": intermediate deltas are summed and rendered as one
     * frame as fast as the renderer can keep up. Discrete events (click)
     * are never dropped — they queue on this same single thread so they
     * serialise correctly with scrolls. Daemon thread; shut in [dispose].
     */
    private val interactionPump = Executors.newSingleThreadExecutor { r ->
        Thread(r, "compose-preview-interaction").apply { isDaemon = true }
    }

    /** Accumulated, not-yet-sent scroll delta; position tracks the latest event. */
    private val pendingScroll = AtomicReference<Scroll?>(null)

    /** True while a scroll-drain task is queued/running — keeps it single-flight. */
    private val scrollDraining = AtomicBoolean(false)

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

    /**
     * Marshal a panel-update block onto the EDT with an explicit
     * [ModalityState] and a project-disposed guard. Replaces every
     * raw `ApplicationManager.getApplication().invokeLater { … }` call
     * site in this service.
     *
     * **Why an explicit ModalityState?** From IntelliJ 2026.3 onward
     * the platform will be stricter about runnables submitted without
     * a modality argument: they may be silently dropped when the EDT is
     * inside a modal dialog. We always want render results to land in
     * the tool window, so we pin every panel update to
     * [ModalityState.defaultModalityState] — the runnable runs as soon
     * as the EDT is free for the modality state that was active when
     * the background task was scheduled (typically NON_MODAL for our
     * VFS- and ProgressManager-triggered work).
     *
     * **Why the disposed guard?** Renders run on background threads
     * and can take seconds. If the project is closed in between, the
     * queued runnable still fires and dereferences `panel?.` — usually
     * harmless, but on a tight race it can resurrect a tool window that
     * the platform was tearing down, causing leaks and stale-reference
     * NPEs deeper in Swing. `project.disposed` is the canonical
     * cancellation condition.
     */
    private fun runOnEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(
            block,
            ModalityState.defaultModalityState(),
            project.disposed,
        )
    }

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
        val sourceModule = ModuleUtilCore.findModuleForPsiElement(function)
            ?: return reportError("No module found for ${function.name}")
        // For Kotlin Multiplatform projects, the user's @Composable often
        // lives in a `commonMain` source set whose IntelliJ module produces
        // metadata KLIBs, not JVM .class files. The renderer can only
        // load JVM bytecode, so we redirect to a platform-specific
        // sibling module (desktopMain → jvmMain → androidMain in
        // preference order). For plain JVM/Android modules this is a no-op.
        val module = resolveJvmRunnableModule(sourceModule)
        if (module != sourceModule) {
            thisLogger().info("[ComposePreview] redirecting ${sourceModule.name} → " +
                "${module.name} for JVM-runnable classpath")
        }
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
     * If [sourceModule] is a Kotlin Multiplatform shared source set whose
     * compile output is metadata-only (cannot be loaded by a JVM
     * classloader), look up a sibling source-set module that compiles to
     * JVM bytecode and return that instead.
     *
     * IntelliJ exposes one module per KMP source set, named
     * `<gradlePath>.<sourceSet>` (e.g. `MultiTask.feature.timer.commonMain`).
     * We rank siblings by JVM compatibility:
     *
     *   • Pure-JVM targets first — `desktopMain`, `jvmMain`,
     *     `desktopAndAndroidMain`, `skikoMain`, anything ending in
     *     `JvmMain` / `DesktopMain`. These compile @Composable code
     *     against the JVM/Skiko flavors of Compose Multiplatform —
     *     no Android Context needed, Compose Resources reads from the
     *     classpath, **renderer works end-to-end**.
     *
     *   • `androidMain` only as a **last resort**, with a stern warning.
     *     Android-compiled bytecode hard-references `androidx.compose
     *     .ui.platform.LocalContext` and `org.jetbrains.compose.resources
     *     .AndroidContextProviderKt`. The JVM renderer cannot synthesise
     *     an `android.content.Context` (abstract class, not an
     *     interface, can't be dynamic-proxied), so a composable that
     *     uses `stringResource`, `painterResource`, or any other
     *     Android-flavored Compose Multiplatform API will throw
     *     `IllegalStateException: CompositionLocal LocalContext not
     *     present` at render time. This fallback exists for composables
     *     that don't touch any of those APIs.
     *
     * Returns the original module unchanged when no JVM-runnable
     * sibling exists (rare: single-target K/Native, wasm-only modules,
     * etc.).
     */
    private fun resolveJvmRunnableModule(sourceModule: Module): Module {
        val name = sourceModule.name
        // ONLY `.commonMain` is a true KMP shared-source-set suffix. The
        // `.main` and `.commonTest` suffixes match plain Android Gradle
        // modules too (the "main" source set has no JVM ↔ Android split),
        // and triggering the KMP redirect for those was a false positive
        // that broke pure-Android projects like Now In Android by
        // logging a misleading "no JVM-runnable sibling" warning.
        if (!name.endsWith(".commonMain")) return sourceModule
        val baseName = name.removeSuffix(".commonMain")
        val moduleManager = ModuleManager.getInstance(project)
        val allModules = moduleManager.modules
        val siblings = allModules.filter { it.name.startsWith("$baseName.") }
        thisLogger().info(
            "[ComposePreview] KMP sibling modules of $baseName: " +
                siblings.joinToString { it.name.removePrefix("$baseName.") }
        )

        // Tier A — pure JVM targets, ordered by exactness then commonality.
        val tierA = listOf(
            "$baseName.desktopMain",
            "$baseName.jvmMain",
            "$baseName.desktopAndAndroidMain",
            "$baseName.skikoMain",
            "$baseName.nonAndroidMain",
        )
        tierA.forEach { exact ->
            moduleManager.findModuleByName(exact)?.let {
                thisLogger().info("[ComposePreview] picked JVM target: ${it.name}")
                return it
            }
        }
        // Fuzzy match — anything containing "desktop" / "jvm" / "skiko" but
        // NOT "android" / "ios" / "native" / "wasm" / "js".
        val jvmHints = listOf("desktop", "jvm", "skiko")
        val nonJvmHints = listOf("android", "ios", "native", "wasm", "js", "test")
        siblings
            .firstOrNull { s ->
                val short = s.name.removePrefix("$baseName.").lowercase()
                jvmHints.any { short.contains(it) } &&
                    nonJvmHints.none { short.contains(it) }
            }?.let {
                thisLogger().info("[ComposePreview] fuzzy-picked JVM target: ${it.name}")
                return it
            }

        // Tier B — Android fallback, with audible warning.
        moduleManager.findModuleByName("$baseName.androidMain")?.let {
            thisLogger().warn(
                "[ComposePreview] no pure-JVM target found for $baseName — falling back " +
                    "to androidMain. Composables using stringResource / Compose Resources / " +
                    "LocalContext will fail with 'LocalContext not present'. Add a " +
                    "`desktopMain` source set to your module for full preview support."
            )
            return it
        }
        thisLogger().warn(
            "[ComposePreview] no JVM-runnable sibling found for $name; rendering will " +
                "likely fail because the commonMain compile output is metadata-only."
        )
        return sourceModule
    }

    /**
     * Build a [RenderRequest] for [target] using the current classpath.
     * Exposed for [HotReloadCoordinator] to embed inside a
     * [RedefineClasses] payload.
     */
    fun buildRenderRequest(target: RenderTarget): RenderRequest? {
        val module = target.resolveModule(project) ?: return null
        val baseClasspath = OrderEnumerator.orderEntries(module)
            .recursively()
            .productionOnly()
            .pathsList
            .pathList
            .map { it.toString() }
        if (baseClasspath.isEmpty()) return null
        val composeResourceRoots = findComposeResourceRoots(target).map { it.absolutePath }
        val classpathPaths = (baseClasspath + composeResourceRoots).distinct()
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
            runOnEdt {
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
     * composition. Scrolls are coalesced and all events run on the
     * single-thread [interactionPump]; the resulting PNG is pushed back to
     * the panel on the EDT. See [interactionPump] for why conflation is
     * essential (freeze + process storm on fast scroll otherwise).
     */
    fun sendInteraction(event: InputEvent) {
        if (lastTarget == null) return
        when (event) {
            is Scroll -> {
                // Merge consecutive scrolls into one accumulated delta at
                // the latest cursor position, then ensure a drain is armed.
                pendingScroll.updateAndGet { prev ->
                    if (prev == null) event else Scroll(event.x, event.y, prev.deltaY + event.deltaY)
                }
                scheduleScrollDrain()
            }
            // Discrete events (e.g. Click) must not be dropped: queue them
            // directly on the same single-thread pump.
            else -> submitInteraction { runInteraction(event) }
        }
    }

    /** Arm a single-flight drain that renders accumulated scroll deltas. */
    private fun scheduleScrollDrain() {
        if (scrollDraining.compareAndSet(false, true)) {
            submitInteraction {
                try {
                    while (true) {
                        val s = pendingScroll.getAndSet(null) ?: break
                        runInteraction(s)
                    }
                } finally {
                    scrollDraining.set(false)
                    // An event may have landed after our last getAndSet but
                    // before we cleared the flag — re-arm so it isn't lost.
                    if (pendingScroll.get() != null) scheduleScrollDrain()
                }
            }
        }
    }

    private fun submitInteraction(block: () -> Unit) {
        if (project.isDisposed) return
        try {
            interactionPump.submit { if (!project.isDisposed) block() }
        } catch (_: RejectedExecutionException) {
            // Pump shut down during project close — drop silently.
        }
    }

    private fun runInteraction(event: InputEvent) {
        val target = lastTarget ?: return
        val rendererService = project.getService(RendererProcess::class.java)
        val request = Interact(requestId = UUID.randomUUID().toString(), event = event)
        when (val outcome = rendererService.interact(request)) {
            is RendererProcess.Outcome.Success -> {
                val r = outcome.result
                runOnEdt {
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

    override fun dispose() {
        interactionPump.shutdownNow()
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

    /**
     * Walk every transitive module and collect every plausible compiled-
     * classes directory, regardless of build system. This is what makes
     * the difference between "OrderEnumerator gave us 111 entries"
     * (which on a multi-module AGP project is mostly Compose dependency
     * caches) and "the actual ButtonKt.class file is reachable".
     *
     * Probed layouts, in roughly the order of popularity:
     *
     *   • `build/classes/kotlin/main`               — KMP/JVM modules
     *   • `build/classes/java/main`                 — pure-JVM modules
     *   • `build/classes/kotlin/<sourceSet>`        — KMP source-set output
     *   • `build/tmp/kotlin-classes/<variant>`      — AGP+KGP Kotlin out
     *   • `build/intermediates/javac/<variant>/classes`         — AGP Java out
     *   • `build/intermediates/runtime_library_classes_dir/...` — AGP AAR
     *   • `build/intermediates/runtime_library_classes_jar/<v>/classes.jar`
     *
     * Each candidate must exist AND contain at least one `.class` file
     * or be a `.jar` — empty/stale directories are skipped so we don't
     * bloat the renderer's classpath with dead paths.
     */
    private fun collectClassOutputRoots(target: RenderTarget, targetFqn: String = ""): List<File> {
        val module = target.resolveModule(project) ?: return emptyList()
        val mm = ModuleManager.getInstance(project)

        // Step 1: gather ALL modules we care about. Start with the
        // source module, add its source-set parent (e.g. ".main" → ""
        // sibling), and walk transitive module deps. For each module
        // we'll inspect content roots AND name-parent siblings —
        // covers the IntelliJ split where a Gradle module like
        // `:core:designsystem` becomes multiple IDE modules
        // (`nowinandroid.core.designsystem`, `nowinandroid.core
        // .designsystem.main`, `…unitTest`, …).
        val modules = mutableSetOf<Module>()
        modules.add(module)
        addNameParent(mm, module.name, modules)
        ModuleRootManager.getInstance(module).orderEntries()
            .recursively()
            .forEachModule { m ->
                modules.add(m)
                addNameParent(mm, m.name, modules)
                true
            }

        // Step 2: derive Gradle module directories. The IntelliJ Android
        // model registers source-set modules whose content roots are
        // SOURCE dirs (e.g. `core/designsystem/src/main`) — NOT the
        // Gradle module root that holds `build/`. We walk up from each
        // content root until we find a `build.gradle{.kts}` file; that
        // marks the real Gradle module dir.
        val gradleModuleDirs = mutableSetOf<File>()
        for (m in modules) {
            for (contentRoot in ModuleRootManager.getInstance(m).contentRoots) {
                val crFile = contentRoot.toNioPath().toFile()
                gradleModuleDirs.add(crFile)  // try as-is (covers gradle-module modules)
                findGradleModuleRoot(crFile)?.let { gradleModuleDirs.add(it) }
            }
            // ExternalSystemApiUtil sometimes carries an external
            // project path even when IDE content roots are pure source
            // dirs — Android Studio sets it via the AGP importer.
            ExternalSystemApiUtil.getExternalProjectPath(m)
                ?.let { File(it) }
                ?.takeIf { it.isDirectory }
                ?.let { gradleModuleDirs.add(it) }
        }

        val out = mutableListOf<File>()
        val seen = mutableSetOf<String>()

        fun accept(f: File) {
            if (!f.exists()) return
            val hasOutput = when {
                f.isFile && f.name.endsWith(".jar") -> true
                f.isDirectory -> f.walkTopDown().take(2000)
                    .any { it.isFile && (it.name.endsWith(".class") || it.name.endsWith(".jar")) }
                else -> false
            }
            if (!hasOutput) return
            if (seen.add(f.absolutePath)) out += f
        }

        // Step 3: for each Gradle module dir, probe every known layout.
        for (moduleDir in gradleModuleDirs) {
            val buildDir = File(moduleDir, "build")
            if (!buildDir.isDirectory) continue
            // KMP / JVM Kotlin outputs.
            File(buildDir, "classes/kotlin").listFiles()?.forEach(::accept)
            File(buildDir, "classes/java").listFiles()?.forEach(::accept)
            // AGP Kotlin (variant subdirs).
            File(buildDir, "tmp/kotlin-classes").listFiles()?.forEach(::accept)
            // AGP Java — variant/classes pattern.
            File(buildDir, "intermediates/javac").listFiles()?.forEach { variant ->
                accept(File(variant, "classes"))
            }
            // AGP merged library outputs (used by AAR consumers).
            File(buildDir, "intermediates/runtime_library_classes_dir").listFiles()?.forEach(::accept)
            File(buildDir, "intermediates/runtime_library_classes_jar").listFiles()?.forEach { variant ->
                accept(File(variant, "classes.jar"))
            }
            // Some AGP versions output to `intermediates/compile_library_classes_jar`.
            File(buildDir, "intermediates/compile_library_classes_jar").listFiles()?.forEach { variant ->
                accept(File(variant, "classes.jar"))
            }
            // AGP 8.x+ "built-in kotlinc" — emits to
            //   intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes/
            // The path has TWO extra hops (variant + task subdir) below
            // built_in_kotlinc — this was the layout that masked Now In
            // Android's class output from every other probe.
            File(buildDir, "intermediates/built_in_kotlinc").listFiles()?.forEach { variant ->
                variant.listFiles()?.forEach { task ->
                    accept(File(task, "classes"))
                }
            }
        }

        // Step 3.5 — **Android intermediate JARs** (R.jar etc.).
        //
        // AGP generates R.class files PER CONSUMING MODULE/APP from
        // merged resources. They live in well-known intermediate
        // paths but are NOT part of `productionOnly()` classpath or
        // any per-module `build/classes/...` directory. Without them,
        // any code that references `androidx.core.R$id`,
        // `androidx.lifecycle.R$id`, or another library's R-generated
        // identifier hits a `NoClassDefFoundError` at preview time —
        // exactly the symptom that surfaced when `WindowInsets
        // .statusBars` initialised `ViewCompat`.
        //
        // We pick these up project-wide because the user's previewed
        // module rarely owns the merged R; it's typically generated
        // by the application module (`:androidApp`) which we'd never
        // reach via `OrderEnumerator` walking from a feature module.
        val projectBase = project.basePath?.let(::File)
        if (projectBase != null && projectBase.isDirectory) {
            val androidJarPatterns = listOf(
                "/intermediates/compile_r_class_jar/",
                "/intermediates/compile_and_runtime_r_class_jar/",
                "/intermediates/runtime_library_classes_jar/",
                "/intermediates/compile_library_classes_jar/",
                "/intermediates/aar_main_jar/",
                "/intermediates/merged_java_res/",
            )
            projectBase.walkTopDown()
                .onEnter { dir ->
                    val name = dir.name
                    name != "node_modules" &&
                        !name.startsWith(".") &&
                        !dir.absolutePath.contains("/ComposePreviewPro/")
                }
                .filter { it.isFile && it.name.endsWith(".jar") }
                .filter { jar ->
                    val abs = jar.absolutePath
                    androidJarPatterns.any { abs.contains(it) }
                }
                .take(1000)
                .forEach(::accept)
        }

        // Step 4 — **bulletproof FQN search**.
        //
        // No matter how exotic the build layout, the user's compiled
        // `.class` file lives SOMEWHERE under the project tree once
        // they've built. Convert the requested FQN to its expected
        // path-suffix, then walk every `build/` directory looking for
        // a `.class` file whose absolute path ends with that suffix.
        // Each match yields a classpath ROOT (the part of the path
        // BEFORE the package), and we add it. This loop runs even when
        // earlier probes succeeded — Step 4 is additive, not a fallback,
        // because the user's target might live in an AGP layout the
        // probes don't know about while OTHER files (dependency
        // jars, build-logic classes) come from the standard paths.
        val classSuffix = if (targetFqn.isNotEmpty()) {
            targetFqn.replace('.', '/') + ".class"
        } else {
            null
        }
        if (classSuffix != null) {
            val projectBase = project.basePath?.let(::File)
            if (projectBase != null && projectBase.isDirectory) {
                val matches = findClassByFqn(projectBase, classSuffix)
                thisLogger().info(
                    "[ComposePreview] FQN-search '$classSuffix' → ${matches.size} matches"
                )
                matches.forEach(::accept)
            }
        }

        thisLogger().info(
            "[ComposePreview] class-output roots discovered: ${out.size} " +
                "(modules=${modules.size}, gradleDirs=${gradleModuleDirs.size}; " +
                "first=${out.take(3).map { it.absolutePath }})"
        )
        return out
    }

    /**
     * Walk every `build/` directory under [projectBase] looking for
     * a `.class` file whose path ends with [classSuffix] (e.g.
     * `com/google/.../ButtonKt.class`). Return the **classpath roots**
     * (i.e. the absolute prefix BEFORE the package portion). Multiple
     * matches are possible — a multi-variant Android module can have
     * the same class compiled in `demoDebug/`, `prodRelease/`, etc.
     * — and they're all added so the URLClassLoader will pick whichever
     * one happens to be valid for the active variant.
     *
     * Performance: lazy `walkTopDown()` with an early exit per build
     * directory. On a 60-module project this still runs in well under
     * a second because most `build/` trees are shallow and we stop
     * collecting after 5 matches per tree.
     */
    private fun findClassByFqn(projectBase: File, classSuffix: String): List<File> {
        val result = mutableListOf<File>()
        val seen = mutableSetOf<String>()
        // Find every `build` directory shallowly — they're the only
        // places compiled `.class` files live.
        val buildDirs = projectBase.walkTopDown()
            .filter { it.isDirectory && it.name == "build" }
            // Skip our own plugin's build dir to avoid noise.
            .filterNot { it.absolutePath.contains("/ComposePreviewPro/") }
            .toList()
        for (build in buildDirs) {
            val hits = build.walkTopDown()
                .filter { it.isFile && it.absolutePath.endsWith(classSuffix) }
                .take(5)
                .toList()
            for (hit in hits) {
                // The classpath root is the absolute path with the
                // classSuffix portion stripped. The result is the
                // directory `.class` files live UNDER (`/foo/bar/`
                // for a class at `/foo/bar/com/google/.../X.class`).
                val rootPath = hit.absolutePath.removeSuffix("/$classSuffix")
                if (rootPath == hit.absolutePath) continue  // suffix didn't match
                val root = File(rootPath)
                if (root.isDirectory && seen.add(root.absolutePath)) {
                    result += root
                }
            }
        }
        return result
    }

    /**
     * IntelliJ's Android module importer creates source-set modules
     * (`<gradlePath>.main`, `<gradlePath>.unitTest`) and a parent
     * module (`<gradlePath>`). Class outputs live under the PARENT's
     * content root — so when we see a `.main`/`.unitTest`/`.commonMain`-
     * suffixed module, we ALSO want to look at its name-parent so we
     * can walk that module's content root → Gradle module dir →
     * `build/` outputs.
     */
    private fun addNameParent(mm: ModuleManager, name: String, into: MutableSet<Module>) {
        // Trim known source-set suffixes.
        val suffixes = listOf(
            ".main", ".unitTest", ".androidTest",
            ".commonMain", ".commonTest",
            ".androidMain", ".desktopMain", ".jvmMain",
            ".iosMain", ".nativeMain", ".appleMain",
        )
        val stripped = suffixes.firstOrNull { name.endsWith(it) }
            ?.let { name.removeSuffix(it) }
            ?: return
        mm.findModuleByName(stripped)?.let(into::add)
    }

    /**
     * Walk up from [from] until we find a directory containing a
     * `build.gradle{.kts}` file — that's the Gradle module root.
     * Returns `null` if we hit the filesystem root first.
     */
    private fun findGradleModuleRoot(from: File): File? {
        var dir: File? = from
        for (i in 0 until 10) {  // hard cap — never walk beyond 10 levels
            if (dir == null) return null
            if (File(dir, "build.gradle.kts").exists() || File(dir, "build.gradle").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Find every directory under the user's transitive module set that
     * contains a `composeResources/...` subtree. These directories are
     * NOT visible through [OrderEnumerator.productionOnly] because
     * Compose Multiplatform Resources keeps its `.cvr` binaries in
     * Gradle-task output folders (e.g. `build/generated/compose/...`
     * and `build/intermediates/library_assets/...`) — separate from the
     * compiled classes. Without these on the renderer's classpath, the
     * `AssetManager.open(path)` stub returns null and Compose Resources
     * fails to decode (the "Invalid Base64 symbol ' '" symptom).
     *
     * We probe four well-known generated locations under each module's
     * content roots; whichever exist get added. Multi-module projects
     * (e.g. MultiTask with 20+ feature/core modules) light up all the
     * paths simultaneously, so a composable that consumes resources
     * from a *different* module than the one being previewed still
     * resolves.
     */
    fun findComposeResourceRoots(target: RenderTarget): List<File> {
        val roots = mutableListOf<File>()
        val seen = mutableSetOf<String>()
        fun add(dir: File) {
            if (!dir.isDirectory) return
            val abs = dir.absolutePath
            if (seen.add(abs)) roots += dir
        }

        // **Aggressive brute-force scan of the whole project tree.**
        // Why we don't hand-roll path probes anymore: Compose
        // Multiplatform and AGP have shipped several different output
        // layouts for `.cvr` resources (preparedResources, library_assets,
        // androidComposeResources, …). Each minor version shifts the
        // path one level. The bug pattern of "ship probe N, miss layout
        // N+1" is structural. Walking the project tree for any
        // `composeResources/` directory and using its PARENT as a
        // classpath root works regardless of layout — the renderer's
        // `ClassLoader.getResourceAsStream("composeResources/<id>/...")`
        // resolves whichever roots we add.
        //
        // Performance: bounded walk that skips obvious irrelevant dirs
        // (hidden, node_modules, our own plugin) and caps total finds.
        // On a 60-module project this completes in well under a second.
        val projectBase = project.basePath?.let(::File)
        if (projectBase != null && projectBase.isDirectory) {
            projectBase.walkTopDown()
                .onEnter { dir ->
                    val name = dir.name
                    name != "node_modules" &&
                        !name.startsWith(".") &&
                        !dir.absolutePath.contains("/ComposePreviewPro/")
                }
                .filter { it.isDirectory && it.name == "composeResources" }
                .take(500)  // cap total to keep memory bounded
                .forEach { dir -> dir.parentFile?.let(::add) }
        }
        thisLogger().info(
            "[ComposePreview] compose-resource roots discovered: ${roots.size} " +
                "(brute-force scan under ${projectBase?.absolutePath}; " +
                "samples=${roots.take(3).map { it.absolutePath }})"
        )
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
        runOnEdt {
            panel?.showLoading("Rebuilding: ${fqn.substringAfterLast('.')}")
        }
    }

    fun showError(target: String, message: String, stackTrace: String?) {
        runOnEdt {
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
        runOnEdt {
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
        runOnEdt {
            panel?.showLive(target.fqn, classpathPaths, target.theme)
        }
    }

    private fun executeSingleRender(target: RenderTarget, freshClassLoader: Boolean) {
        // Recompute classpath each render — covers cases where the user
        // changed dependencies between renders.
        val module = target.resolveModule(project)
            ?: return reportError("Module ${target.moduleName} not found")

        val baseClasspath = OrderEnumerator.orderEntries(module)
            .recursively()
            .productionOnly()
            .pathsList
            .pathList
            .map { it.toString() }

        // OrderEnumerator returns dependency JARs and SOMETIMES the
        // module's own class outputs — but for Android Library modules,
        // the user's own `core/designsystem/build/intermediates/javac/.../
        // classes/...` directories are routinely missing. We probe them
        // explicitly via moduleClassOutputRoots+AGP-specific paths so
        // the renderer can actually find ButtonKt.class.
        val classOutputRoots = collectClassOutputRoots(target, target.fqn).map { it.absolutePath }

        // Augment with Compose Multiplatform Resources output directories.
        // OrderEnumerator only returns class outputs and JAR deps, but
        // the renderer ALSO needs the .cvr binary files that hold the
        // actual string/asset payloads. Without these, AssetManager
        // .open(...) on an Android-compiled composable returns null and
        // Compose Resources crashes decoding Base64 off an empty stream.
        val composeResourceRoots = findComposeResourceRoots(target).map { it.absolutePath }

        val classpathPaths = (baseClasspath + classOutputRoots + composeResourceRoots).distinct()

        if (classpathPaths.isEmpty()) {
            return reportError(
                "Module ${module.name} has no production classpath. " +
                "Build the project first (Build → Build Project)."
            )
        }

        thisLogger().info("[ComposePreview] executeRender: ${target.fqn}, " +
            "classpath entries=${classpathPaths.size} " +
            "(base=${baseClasspath.size}, +class-output=${classOutputRoots.size}, " +
            "+compose-resources=${composeResourceRoots.size})")

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
                    runOnEdt {
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
                    runOnEdt {
                        panel?.showError(target.fqn, "${e.kind}: ${e.message}", e.stackTrace)
                    }
                }
                is RendererProcess.Outcome.LaunchFailed -> {
                    thisLogger().error("[ComposePreview] launch FAILED: ${result.reason}")
                    runOnEdt {
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
        val baseClasspath = OrderEnumerator.orderEntries(module)
            .recursively()
            .productionOnly()
            .pathsList
            .pathList
            .map { it.toString() }
        val composeResourceRoots = findComposeResourceRoots(target).map { it.absolutePath }
        val classpathPaths = (baseClasspath + composeResourceRoots).distinct()
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
            runOnEdt {
                panel?.showMultiFrame(target.fqn, frames)
            }
        }
    }

    private fun reportError(message: String) {
        revealToolWindow()
        runOnEdt {
            panel?.showError(target = "", message = message, stackTrace = null)
        }
    }

    private fun revealToolWindow() {
        runOnEdt {
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
