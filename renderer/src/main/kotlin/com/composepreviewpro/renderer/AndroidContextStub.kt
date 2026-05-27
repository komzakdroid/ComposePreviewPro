package com.composepreviewpro.renderer

import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

/**
 * Runtime-synthesised `android.content.Context` graph that lets the JVM
 * renderer execute Kotlin-Multiplatform composables whose **only** target
 * is `androidMain`.
 *
 * Why this exists
 * ----------------
 * Compose Multiplatform Resources (`stringResource`, `painterResource`)
 * uses `expect/actual`. The desktop/JVM implementation reads from the
 * classpath, the Android one reads through `Context.assets`. Because the
 * expect/actual split is resolved at compile time, a composable
 * compiled for the Android target has the `LocalContext.current` lookup
 * baked into its bytecode and crashes in any pure-JVM host.
 *
 * Why Mockito (and NOT plain ByteBuddy.subclass)
 * -----------------------------------------------
 * `android.content.res.AssetManager` is declared `public final` in the
 * Android SDK. ByteBuddy's standard subclass strategy refuses to extend
 * final classes — and Compose Resources' Android preview path calls
 * `context.applicationContext.assets.open(path)` to read every resource.
 *
 * Mockito 5 ships with the inline mock maker enabled by default
 * (`mock-maker-inline`). It uses ByteBuddy + a JVMTI agent to **redefine**
 * already-loaded classes, which lets us intercept methods on final
 * types like AssetManager. We use the same API to mock Context and
 * Resources for consistency. The renderer's JVM startup already passes
 * `-XX:+EnableDynamicAgentLoading` so Mockito's agent attaches without
 * extra opt-in.
 *
 * Limits (be honest with the user)
 * --------------------------------
 * The stub returns deterministic placeholders (`"preview-string"`, empty
 * arrays, zeroed primitives) for everything except a handful of
 * navigation-critical getters. Composables that go beyond
 * `stringResource`/`painterResource` lookups — launching intents,
 * querying `PackageManager`, using `AndroidView { ... }` — will still
 * fail at runtime. Recommend the user add a `desktopMain` target for
 * full fidelity. But for the common KMP screen case (`stringResource`
 * + Material), this stub keeps the preview working without forcing a
 * project change.
 */
internal object AndroidContextStub {

    /**
     * Build a Context stub against [userClassLoader]. Returns `null` if
     * the user's classpath does not contain `android.content.Context`
     * (no Android dependency in scope) — caller renders without
     * wrapping `LocalContext`.
     *
     * @param classpathPaths the raw classpath entries (directories AND
     *     jar files) shipped with the RenderRequest. The synthetic
     *     `AssetManager.open(path)` indexes any `composeResources/...`
     *     and `assets/composeResources/...` files inside these roots so
     *     Compose Multiplatform Resources's preview path can find its
     *     `.cvr` binaries — without this, calls land on `getResource
     *     AsStream(path)` which only sees the classloader's root entries
     *     and misses Android assets bundled under the `assets/` prefix.
     */
    /**
     * Bundle of synthetic Android objects emitted as a group so callers
     * can route them to the right CompositionLocal each. [context]
     * implements `Context`; [resources] is its returned
     * `getResources()`; [configuration] is a real, instantiable
     * `Configuration` object (it has a public no-arg constructor); and
     * [assets] is the AssetManager the Context routes its asset reads
     * through.
     */
    data class StubGraph(
        val context: Any,
        val resources: Any,
        val configuration: Any?,
        val assets: Any,
    )

    /** Backwards-compatible shorthand for callers that only want the Context. */
    fun createOrNull(userClassLoader: ClassLoader, classpathPaths: List<String>): Any? =
        createGraphOrNull(userClassLoader, classpathPaths)?.context

    fun createGraphOrNull(
        userClassLoader: ClassLoader,
        classpathPaths: List<String>,
    ): StubGraph? {
        val contextClass = try {
            userClassLoader.loadClass("android.content.Context")
        } catch (_: ClassNotFoundException) {
            System.err.println(
                "[AndroidContextStub] android.content.Context not on classpath — " +
                    "skipping LocalContext provider (non-Android scenario).",
            )
            return null
        }
        val resourcesClass = try {
            userClassLoader.loadClass("android.content.res.Resources")
        } catch (t: Throwable) {
            System.err.println("[AndroidContextStub] Resources class load failed: ${t.message}")
            return null
        }
        val assetManagerClass = try {
            userClassLoader.loadClass("android.content.res.AssetManager")
        } catch (t: Throwable) {
            System.err.println("[AndroidContextStub] AssetManager class load failed: ${t.message}")
            return null
        }

        // Build a resource index across every classpath root so that
        // `AssetManager.open("composeResources/...")` can find its file
        // regardless of whether the host module packaged the asset at
        // the JAR root or under an `assets/` prefix (Android packaging
        // adds the prefix; pure-JVM ones don't). The index also covers
        // .jar entries since AGP-built modules sometimes ship resources
        // inside their classes.jar.
        val resourceIndex = buildResourceIndex(classpathPaths)
        System.err.println(
            "[AndroidContextStub] resource index: ${resourceIndex.size} entries " +
                "(scanned ${classpathPaths.size} classpath roots)",
        )
        if (resourceIndex.isNotEmpty()) {
            val sample = resourceIndex.keys.take(3).joinToString()
            System.err.println("[AndroidContextStub] sample keys: $sample")
        }

        // Configuration is a concrete class with a public no-arg ctor —
        // construct directly instead of mocking so locale-driven helpers
        // (`Configuration.locale`, getLayoutDirection) return real values.
        val configurationStub: Any? = try {
            userClassLoader.loadClass("android.content.res.Configuration")
                .getDeclaredConstructor()
                .newInstance()
        } catch (t: Throwable) {
            System.err.println(
                "[AndroidContextStub] Configuration no-arg ctor unavailable " +
                    "(${t.javaClass.simpleName}); LocalConfiguration will use Mockito mock.",
            )
            null
        }

        return try {
            val assetManagerMock = mockWith(
                assetManagerClass,
                AssetManagerAnswer(userClassLoader, resourceIndex),
            )
            val resourcesMock = mockWith(
                resourcesClass,
                ResourcesAnswer(assetManagerMock, configurationStub),
            )
            val selfHolder = SelfHolder()
            val contextAnswer = ContextAnswer(
                resources = resourcesMock,
                assets = assetManagerMock,
                self = selfHolder,
            )
            val contextMock = mockWith(contextClass, contextAnswer)
            selfHolder.value = contextMock
            System.err.println(
                "[AndroidContextStub] Mockito mocks ready: " +
                    "Context=${contextMock.javaClass.name}, " +
                    "Resources=${resourcesMock.javaClass.name}, " +
                    "AssetManager=${assetManagerMock.javaClass.name}, " +
                    "Configuration=${configurationStub?.javaClass?.name ?: "<unavailable>"}",
            )
            StubGraph(
                context = contextMock,
                resources = resourcesMock,
                configuration = configurationStub,
                assets = assetManagerMock,
            )
        } catch (t: Throwable) {
            System.err.println(
                "[AndroidContextStub] failed to synthesise Context stub: " +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
            t.printStackTrace(System.err)
            null
        }
    }

    private fun mockWith(type: Class<*>, answer: Answer<*>): Any {
        // Mockito.mock(Class, Answer) — inline mock maker handles final
        // classes via class redefinition (requires the JVMTI agent
        // which mockito-core attaches itself at runtime).
        return Mockito.mock(
            type,
            Mockito.withSettings()
                .defaultAnswer(answer)
                .stubOnly(),  // memory-light: don't record interactions
        )
    }

    private class SelfHolder { @Volatile var value: Any? = null }

    private class ContextAnswer(
        private val resources: Any,
        private val assets: Any,
        private val self: SelfHolder,
    ) : Answer<Any?> {
        override fun answer(invocation: InvocationOnMock): Any? {
            return when (invocation.method.name) {
                "getApplicationContext", "getBaseContext" -> self.value
                "getResources" -> resources
                "getAssets" -> assets
                "getPackageName", "getOpPackageName" -> "compose.preview.stub"
                "getClassLoader" -> Thread.currentThread().contextClassLoader
                else -> defaultReturnValue(invocation.method.returnType)
            }
        }
    }

    /**
     * Resources answer covering every `Resources.getXxx` accessor a
     * typical Jetpack Compose / Compose Multiplatform composable can
     * hit during preview. Every getter that the Android Resources
     * implementation declares as `@NonNull` MUST return non-null here,
     * otherwise the calling composable's non-null parameter contract
     * crashes the render (the symptom we saw with `Text(text: String)`
     * receiving `null` from a generic Mockito mock).
     */
    private class ResourcesAnswer(
        private val assets: Any,
        private val configuration: Any?,
    ) : Answer<Any?> {
        override fun answer(invocation: InvocationOnMock): Any? {
            val name = invocation.method.name
            return when {
                name == "getAssets" -> assets
                name == "getConfiguration" -> configuration
                // Strings / text — non-null deterministic placeholders.
                name == "getString" -> {
                    val id = invocation.arguments.firstOrNull() as? Int
                    if (id != null) "preview-string-$id" else "preview-string"
                }
                name == "getStringArray" -> arrayOf("preview-string")
                name == "getText" -> "preview-text"
                name == "getQuantityString" -> "preview-qty"
                name == "getQuantityText" -> "preview-qty"
                // Numbers / colors / dimensions — sane defaults.
                name == "getInteger" -> 0
                name == "getInt" -> 0
                name == "getBoolean" -> false
                name == "getColor" -> 0xFF888888.toInt()  // neutral gray
                name == "getColorStateList" -> null  // ColorStateList is @Nullable in some overloads
                name == "getFloat" -> 1f
                name == "getDimension" -> 0f
                name == "getDimensionPixelSize" -> 16
                name == "getDimensionPixelOffset" -> 0
                name == "getFraction" -> 0f
                // Drawables / vectors — can't fake an Android Drawable
                // in a JVM-only renderer. Returning null is documented;
                // composables that use painterResource will still
                // proceed to layout because Compose Resources handles
                // null drawables via placeholder.
                name == "getDrawable" -> null
                name == "getDrawableForDensity" -> null
                // Identifiers / metadata — non-null deterministic names.
                name == "getResourceName" -> "preview-resource"
                name == "getResourceEntryName" -> "preview-entry"
                name == "getResourceTypeName" -> "string"
                name == "getResourcePackageName" -> "preview"
                name == "getIdentifier" -> 0
                // Arrays — empty non-null defaults.
                name == "getIntArray" -> IntArray(0)
                name == "obtainTypedArray" -> null
                name == "obtainAttributes" -> null
                // Display metrics / display info — Mockito-deep mocks
                // are fine; renderer doesn't lay out anything that
                // truly needs density-aware pixel math.
                name == "getDisplayMetrics" -> null
                // Fallback — Mockito default-answer (null for objects,
                // 0/false for primitives). Composables shouldn't hit
                // these paths during preview; if they do, the resulting
                // NPE is more diagnostic than fabricating wrong data.
                else -> defaultReturnValue(invocation.method.returnType)
            }
        }
    }

    /**
     * Lookup table from a requested asset path to a concrete source —
     * either a [File] on disk or an `(jar, entryName)` pair. Built once
     * per stub via [buildResourceIndex]; queried on every
     * `AssetManager.open(...)` invocation.
     */
    private sealed interface ResourceLocation {
        fun open(): InputStream
        data class OnDisk(val file: File) : ResourceLocation {
            override fun open(): InputStream = file.inputStream()
        }
        data class InJar(val jar: File, val entry: String) : ResourceLocation {
            override fun open(): InputStream {
                // JarFile is closed lazily — opening one entry doesn't
                // pull the whole archive into memory.
                val jf = JarFile(jar)
                val zipEntry = jf.getJarEntry(entry)
                    ?: error("entry $entry not found in ${jar.name}")
                return object : InputStream() {
                    private val delegate = jf.getInputStream(zipEntry)
                    override fun read(): Int = delegate.read()
                    override fun read(b: ByteArray, off: Int, len: Int) = delegate.read(b, off, len)
                    override fun close() { delegate.close(); jf.close() }
                }
            }
        }
    }

    private class AssetManagerAnswer(
        private val classLoader: ClassLoader,
        private val resourceIndex: Map<String, ResourceLocation>,
    ) : Answer<Any?> {
        override fun answer(invocation: InvocationOnMock): Any? {
            return when (invocation.method.name) {
                "open" -> {
                    val path = invocation.arguments.firstOrNull() as? String
                        ?: return emptyStream()
                    val stream = openResource(path)
                    if (stream != null) {
                        System.err.println("[AssetManagerStub] open($path) → HIT")
                        return stream
                    }
                    System.err.println(
                        "[AssetManagerStub] open($path) → MISS " +
                            "(index size=${resourceIndex.size})",
                    )
                    emptyStream()
                }
                "list" -> emptyArray<String>()
                else -> defaultReturnValue(invocation.method.returnType)
            }
        }

        /**
         * Try four lookups, in order:
         *   1. Direct classloader hit on the requested path.
         *   2. Classloader with an `assets/` prefix (Android packaging
         *      conventions).
         *   3. The pre-built resource index.
         *   4. The pre-built index with an `assets/` prefix.
         * Returns the first hit, or `null` if nothing matched.
         */
        private fun openResource(path: String): InputStream? {
            classLoader.getResourceAsStream(path)?.let { return it }
            classLoader.getResourceAsStream("assets/$path")?.let { return it }
            resourceIndex[path]?.let { return it.open() }
            resourceIndex["assets/$path"]?.let { return it.open() }
            return null
        }

        private fun emptyStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    /**
     * Scan every classpath root for `composeResources/...` files. We index
     * each file under TWO keys — the path relative to the root (raw),
     * and the path stripped of any `assets/` prefix — so that whichever
     * form the caller requests, we have a match.
     *
     * JAR entries are added separately; their `JarFile` is closed
     * promptly when the corresponding stream is closed.
     */
    private fun buildResourceIndex(classpathPaths: List<String>): Map<String, ResourceLocation> {
        val index = mutableMapOf<String, ResourceLocation>()
        for (path in classpathPaths) {
            val root = File(path)
            try {
                when {
                    root.isDirectory -> indexDirectory(root, index)
                    root.isFile && root.name.endsWith(".jar") -> indexJar(root, index)
                    else -> Unit
                }
            } catch (t: Throwable) {
                System.err.println(
                    "[AndroidContextStub] index scan of ${root.absolutePath} failed: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
        return index
    }

    private fun indexDirectory(root: File, out: MutableMap<String, ResourceLocation>) {
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val rel = file.relativeTo(root).path.replace('\\', '/')
            if (rel.contains("composeResources/")) {
                addKeys(rel, ResourceLocation.OnDisk(file), out)
            }
        }
    }

    private fun indexJar(jar: File, out: MutableMap<String, ResourceLocation>) {
        JarFile(jar).use { jf ->
            val entries = jf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.contains("composeResources/")) {
                    addKeys(name, ResourceLocation.InJar(jar, name), out)
                }
            }
        }
    }

    private fun addKeys(
        relativePath: String,
        location: ResourceLocation,
        out: MutableMap<String, ResourceLocation>,
    ) {
        // Raw path as-is.
        out.putIfAbsent(relativePath, location)
        // Without the `assets/` prefix Android adds automatically.
        if (relativePath.startsWith("assets/")) {
            out.putIfAbsent(relativePath.removePrefix("assets/"), location)
        }
        // Without the leading `/` if any.
        if (relativePath.startsWith("/")) {
            out.putIfAbsent(relativePath.removePrefix("/"), location)
        }
    }

    private fun defaultReturnValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> ' '
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Void.TYPE -> null
        else -> null  // reference types get null
    }

    @Suppress("unused")
    private val unused = Answers.RETURNS_DEFAULTS  // keep the import alive
}
