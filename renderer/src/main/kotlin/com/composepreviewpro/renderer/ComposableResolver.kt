package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ClasspathEntries
import com.composepreviewpro.ipc.ComposableId
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.kotlinFunction

/**
 * Loads the user's compiled classes from disk and resolves a fully-qualified
 * @Composable name to a [KFunction] we can invoke reflectively.
 *
 * Why a separate [URLClassLoader] per request? Two reasons:
 *
 *   1. Isolation — multiple previews of different projects can coexist
 *      without their classpaths bleeding into one another.
 *
 *   2. Future hot-reload — a fresh class loader is the *only* way to swap a
 *      changed class without restarting the JVM. The standard system loader
 *      caches forever; URLClassLoader can be closed and discarded.
 */
class ComposableResolver(
    private val classpath: ClasspathEntries,
    private val parent: ClassLoader = ComposableResolver::class.java.classLoader,
) : AutoCloseable {

    val classLoader: URLClassLoader = URLClassLoader(
        buildClasspathUrls(classpath.paths),
        parent,
    )

    /**
     * Build the child classloader's URL search path.
     *
     * When the user's compile classpath carries the SDK's compile-only
     * `android.jar` stub (the standard Android scenario), prepend a **real**
     * Android framework jar ([AndroidRuntimeProvisioner]) so that android.*
     * classes resolve to bytecode with real method bodies instead of the
     * stub's `throw new RuntimeException("Stub!")`. URLClassLoader searches
     * its URLs in order, so the real runtime wins for every shared class
     * while the stub stays behind it as a backstop for anything the real
     * runtime happens to lack.
     *
     * Pure Compose-Multiplatform/desktop classpaths (no `android.jar`) are
     * left untouched — there is nothing to shadow and we avoid pulling the
     * heavy android-all jar into a render that does not need it.
     */
    private fun buildClasspathUrls(paths: List<String>): Array<URL> {
        val userUrls = paths.map { File(it).toURI().toURL() }
        val hasSdkStub = paths.any { File(it).name == "android.jar" }
        if (!hasSdkStub) return userUrls.toTypedArray()

        val androidRuntime = AndroidRuntimeProvisioner.locate()
        if (androidRuntime == null) {
            System.err.println(
                "[ComposableResolver] SDK android.jar stub is on the classpath but no real " +
                    "android-all runtime was found — android.* calls that touch a static " +
                    "initialiser may throw 'Stub!'.",
            )
            return userUrls.toTypedArray()
        }
        System.err.println(
            "[ComposableResolver] prepending real Android runtime '${androidRuntime.name}' " +
                "ahead of the SDK stub (shadows android.* 'Stub!' bodies).",
        )
        return (listOf(androidRuntime.toURI().toURL()) + userUrls).toTypedArray()
    }

    /**
     * @return the Kotlin function descriptor for [id], or null if not found.
     *
     * Top-level functions live on a synthetic class named "<FileName>Kt".
     * The [id]'s `className` already encodes this convention.
     *
     * We deliberately use [KFunction] rather than [java.lang.reflect.Method]
     * because the Compose Compiler injects extra synthetic parameters
     * (`$composer`, `$changed`) that Java reflection sees but Kotlin
     * reflection hides — KFunction lets us bind only the *real* parameters.
     */
    fun resolve(id: ComposableId): KFunction<*>? {
        val cls = try {
            classLoader.loadClass(id.className)
        } catch (e: ClassNotFoundException) {
            // Emit detailed diagnostics so the IDE side can show the user
            // which class file we were looking for and whether anything
            // close to it exists on the classpath — speeds up debugging
            // of "TARGET_NOT_FOUND" for non-trivial KMP / Gradle layouts.
            System.err.println(
                "[ComposableResolver] class ${id.className} not on classpath. " +
                    "Classpath entries=${classpath.paths.size}:"
            )
            classpath.paths.take(40).forEach { System.err.println("  $it") }
            if (classpath.paths.size > 40) {
                System.err.println("  ... (${classpath.paths.size - 40} more)")
            }
            // Look for a class file with the same simple name anywhere
            // under the classpath roots so the IDE can suggest the
            // closest module.
            val expected = id.className.substringAfterLast('.') + ".class"
            classpath.paths.asSequence()
                .map { File(it) }
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.name == expected } }
                .take(5)
                .forEach { System.err.println("[ComposableResolver] near miss: ${it.absolutePath}") }
            return null
        }

        // Try Kotlin reflection first — preferred path, hides synthetics.
        // Compare on the *demangled stem* so a value-class parameter (e.g.
        // `accent: Color`, which is a @JvmInline value class) still matches:
        // Kotlin's name-mangling appends a `-<hash>` suffix to the JVM name,
        // and on file-facade classes kotlin-reflect does not reliably surface
        // such members under their plain source name.
        val target = id.functionName.demangledStem()
        val kotlinHit = cls.kotlin
            .declaredFunctions
            .firstOrNull { it.name.demangledStem() == target }
        if (kotlinHit != null) return kotlinHit

        // Java-reflection fallback. Covers two cases the Kotlin path misses:
        //   1. Inline/value-class name mangling — the bytecode method is
        //      `UrlInputCard-9k3Hd`, while the IDE sends `UrlInputCard`.
        //   2. Kotlin metadata stripped by R8/ProGuard.
        // declaredMethods (not getMethods) is required because these
        // composables are commonly `private`.
        //
        // CRITICAL: a value-class-param composable's `Method.kotlinFunction`
        // is *poisoned* — `.name`/`.parameters`/`.javaMethod` all throw
        // `KotlinReflectionInternalError`. Returning it would crash every
        // downstream binder. We validate the KFunction is usable and return
        // null otherwise, signalling the caller to fall back to
        // [resolveMethod] + [JavaComposableInvoker] (the pure-Java path).
        val method = cls.declaredMethods
            .firstOrNull { it.name.demangledStem() == target }
            ?: return null
        val kf = runCatching { method.kotlinFunction }.getOrNull() ?: return null
        val usable = runCatching {
            kf.name
            kf.parameters.size
            true
        }.getOrDefault(false)
        return if (usable) kf else null
    }

    /**
     * Resolve the raw [java.lang.reflect.Method] for [id], independent of
     * kotlin-reflect. Always succeeds where [resolve] returns null due to a
     * value-class-mangled signature — this is the target the pure-Java
     * [JavaComposableInvoker] renders.
     */
    fun resolveMethod(id: ComposableId): java.lang.reflect.Method? {
        val cls = try {
            classLoader.loadClass(id.className)
        } catch (_: ClassNotFoundException) {
            return null
        }
        val target = id.functionName.demangledStem()
        return cls.declaredMethods.firstOrNull { it.name.demangledStem() == target }
    }

    /**
     * Strip Kotlin's value-class name-mangling suffix. `foo-9k3Hd` → `foo`,
     * `foo` → `foo`. The suffix is a `-` followed by a base64-ish hash that
     * never appears in a legal Kotlin source identifier, so splitting on the
     * first `-` is safe and lossless for matching purposes.
     */
    private fun String.demangledStem(): String = substringBefore('-')

    override fun close() {
        classLoader.close()
    }
}
