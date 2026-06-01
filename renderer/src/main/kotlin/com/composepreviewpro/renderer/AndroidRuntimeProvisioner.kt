package com.composepreviewpro.renderer

import java.io.File

/**
 * Locates a **real** Android framework jar (`org.robolectric:android-all`)
 * to shadow the SDK's compile-only `android.jar` stub at render time.
 *
 * Why this exists
 * ----------------
 * The plugin hands the renderer the user's compile classpath, which for an
 * Android module includes the platform `android.jar` from the SDK. That jar
 * is a **stub**: every method and constructor body is literally
 * `throw new RuntimeException("Stub!")`. It exists only to compile against —
 * the real implementation lives on-device in `framework.jar`.
 *
 * The renderer runs on a desktop JVM (Skiko / ImageComposeScene), so there is
 * no device. The moment a previewed composable touches any android.* code with
 * a static initialiser — e.g. `Modifier.windowInsetsPadding(WindowInsets
 * .statusBars)` initialises `WindowInsetsAnimationCompat$Impl21`, whose
 * `<clinit>` constructs an `android.view.animation.PathInterpolator` — the stub
 * fires `RuntimeException: Stub!` and the whole composition dies with
 * `NoClassDefFoundError`.
 *
 * Robolectric's `android-all` artifact is the AOSP framework compiled to real
 * bytecode (the same jar Robolectric/Paparazzi use to run Android off-device).
 * Its constructors and static initialisers have real bodies, so class loading
 * and the constructor paths the renderer hits succeed. We place it **ahead** of
 * the stub on the user-code classloader so it wins for every android.* class,
 * while the stub stays behind it as a backstop for any class android-all lacks.
 *
 * Native methods in android-all (`Looper`, `MessageQueue`, Canvas natives, …)
 * are NOT shadowed here — we are not running the Robolectric runtime. Paths
 * that reach a native call still fail. But the common stub-init crashes
 * (interpolators, `TypedValue`, `Configuration`, view tag/listener setters)
 * are class-load + plain-ctor paths, and those all start working.
 *
 * Discovery order
 * ---------------
 *   1. `-Dcomposepreviewpro.androidRuntimeJar=<path>` — set by the in-process
 *      JavaExec verification tasks (smokeTest, typeMockTest).
 *   2. `$APP_HOME/android-runtime/android-all-*.jar` — bundled into the
 *      `installDist` image alongside `lib/` (see renderer/build.gradle.kts).
 *
 * Returns null when no real runtime is available; callers degrade to the
 * stub and log a precise breadcrumb so the failure mode is diagnosable.
 */
internal object AndroidRuntimeProvisioner {

    /** System property the dev/test tasks use to point at the resolved jar. */
    const val PROPERTY = "composepreviewpro.androidRuntimeJar"

    /** Name of the directory the jar is bundled under inside installDist. */
    private const val BUNDLE_DIR = "android-runtime"

    private val cached: File? by lazy { resolve() }

    /** The located real-Android-runtime jar, or null if none is available. */
    fun locate(): File? = cached

    private fun resolve(): File? {
        // 1 — explicit override (in-process verification tasks).
        System.getProperty(PROPERTY)?.let { path ->
            val f = File(path)
            if (f.isFile) {
                System.err.println("[AndroidRuntimeProvisioner] using -D$PROPERTY → ${f.absolutePath}")
                return f
            }
            System.err.println("[AndroidRuntimeProvisioner] -D$PROPERTY set but not a file: $path")
        }

        // 2 — bundled in the installDist image. Derive $APP_HOME from this
        //     class's own code source: in a production install it is
        //     `$APP_HOME/lib/composepreviewpro-renderer.jar`, so the bundle
        //     dir is a sibling of `lib/`. In a dev/exploded layout the code
        //     source is a `.../classes` dir; we probe both shapes.
        val selfLocation = runCatching {
            File(javaClass.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        if (selfLocation != null) {
            val candidateDirs = sequenceOf(
                selfLocation.parentFile?.parentFile?.resolve(BUNDLE_DIR), // $APP_HOME/lib/.. /android-runtime
                selfLocation.parentFile?.resolve(BUNDLE_DIR),             // .../lib/android-runtime
            )
            for (dir in candidateDirs) {
                val jar = dir
                    ?.takeIf { it.isDirectory }
                    ?.listFiles { f -> f.isFile && f.name.startsWith("android-all") && f.extension == "jar" }
                    ?.firstOrNull()
                if (jar != null) {
                    System.err.println("[AndroidRuntimeProvisioner] using bundled runtime → ${jar.absolutePath}")
                    return jar
                }
            }
        }

        System.err.println(
            "[AndroidRuntimeProvisioner] no real Android runtime found " +
                "(neither -D$PROPERTY nor a bundled $BUNDLE_DIR/android-all-*.jar). " +
                "android.* calls that hit a static initialiser may throw 'Stub!'.",
        )
        return null
    }
}
