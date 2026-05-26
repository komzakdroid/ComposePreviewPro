package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ClasspathEntries
import com.composepreviewpro.ipc.PreviewTheme

/**
 * One-slot cache of the most recently used renderer session.
 *
 * A "session" is the pairing of a [ComposableResolver] (which owns a
 * URLClassLoader) with the classpath that produced it. By keeping the
 * URLClassLoader alive between calls we satisfy two goals:
 *
 *   1. [com.composepreviewpro.ipc.RenderRequest]s that target the same
 *      classpath reuse the loader → second render is faster and the
 *      loaded classes are stable enough for hot-swap.
 *
 *   2. [com.composepreviewpro.ipc.RedefineClasses] requests have a
 *      classloader to operate against — without an existing session there
 *      would be no class instances to redefine.
 *
 * Single-slot is sufficient: in practice a user previews one module at a
 * time. Multi-slot LRU would buy little and add complexity.
 */
internal class SessionCache : AutoCloseable {

    private var current: Entry? = null

    private data class Entry(
        val classpath: ClasspathEntries,
        val resolver: ComposableResolver,
    )

    /**
     * Return the existing resolver if its classpath matches, otherwise
     * dispose the old one and build a new resolver.
     *
     * Set [forceFresh] = true to discard any cached classloader even when
     * the classpath is identical — required after the user recompiles
     * the project, because URLClassLoader caches loaded classes by name
     * and would otherwise silently serve the OLD code from disk.
     */
    fun getOrCreate(classpath: ClasspathEntries, forceFresh: Boolean = false): ComposableResolver {
        val cur = current
        if (!forceFresh && cur != null && cur.classpath == classpath) return cur.resolver
        cur?.resolver?.close()
        val fresh = ComposableResolver(classpath)
        current = Entry(classpath, fresh)
        return fresh
    }

    /**
     * Return the resolver iff its classpath matches; do NOT create a new
     * one. Used by [com.composepreviewpro.ipc.RedefineClasses] which
     * cannot operate without an already-loaded set of classes.
     */
    fun currentResolver(): ComposableResolver? = current?.resolver

    // ── Interactive session lifecycle ──────────────────────────────
    //
    // Separately cached from the resolver because callers re-mount the
    // composable far more often than they reload the classpath: every
    // Interact comes in against an existing session; every fresh ▶
    // click re-mounts within the same classpath. We keep one session
    // and dispose it when any of (target FQN, size, theme) changes.

    private data class SessionKey(
        val fqn: String,
        val widthPx: Int,
        val heightPx: Int,
        val theme: PreviewTheme,
    )

    private var sessionKey: SessionKey? = null
    private var session: InteractiveSession? = null

    fun getOrCreateSession(
        fqn: String,
        widthPx: Int,
        heightPx: Int,
        theme: PreviewTheme,
    ): InteractiveSession {
        val key = SessionKey(fqn, widthPx, heightPx, theme)
        val cur = session
        if (cur != null && sessionKey == key) return cur
        cur?.close()
        val fresh = InteractiveSession(widthPx, heightPx, theme)
        session = fresh
        sessionKey = key
        return fresh
    }

    fun currentSession(): InteractiveSession? = session

    override fun close() {
        current?.resolver?.close()
        current = null
        session?.close()
        session = null
        sessionKey = null
    }
}
