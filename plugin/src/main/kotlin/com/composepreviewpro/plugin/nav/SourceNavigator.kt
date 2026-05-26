package com.composepreviewpro.plugin.nav

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Resolves a Compose function's fully-qualified name to its source
 * declaration and opens the IDE editor at that position.
 *
 * Approach: top-level `@Composable` functions in Kotlin compile to
 * `public static final` methods of a synthetic `<FileName>Kt` class.
 * IntelliJ's [JavaPsiFacade] exposes those via the LightClassUtil
 * machinery, so the same `findClass` + `findMethod` lookup that works
 * for Java top-level statics also works for Kotlin file facades.
 *
 * Falls back silently when the FQN cannot be resolved — the user just
 * sees no navigation occur, never a popup error.
 */
class SourceNavigator(private val project: Project) {

    fun navigate(composableFqn: String): Boolean {
        // FQN looks like `com.example.SampleScreensKt.UserProfileScreen`.
        val dot = composableFqn.lastIndexOf('.')
        if (dot <= 0) return false
        val className = composableFqn.substring(0, dot)
        val functionName = composableFqn.substring(dot + 1)

        return runReadOnEdt {
            val scope = GlobalSearchScope.allScope(project)
            val psiClass = JavaPsiFacade.getInstance(project).findClass(className, scope)
            if (psiClass == null) {
                thisLogger().info("[ComposePreview] navigate: class $className not found")
                return@runReadOnEdt false
            }
            val method = psiClass.methods.firstOrNull { it.name == functionName }
            if (method == null) {
                thisLogger().info("[ComposePreview] navigate: method $functionName not in $className")
                return@runReadOnEdt false
            }
            val navigatable = method as? Navigatable
                ?: return@runReadOnEdt false
            navigatable.navigate(/* requestFocus = */ true)
            thisLogger().info("[ComposePreview] navigated to $composableFqn")
            true
        }
    }

    /**
     * Open a specific source file at the given line. Used for
     * per-element hits where the inspector resolved a [HitNode] to a
     * file+line. Searches by file name (not absolute path) because the
     * inspector only knows the simple file name.
     *
     * Returns true iff the file was found and the editor moved.
     */
    fun navigateToFileLine(fileName: String, lineNumber: Int): Boolean = runReadOnEdt {
        val scope = GlobalSearchScope.allScope(project)
        val matches = FilenameIndex.getVirtualFilesByName(fileName, scope)
        val target = matches.firstOrNull()
            ?: matches.minByOrNull { it.path.length }  // shortest path wins
            ?: run {
                thisLogger().info("[ComposePreview] navigateToFileLine: $fileName not found")
                return@runReadOnEdt false
            }
        // Editor line numbers in IntelliJ are 0-based; the inspector
        // gives 1-based source lines.
        val descriptor = OpenFileDescriptor(project, target, (lineNumber - 1).coerceAtLeast(0), 0)
        descriptor.navigate(true)
        thisLogger().info("[ComposePreview] navigated to $fileName:$lineNumber")
        true
    }

    /**
     * `Navigatable.navigate` MUST be called on the EDT and inside a
     * read action. We only need a boolean result, so we capture and
     * return from the lambda.
     */
    private fun <T> runReadOnEdt(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        return if (app.isDispatchThread) {
            app.runReadAction<T>(block)
        } else {
            val result = arrayOfNulls<Any>(1)
            app.invokeAndWait {
                result[0] = app.runReadAction<T>(block)
            }
            @Suppress("UNCHECKED_CAST")
            result[0] as T
        }
    }
}
