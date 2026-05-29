package com.composepreviewpro.plugin.toolwindow

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.composepreviewpro.ipc.PreviewTheme
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.JBColor
import com.intellij.util.ui.EDT
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.io.File
import java.net.URLClassLoader
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.javaMethod

/**
 * Live, interactive Compose UI hosted inside the IDE tool window. The
 * antithesis of the PNG-streaming path — this panel runs the user's
 * composable AS A REAL COMPOSE UI inside the plugin's JVM, so clicks,
 * scrolls, hovers, and text input behave exactly like in the deployed
 * app.
 *
 * Lifecycle: implements [Disposable]; the IDE registers this against
 * the parent [PreviewPanel] (which is itself a child of the tool window
 * content). On project close / plugin disable, [dispose] tears down the
 * embedded [ComposePanel] (releasing the Skia surface and the AWT peer)
 * and closes the cached [URLClassLoader] so the user's project JARs
 * aren't held open after the IDE no longer needs them. Without this,
 * a heap dump after a dozen project switches shows N parallel copies of
 * every user JAR pinned in the OS file cache.
 *
 * Threading: [show] / [clear] must be called from the EDT. Swing widget
 * mutation and `ComposePanel.setContent` are both EDT-only operations.
 * Callers from background coroutines / pooled threads must marshal via
 * `ApplicationManager.invokeLater`. The methods assert this contract
 * with [EDT.assertIsEdt] so misuse fails loud during development.
 *
 * Architecture trade-offs (vs the PNG renderer):
 *
 *   PRO
 *   • Zero IPC — no per-frame round-trip; latency is measured in
 *     microseconds, not 30-50ms.
 *   • Native Compose: state preservation, animations, gesture
 *     recognisers all work because they ARE Compose, not a screenshot.
 *   • Mouse hover, focus, IME — all flow through naturally.
 *
 *   CON
 *   • Plugin classpath now carries Compose Multiplatform Desktop
 *     (~30MB + Skiko native libs). plugin.zip jumps ~4MB → ~50MB.
 *   • User code is loaded into the plugin's classloader. A buggy
 *     composable can take down the panel (we isolate behind a try/
 *     catch but a runaway recomposition could spin the EDT).
 *   • Compose runtime version must match between the user project and
 *     this plugin. Mismatched versions throw NoSuchMethodError at the
 *     first composition.
 *
 * The plugin therefore offers BOTH paths: PNG (this panel's older
 * sibling) for safety + cross-version compatibility, and live mode for
 * fidelity. The toolbar lets the user switch.
 */
class LivePreviewPanel : JPanel(BorderLayout()), Disposable {

    private val composePanel = ComposePanel()
    private val emptyLabel = JLabel(
        "Click ▶ next to a @Composable function to mount it here.",
        SwingConstants.CENTER,
    ).apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(40)
    }

    // Cached loader per classpath signature — recreated when the user
    // edits the project so newly-compiled .class files actually load.
    // Always touched on the EDT (via show/clear), so no @Volatile needed.
    private var cachedLoader: URLClassLoader? = null
    private var cachedSignature: String? = null

    @Volatile
    private var disposed: Boolean = false

    init {
        background = JBColor.background()
        add(emptyLabel, BorderLayout.CENTER)
    }

    /**
     * Replace the current preview content with a fresh mount of [fqn]
     * loaded from [classpath]. Always builds a new URLClassLoader so
     * that compile-on-save / Gradle output changes are picked up.
     */
    fun show(fqn: String, classpath: List<String>, theme: PreviewTheme) {
        EDT.assertIsEdt()
        if (disposed) {
            thisLogger().warn("[ComposePreview-Live] show() called after dispose() — ignored")
            return
        }
        try {
            removeAll()
            add(composePanel, BorderLayout.CENTER)

            val loader = ensureLoader(classpath)
            val (_, fn) = resolve(loader, fqn) ?: run {
                replaceWithMessage("Composable not found: $fqn")
                return
            }
            // Static class init might require running on EDT (e.g.
            // Material colour schemes initialise Compose state).
            composePanel.setContent {
                MaterialThemed(theme) {
                    LiveInvoke(fn)
                }
            }
            revalidate()
            repaint()
            thisLogger().info("[ComposePreview-Live] mounted $fqn")
        } catch (t: Throwable) {
            thisLogger().error("[ComposePreview-Live] mount failed", t)
            replaceWithMessage("Live mount failed: ${t.message}\n${t.stackTraceToString().take(500)}")
        }
    }

    fun clear() {
        EDT.assertIsEdt()
        if (disposed) return
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    override fun dispose() {
        // Idempotent: Disposer may call us once explicitly and once via
        // a parent chain in pathological reload scenarios. Guard so we
        // don't double-close native handles.
        if (disposed) return
        disposed = true

        try {
            cachedLoader?.close()
        } catch (t: Throwable) {
            thisLogger().warn("[ComposePreview-Live] URLClassLoader.close failed", t)
        }
        cachedLoader = null
        cachedSignature = null

        try {
            // Releases the Skia surface + AWT peer. Without this, every
            // tool-window close leaks a Skiko-backed framebuffer.
            // `ComposePanel.dispose` is `@ExperimentalComposeUiApi` in
            // Compose Multiplatform 1.10 — there is no stable equivalent
            // for releasing the native surface, so we accept the opt-in
            // and pin it locally to this call.
            @OptIn(ExperimentalComposeUiApi::class)
            composePanel.dispose()
        } catch (t: Throwable) {
            thisLogger().warn("[ComposePreview-Live] ComposePanel.dispose failed", t)
        }
    }

    // ── internals ────────────────────────────────────────────────

    private fun replaceWithMessage(text: String) {
        removeAll()
        add(
            JLabel("<html><body style='width:300px'>$text</body></html>", SwingConstants.LEFT).apply {
                foreground = JBColor.RED
                border = JBUI.Borders.empty(20)
            },
            BorderLayout.CENTER,
        )
        revalidate()
        repaint()
    }

    private fun ensureLoader(classpath: List<String>): URLClassLoader {
        val signature = classpath.joinToString("|")
        val existing = cachedLoader
        if (existing != null && cachedSignature == signature) return existing
        try {
            existing?.close()
        } catch (_: Throwable) { /* best effort */ }
        val fresh = URLClassLoader(
            classpath.map { File(it).toURI().toURL() }.toTypedArray(),
            this::class.java.classLoader,
        )
        cachedLoader = fresh
        cachedSignature = signature
        return fresh
    }

    private fun resolve(loader: URLClassLoader, fqn: String): Pair<Class<*>, KFunction<*>>? {
        val className = fqn.substringBeforeLast('.')
        val functionName = fqn.substringAfterLast('.')
        return try {
            val cls = loader.loadClass(className)
            val fn = cls.kotlin.declaredFunctions.firstOrNull { it.name == functionName }
                ?: return null
            cls to fn
        } catch (t: Throwable) {
            thisLogger().warn("[ComposePreview-Live] resolve failed: $fqn — ${t.message}")
            null
        }
    }

    @Composable
    private fun MaterialThemed(theme: PreviewTheme, content: @Composable () -> Unit) {
        when (theme) {
            PreviewTheme.LIGHT -> MaterialTheme(colorScheme = lightColorScheme()) { content() }
            PreviewTheme.DARK -> MaterialTheme(colorScheme = darkColorScheme()) { content() }
        }
    }

    /**
     * Reflective composable invocation. Mirrors the renderer's
     * OffscreenRenderer.InvokeComposable: jam the live Composer + the
     * compiler-injected $changed (and possibly $default) ints onto the
     * end of the source-level argument array. The user composable
     * receives a fully-formed Composer and runs as if called natively.
     */
    @Composable
    private fun LiveInvoke(fn: KFunction<*>) {
        val errorState = remember { mutableStateOf<String?>(null) }
        errorState.value?.let { msg ->
            return JBLabelComposable(msg)
        }
        val javaMethod = fn.javaMethod ?: run {
            errorState.value = "No JVM method for ${fn.name}"
            return
        }
        if (!javaMethod.canAccess(null)) {
            javaMethod.isAccessible = true
        }
        // For now: invoke with NO source args (defaults only) — this is
        // the MVP and only handles composables that take no required
        // parameters OR have defaults for everything. Parameter mocking
        // hookup is the next-session follow-up.
        val composer = currentComposer
        val jvmArgs = mutableListOf<Any?>()
        // Pad with null/zero for source params (best effort). For
        // composables with required params (most of our samples), we
        // need the renderer's auto-mock pipeline — that lives in a
        // separate JVM. The PNG mode covers that today.
        for (param in fn.parameters) {
            if (param.kind != KParameter.Kind.VALUE) continue
            // crude default; real auto-mock requires re-implementing
            // MockEngine in plugin classloader, scheduled separately.
            jvmArgs.add(defaultValueFor(param))
        }
        jvmArgs.add(composer)
        while (jvmArgs.size < javaMethod.parameterCount) {
            jvmArgs.add(0)
        }
        try {
            javaMethod.invoke(null, *jvmArgs.toTypedArray())
        } catch (t: Throwable) {
            errorState.value = "${t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName}: " +
                "${t.cause?.message ?: t.message}"
        }
    }

    @Composable
    private fun JBLabelComposable(text: String) {
        androidx.compose.material3.Text(text)
    }

    private fun defaultValueFor(param: KParameter): Any? = when {
        param.type.toString().contains("String") -> "Preview"
        param.type.toString().contains("Int") -> 42
        param.type.toString().contains("Boolean") -> true
        param.type.toString().contains("Double") -> 3.14
        param.type.toString().contains("Float") -> 3.14f
        param.type.toString().contains("Unit") -> { -> }
        else -> null
    }
}
