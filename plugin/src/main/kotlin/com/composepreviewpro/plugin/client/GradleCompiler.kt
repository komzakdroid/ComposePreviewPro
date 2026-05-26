package com.composepreviewpro.plugin.client

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drives an out-of-process Gradle compilation of one module's main
 * sources. Used by the hot-reload coordinator INSTEAD of IntelliJ's
 * built-in [CompilerManager] because the latter runs the IDE's bundled
 * Kotlin compiler, whose version may lag the project's Kotlin/Compose
 * Multiplatform pairing by months. Gradle, in contrast, always uses the
 * toolchain pinned in `gradle/libs.versions.toml`, so the plugin works
 * unchanged on every IDE release.
 *
 * Trade-off: cold gradle invocation costs ~3 seconds; with the gradle
 * daemon already warm it drops to ~500ms. Still much faster than a JVM
 * restart of the renderer (the old fork/exec model) and far more robust
 * than relying on the IDE's compiler classpath.
 */
class GradleCompiler(private val project: Project) {

    sealed interface Result {
        object Success : Result
        data class Failed(val exitCode: Int, val output: String) : Result
        data class LaunchFailed(val reason: String) : Result
    }

    /**
     * Compile a module's main classes via Gradle. Caller MUST pass an
     * IntelliJ [Module]; we extract the Gradle project path from it
     * (IntelliJ's `Module.name` like "ComposePreviewPro.sample.main" is
     * NOT a Gradle path — Gradle wants `:sample`).
     *
     * Blocks the caller. Invoke only from a background coroutine.
     */
    fun compileModule(module: Module, timeoutSec: Long = 120): Result {
        val gradlew = resolveGradlew()
            ?: return Result.LaunchFailed(
                "gradlew not found at project base path: ${project.basePath}"
            )

        val gradlePath = gradleProjectPath(module)
            ?: return Result.LaunchFailed(
                "Could not derive Gradle project path from module '${module.name}'. " +
                    "Re-import the Gradle project."
            )
        val task = "$gradlePath:classes"

        thisLogger().info("[ComposePreview] gradle compile: $task (from module ${module.name})")

        val process = try {
            ProcessBuilder(gradlew.absolutePath, task, "-q")
                .directory(File(project.basePath!!))
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            return Result.LaunchFailed("ProcessBuilder failed: ${t.message}")
        }

        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val pump = Thread {
            try {
                reader.lineSequence().forEach { line -> output.appendLine(line) }
            } catch (_: Throwable) { /* stream closed on process death */ }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return Result.Failed(exitCode = -1, output = "timeout after ${timeoutSec}s")
        }
        pump.join(2000)

        val exit = process.exitValue()
        return if (exit == 0) {
            thisLogger().info("[ComposePreview] gradle compile OK")
            Result.Success
        } else {
            thisLogger().warn("[ComposePreview] gradle compile FAILED exit=$exit\n$output")
            Result.Failed(exit, output.toString())
        }
    }

    private fun resolveGradlew(): File? {
        val base = project.basePath ?: return null
        // Unix gradlew first; .bat is irrelevant on JVM-only renderer path.
        val candidate = File(base, "gradlew")
        return if (candidate.exists() && candidate.canExecute()) candidate else null
    }

    /**
     * Resolve a Gradle project path (e.g. `:sample`, `:nested:lib`) from
     * an IntelliJ module. Two paths in order of preference:
     *
     *   1. External-system project ID, set by IntelliJ on Gradle import.
     *      Typically looks like `ComposePreviewPro:sample:main` or
     *      `:sample:main`. We strip the source-set suffix and root
     *      project prefix.
     *
     *   2. Fallback: parse `Module.name` which IntelliJ formats as
     *      `Root.Sub[.Nested].SourceSet`. Drop root and source set,
     *      colon-join the rest.
     */
    private fun gradleProjectPath(module: Module): String? {
        // (1) Try the external-system view first.
        val externalId = ExternalSystemApiUtil.getExternalProjectId(module)
        if (externalId != null) {
            val trimmed = externalId
                .removeSuffix(":main")
                .removeSuffix(":test")
            // External ID may or may not include a leading colon; may
            // also include the root project name as the first segment.
            val withoutLeadingColon = trimmed.removePrefix(":")
            val parts = withoutLeadingColon.split(":").filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) {
                val rootName = project.name
                val withoutRoot = if (parts.first() == rootName) parts.drop(1) else parts
                if (withoutRoot.isNotEmpty()) {
                    return ":" + withoutRoot.joinToString(":")
                }
            }
        }

        // (2) Fallback: parse the IntelliJ module name.
        // "ComposePreviewPro.sample.main" → ":sample"
        val parts = module.name.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        // Drop the root project name (first segment).
        val withoutRoot = parts.drop(1)
        // Drop the source-set suffix if present.
        val withoutSourceSet = if (withoutRoot.last() in SOURCE_SETS) {
            withoutRoot.dropLast(1)
        } else {
            withoutRoot
        }
        if (withoutSourceSet.isEmpty()) return null
        return ":" + withoutSourceSet.joinToString(":")
    }

    private companion object {
        val SOURCE_SETS = setOf("main", "test", "androidMain", "commonMain", "jvmMain")
    }
}
