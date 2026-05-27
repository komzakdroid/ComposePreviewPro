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
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

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

        // Eligibility — three kinds of @Composable function are callable
        // without us synthesizing an enclosing-instance argument:
        //
        //   1. Top-level                — directly callable through its
        //                                  containing file's facade class.
        //   2. Inside an `object`       — singleton, callable via the
        //                                  generated INSTANCE field.
        //   3. Inside a `companion object` — same as #2.
        //
        // Skipped:
        //   • Members of a `class` — require an instance we cannot
        //     auto-mock generically.
        //   • Extension functions (receiver type) — receiver instance
        //     would need to be conjured; out of scope for MVP.
        //   • Anonymous `@Composable` lambdas — not KtNamedFunction.
        if (!function.hasComposableAnnotation()) return null
        if (function.receiverTypeReference != null) return null
        if (!isInvokableWithoutInstance(function)) return null

        val composableFqn = computeComposableFqn(function) ?: return null
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

    /**
     * True iff the renderer can invoke this function without first
     * conjuring an instance of a containing class. See main eligibility
     * comment in [getLineMarkerInfo] for the full rationale.
     */
    private fun isInvokableWithoutInstance(function: KtNamedFunction): Boolean {
        if (function.isTopLevel) return true
        val parent = function.containingClassOrObject ?: return false
        // Object (singleton or companion) — JVM emits an INSTANCE static
        // field, so the renderer can reach it without constructor magic.
        if (parent is KtObjectDeclaration) return true
        // Regular class members need an instance — out of scope.
        if (parent is KtClass) return false
        return false
    }

    /**
     * Build the dotted name that the renderer's reflection layer expects:
     *
     *   • Top-level fun         → `com.foo.FileNameKt.Composable`
     *   • Object member         → `com.foo.MyObject.Composable`
     *   • Companion member      → `com.foo.OuterClass$Companion.Composable`
     *     (renderer resolves $Companion via the Outer.Companion accessor)
     *
     * Returns `null` if the function is anonymous or its enclosing
     * declaration cannot be FQN-resolved.
     */
    private fun computeComposableFqn(function: KtNamedFunction): String? {
        val funcName = function.name ?: return null
        if (function.isTopLevel) {
            val fileClass = function.containingKtFile.javaFileFacadeFqName.asString()
            return "$fileClass.$funcName"
        }
        val owner = function.containingClassOrObject as? KtObjectDeclaration ?: return null
        val ownerFqn = owner.fqName?.asString() ?: return null
        return "$ownerFqn.$funcName"
    }
}
