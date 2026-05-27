package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ClasspathEntries
import com.composepreviewpro.ipc.ComposableId
import java.io.File
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
        classpath.paths.map { File(it).toURI().toURL() }.toTypedArray(),
        parent,
    )

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
        val kotlinHit = cls.kotlin
            .declaredFunctions
            .firstOrNull { it.name == id.functionName }
        if (kotlinHit != null) return kotlinHit

        // Java-reflection fallback. Rarely needed but covers cases where
        // Kotlin metadata is stripped (R8, ProGuard).
        return cls.declaredMethods
            .firstOrNull { it.name == id.functionName }
            ?.kotlinFunction
    }

    override fun close() {
        classLoader.close()
    }
}
