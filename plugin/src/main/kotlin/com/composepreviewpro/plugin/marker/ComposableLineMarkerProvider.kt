package com.composepreviewpro.plugin.marker

import com.composepreviewpro.plugin.service.PreviewService
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Adds a ▶ icon to the editor gutter beside every top-level @Composable
 * function. Clicking the icon hands off to [PreviewService] which runs the
 * renderer and pushes the resulting PNG into the tool window.
 *
 * Why target only the function's *name leaf*, not the whole declaration?
 * IntelliJ requires line markers to be attached to the smallest possible
 * PsiElement — attaching to a parent is logged as a warning and the marker
 * may be skipped during incremental highlight passes.
 */
class ComposableLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only run for Kotlin files.
        if (element.language != KotlinLanguage.INSTANCE) return null

        // Match the name identifier (leaf) of a Kotlin function. The leaf
        // is what IntelliJ expects line markers to target — not the
        // KtNamedFunction itself.
        val nameId = element.takeIf { it.parent is KtNamedFunction && (it.parent as KtNamedFunction).nameIdentifier === it }
            ?: return null
        val function = nameId.parent as KtNamedFunction

        // Only top-level @Composable functions for MVP. Member-level support
        // requires extra wiring (instance argument synthesis) — out of scope.
        if (!function.isTopLevel) return null
        if (!function.hasComposableAnnotation()) return null

        val fqn = function.fqName?.asString() ?: return null
        val fileClassName = function.containingKtFile.javaFileFacadeFqName.asString()
        val composableFqn = "$fileClassName.${function.name}"
        thisLogger().info("[ComposePreview] LineMarker emitted for $composableFqn")

        return LineMarkerInfo(
            /* element       = */ nameId,
            /* range         = */ nameId.textRange,
            /* icon          = */ AllIcons.Actions.Execute,
            /* tooltipFn     = */ { "Render Compose Preview" },
            /* handler       = */ { _, _ ->
                val project = nameId.project
                thisLogger().info("[ComposePreview] Gutter clicked: $composableFqn")
                project.getService(PreviewService::class.java)
                    .renderComposable(function, composableFqn)
            },
            /* alignment     = */ GutterIconRenderer.Alignment.LEFT,
            /* accessibleName = */ { "Render preview for ${function.name}" },
        )
    }

    /**
     * The @Composable annotation may be referenced by short name only
     * (when its import is in scope) or by FQN. Both should be detected.
     */
    private fun KtNamedFunction.hasComposableAnnotation(): Boolean =
        annotationEntries.any { entry ->
            val short = entry.shortName?.asString()
            short == "Composable"
        }
}
