package com.composepreviewpro.renderer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.composepreviewpro.ipc.Click
import com.composepreviewpro.ipc.HitMap
import com.composepreviewpro.ipc.InputEvent
import com.composepreviewpro.ipc.PreviewTheme
import com.composepreviewpro.ipc.Scroll
import org.jetbrains.skia.EncodedImageFormat
import java.util.Base64
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaMethod

/**
 * A long-lived [ImageComposeScene] paired with the composable currently
 * mounted in it. Lifetime: one session per (target FQN, size, theme)
 * triple; the renderer's session cache disposes and replaces it when any
 * of those change.
 *
 * The session is the unit that makes the preview *interactive* — once a
 * scene has setContent it carries Compose's full state machinery
 * (Recomposer, snapshot, slot table). [interact] feeds pointer events
 * into that scene and re-renders, so a click on a Button actually fires
 * its onClick lambda and the resulting state change appears in the next
 * frame.
 */
class InteractiveSession(
    val widthPx: Int,
    val heightPx: Int,
    val theme: PreviewTheme,
    private val density: Density = Density(2f),
) : AutoCloseable {

    private val scene: ImageComposeScene = ImageComposeScene(
        width = widthPx,
        height = heightPx,
        density = density,
    )

    /**
     * Captured composition data for the most recent mount. Read by
     * [CompositionInspector] to build the per-element hit map. Set up
     * each mount via [LocalInspectionTables] so Compose's runtime
     * publishes its CompositionData into us automatically.
     */
    @OptIn(InternalComposeApi::class)
    private var inspectionTables: MutableSet<CompositionData> = mutableSetOf()

    /**
     * Mount a composable and return its first frame. Calling this on an
     * already-mounted scene REPLACES the content — useful after a
     * hot-swap so we pick up the new method body, OR when the user
     * clicks ▶ on a different composable.
     */
    @OptIn(InternalComposeApi::class)
    fun mount(
        fn: KFunction<*>,
        args: Map<KParameter, Any?>,
        classpathPaths: List<String> = emptyList(),
    ): String {
        // Fresh inspection table per mount — old composition data is
        // stale once the content lambda changes.
        inspectionTables = mutableSetOf()
        // If the user code was compiled for Android (typical for KMP
        // projects that target only androidMain + iOS), Compose
        // Multiplatform Resources's stringResource/painterResource will
        // read LocalContext.current and crash with "LocalContext not
        // present". Synthesise a Context stub on the user's classloader
        // and provide it via CompositionLocalProvider so the read
        // succeeds. The collectedProvidedValues list is empty when no
        // Android runtime is in scope.
        val userClassLoader: ClassLoader = fn.javaMethod?.declaringClass?.classLoader
            ?: Thread.currentThread().contextClassLoader
        val providedValues = buildAndroidLocalContextProvidedValues(userClassLoader, classpathPaths)
        System.err.println(
            "[InteractiveSession] mount: providedValues=${providedValues.size} " +
                "(0 means non-Android scenario or stub failed)",
        )

        scene.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalInspectionTables provides inspectionTables,
            ) {
                captureComposerData()
                // INLINE provide: the LocalContext provider chain has
                // to be in the SAME @Composable scope as the user's
                // composable, with no wrapping helper function between
                // them. We pass the array of ProvidedValue's directly
                // to CompositionLocalProvider — its vararg overload
                // accepts whatever we hand it.
                CompositionLocalProvider(values = providedValues.toTypedArray()) {
                    MaterialTheme(
                        colorScheme = when (theme) {
                            PreviewTheme.LIGHT -> lightColorScheme()
                            PreviewTheme.DARK -> darkColorScheme()
                        },
                    ) {
                        InvokeComposable(fn, args)
                    }
                }
            }
        }
        return renderToBase64Png()
    }

    /**
     * Discover EVERY `LocalXxx` CompositionLocal that any Android
     * Compose module exposes, and bind each one to a value the user's
     * code can read without crashing.
     *
     * Why this is generic and not hand-rolled
     * ----------------------------------------
     * Real Android Compose code reads a long tail of CompositionLocals:
     *
     *   • `LocalContext`              — the Android Context graph (we
     *                                   own this one and inject our
     *                                   Mockito-built stub).
     *   • `LocalConfiguration`        — `android.content.res.Configuration`.
     *   • `LocalResources`            — `android.content.res.Resources`.
     *   • `LocalView`                 — `android.view.View`.
     *   • `LocalLifecycleOwner`       — `androidx.lifecycle.LifecycleOwner`
     *                                   (lifecycle-runtime-compose).
     *   • `LocalSavedStateRegistryOwner`
     *                                 — `androidx.savedstate.…Owner`
     *                                   (savedstate-compose).
     *   • `LocalViewModelStoreOwner`  — viewmodel-compose.
     *   • `LocalOnBackPressedDispatcherOwner`
     *                                 — activity-compose.
     *
     * Hand-coding every entry was the bug pattern that surfaced as
     * "LocalConfiguration not present" the moment a composable used
     * `stringResource`. Instead: we **enumerate `getLocal*` static
     * methods** on the well-known accessor classes, mock each target
     * type with Mockito, and provide everything in one shot. Whatever
     * the user's Compose module pulls in, we cover it.
     *
     * `LocalContext` is the only special case — we substitute our
     * domain-aware Context stub so that asset reads can route through
     * the user's classpath.
     */
    private fun buildAndroidLocalContextProvidedValues(
        classLoader: ClassLoader,
        classpathPaths: List<String>,
    ): List<androidx.compose.runtime.ProvidedValue<*>> {
        val graph = AndroidContextStub.createGraphOrNull(classLoader, classpathPaths)
            ?: return emptyList()
        // Build a Local-name → value override map so LocalResources and
        // LocalConfiguration resolve to OUR domain-aware stubs instead
        // of generic Mockito mocks. Critical because:
        //
        //   • `stringResource()` reads LocalResources.current.getString(id).
        //     A generic Mockito Resources returns null, which then NPEs
        //     when material3 Text(text: String!) parameter contract
        //     rejects null.
        //
        //   • `LocalConfiguration` is checked by stringResource() to
        //     force recomposition; using a real Configuration object
        //     (no-arg ctor, perfectly valid) keeps locale lookups
        //     consistent across recompositions.
        val overrides = buildMap {
            put("LocalResources", graph.resources)
            graph.configuration?.let { put("LocalConfiguration", it) }
        }
        val androidProvideds = AndroidCompositionLocalProviders.discoverAndProvide(
            classLoader = classLoader,
            contextStub = graph.context,
            overrides = overrides,
        )
        // ALSO discover user-defined CompositionLocals in their classpath.
        // Real-world apps define their own theme-shaped Locals
        // (LocalAuroraColors, LocalAppColors, LocalTypography, …) whose
        // default factory throws unless the user wraps the composable
        // in their app theme. We provide each with a Mockito mock so
        // the preview renders something instead of crashing — Compose
        // tolerates the mock's `0`/`null` defaults well enough for
        // layout & paint.
        val userProvideds = UserCompositionLocalsDiscoverer.discover(
            classLoader = classLoader,
            classpathRoots = classpathPaths,
        )
        val provideds = androidProvideds + userProvideds
        System.err.println(
            "[InteractiveSession] provided ${provideds.size} CompositionLocals " +
                "(android=${androidProvideds.size}, user=${userProvideds.size}, " +
                "${overrides.size} named overrides)",
        )
        return provideds
    }

    /**
     * Pull the active [Composer]'s composition data into our inspection
     * set. Uses reflection because [androidx.compose.runtime.Composer]
     * exposes its composition only through internal APIs that the public
     * artifact doesn't surface. Best-effort: any failure leaves the set
     * untouched, and the plugin falls back to top-level navigation.
     */
    @Composable
    @OptIn(InternalComposeApi::class)
    private fun captureComposerData() {
        val composer = currentComposer
        try {
            val composerClass = composer::class.java
            // Look for a property named "compositionData" reachable on
            // the concrete ComposerImpl. In current Compose runtimes
            // it's a public-but-internal field of `slotTable`.
            val slotTableField = composerClass.declaredFields
                .firstOrNull { it.name == "slotTable" }
                ?: composerClass.declaredFields
                    .firstOrNull { CompositionData::class.java.isAssignableFrom(it.type) }
            if (slotTableField != null) {
                slotTableField.isAccessible = true
                val value = slotTableField.get(composer)
                if (value is CompositionData) {
                    inspectionTables.add(value)
                }
            }
        } catch (_: Throwable) {
            // Silent fallback — the plugin will use top-level nav.
        }
    }

    /**
     * Snapshot the bounds + source positions of every node rendered in
     * the current frame. Returns [HitMap.EMPTY] when the renderer
     * captured no composition data — the caller falls back to
     * top-level navigation.
     *
     * The source-info table is bytecode-derived (see [ComposeSourceMapper])
     * and pairs each composition slot key with its function name + file
     * + line, working around CMP 1.10.3's tooling-data not exposing
     * those at runtime.
     */
    @OptIn(InternalComposeApi::class)
    fun computeHitMap(sourceMap: Map<Int, ComposeSourceMapper.FunctionInfo>): HitMap {
        return try {
            if (inspectionTables.isEmpty()) HitMap.EMPTY
            else CompositionInspector(sourceMap).walk(inspectionTables)
        } catch (t: Throwable) {
            System.err.println("[renderer] hit-map walk failed: ${t.javaClass.simpleName}: ${t.message}")
            HitMap.EMPTY
        }
    }

    /**
     * Feed an [InputEvent] into the live scene and render the resulting
     * frame. State changes inside the composable (mutableStateOf updates
     * triggered by Button.onClick, LazyListState scroll, etc.) are
     * preserved across calls — the same Recomposer drives all frames.
     */
    fun interact(event: InputEvent): String {
        when (event) {
            is Click -> {
                val pos = Offset(event.x.toFloat(), event.y.toFloat())
                // Press + Release sequence at the same point = a tap.
                scene.sendPointerEvent(PointerEventType.Press, pos)
                scene.sendPointerEvent(PointerEventType.Release, pos)
                // After the tap, move the pointer off-canvas and send
                // an Exit so any hover/focus indicator clears in the
                // next frame. Otherwise Compose renders a stuck focus
                // ring around the element the user just tapped.
                scene.sendPointerEvent(PointerEventType.Move, Offset(-10f, -10f))
                scene.sendPointerEvent(PointerEventType.Exit, Offset(-10f, -10f))
            }
            is Scroll -> {
                val pos = Offset(event.x.toFloat(), event.y.toFloat())
                scene.sendPointerEvent(
                    eventType = PointerEventType.Scroll,
                    position = pos,
                    scrollDelta = Offset(0f, event.deltaY),
                )
            }
        }
        return renderToBase64Png()
    }

    fun renderFrame(): String = renderToBase64Png()

    private fun renderToBase64Png(): String {
        val image = scene.render()
        val pngData = image.encodeToData(EncodedImageFormat.PNG)
            ?: error("Skia failed to encode PNG from rendered scene")
        return Base64.getEncoder().encodeToString(pngData.bytes)
    }

    override fun close() {
        scene.close()
    }

    // ── Composable invocation glue (duplicated from OffscreenRenderer
    //    intentionally so InteractiveSession stays a self-contained
    //    unit. Refactor candidate once both paths stabilise.)

    @Composable
    private fun InvokeComposable(fn: KFunction<*>, args: Map<KParameter, Any?>) {
        val javaMethod = fn.javaMethod
            ?: error("No JVM method for ${fn.name} — inline or intrinsic function?")
        if (!javaMethod.canAccess(null)) {
            javaMethod.isAccessible = true
        }

        val sourceArgs: Array<Any?> = fn.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .map { args[it] }
            .toTypedArray()

        val composer = currentComposer
        val jvmArgs = mutableListOf<Any?>()
        jvmArgs.addAll(sourceArgs)
        jvmArgs.add(composer)
        while (jvmArgs.size < javaMethod.parameterCount) {
            jvmArgs.add(0)
        }
        javaMethod.invoke(null, *jvmArgs.toTypedArray())
    }
}
