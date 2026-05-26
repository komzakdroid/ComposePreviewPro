package com.composepreviewpro.plugin.client

import java.io.File
import java.util.Base64

/**
 * Detects which .class files in a module's compile output changed since
 * the last snapshot.
 *
 * Strategy: keyed by absolute path, store (lastModified, length). On
 * [diff] we re-walk the same roots and emit any file whose
 * (lastModified, length) tuple has moved. Length is included because an
 * mtime-only check misses some compile scenarios where the JVM
 * filesystem-cache reuses the same timestamp.
 *
 * Why not content hash? For a typical project compile we walk thousands
 * of .class files; hashing each on every save would dominate the hot
 * reload latency. (lastModified, length) is what every other dev tool
 * does for incremental compilation tracking.
 */
class ClasspathSnapshot {

    private data class Stamp(val mtime: Long, val length: Long)

    private val seen: MutableMap<String, Stamp> = mutableMapOf()

    data class Changed(
        /** FQN → Base64-encoded class bytes, ready to be put on the wire. */
        val classes: Map<String, String>,
    ) {
        val isEmpty: Boolean get() = classes.isEmpty()
    }

    /**
     * Walk [rootDirs] (typically the module's `build/classes/.../main`
     * directories) and return classes whose stamp differs from the last
     * call. The first call returns everything currently present.
     */
    fun diff(rootDirs: Collection<File>): Changed {
        val freshStamps = mutableMapOf<String, Stamp>()
        val changedClasses = mutableMapOf<String, String>()

        for (root in rootDirs) {
            if (!root.isDirectory) continue
            root.walkTopDown().forEach { file ->
                if (!file.isFile || !file.name.endsWith(".class")) return@forEach
                val key = file.absolutePath
                val stamp = Stamp(file.lastModified(), file.length())
                freshStamps[key] = stamp

                val previous = seen[key]
                if (previous == null || previous != stamp) {
                    val fqn = fqnOf(file, root) ?: return@forEach
                    val bytes = file.readBytes()
                    changedClasses[fqn] = Base64.getEncoder().encodeToString(bytes)
                }
            }
        }

        // Replace the entire snapshot — files deleted since the last call
        // simply drop out, which is the behaviour we want.
        seen.clear()
        seen.putAll(freshStamps)
        return Changed(changedClasses)
    }

    /**
     * Derive a class FQN from its file path relative to its classes root.
     * e.g. `<root>/com/example/Foo.class` → `com.example.Foo`. Skips
     * synthetic inner classes only insofar as they keep their `$` form.
     */
    private fun fqnOf(file: File, root: File): String? {
        val rel = file.toRelativeString(root)
        if (!rel.endsWith(".class")) return null
        return rel.removeSuffix(".class")
            .replace(File.separatorChar, '.')
            .replace('/', '.')
    }
}
