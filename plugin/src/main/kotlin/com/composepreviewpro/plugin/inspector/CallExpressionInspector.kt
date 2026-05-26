package com.composepreviewpro.plugin.inspector

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Bridges a rendered hit-node to its source call expression. Lets the
 * Parameters panel surface the EXACT arguments of the composable the
 * user clicked on, and write edited values back into the file with a
 * single PSI mutation.
 *
 * Why PSI instead of regex / string editing?
 *   PSI keeps every other detail of the source intact — formatting,
 *   comments, trailing commas, mixed quote styles — and IntelliJ's
 *   refactoring infrastructure already handles undo, reformatting, and
 *   write-action threading. A regex pass would re-format the file or,
 *   worse, mis-edit a similarly-named argument elsewhere in scope.
 */
class CallExpressionInspector(private val project: Project) {

    data class ArgInfo(
        /**
         * Explicit argument name at the call site — i.e. the user wrote
         * `color = …`. `null` if the call site used a positional arg.
         */
        val name: String?,
        /**
         * The display / write-back name. Equal to [name] if the call
         * site is explicitly named; otherwise the i-th declared parameter
         * name (resolved via PSI reference), if we could resolve the
         * callee. `null` only when even resolution failed (genuinely
         * unknown name — read-only).
         */
        val resolvedName: String?,
        /** The argument's *textual* expression as it appears in source. */
        val currentValueText: String,
        /** PSI reference for write-back. */
        val argument: KtValueArgument,
        /**
         * Zero-based index of this arg within the call's positional slots
         * (i.e. how many args came before it without an explicit name).
         * `-1` for explicitly-named args. Used by the writer to insert
         * the new value as a NAMED argument when the original was
         * positional — safer than replacing in-place, because Kotlin
         * preserves positional ordering anyway and we get readable code.
         */
        val positionalIndex: Int,
    )

    data class CallInfo(
        val functionName: String,
        val args: List<ArgInfo>,
        val file: String,
        val line: Int,
    )

    /**
     * Find the most-specific composable call expression at the given
     * file:line position. We pick the call whose entire text range
     * stays within the line (most specific) over a call whose range
     * straddles many lines — that way clicking inside a nested
     * `Text(...)` resolves to Text, not the enclosing Column.
     */
    fun callAtLine(fileName: String, line: Int): CallInfo? {
        val app = ApplicationManager.getApplication()
        return if (app.isDispatchThread) {
            app.runReadAction<CallInfo?> { resolveCall(fileName, line) }
        } else {
            val box = arrayOfNulls<CallInfo>(1)
            app.invokeAndWait {
                box[0] = app.runReadAction<CallInfo?> { resolveCall(fileName, line) }
            }
            box[0]
        }
    }

    private fun resolveCall(fileName: String, line: Int): CallInfo? {
        val scope = GlobalSearchScope.allScope(project)
        val vf = FilenameIndex.getVirtualFilesByName(fileName, scope)
            .minByOrNull { it.path.length }
            ?: return null
        val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(ktFile) ?: return null

        // Positional matching from the bytecode is approximate — Compose
        // Compiler sometimes maps a sub-call to the function's start
        // line, or to a line one or two off from the actual call. We
        // therefore probe a small window around the target line and
        // pick the nearest match.
        val candidates = mutableListOf<Pair<Int, KtCallExpression>>()
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression
                    ?: return super.visitCallExpression(expression)
                val calleeStart = callee.textRange.startOffset
                val callLine = document.getLineNumber(calleeStart) + 1
                val distance = kotlin.math.abs(callLine - line)
                if (distance <= LINE_TOLERANCE) {
                    candidates += distance to expression
                }
                super.visitCallExpression(expression)
            }
        })
        if (candidates.isEmpty()) return null

        // Two-tier ranking — composables before helpers:
        //
        //   Tier A : callee starts with an UPPERCASE letter — by Compose
        //            convention this is a @Composable invocation (Box,
        //            Card, Text, Image, MyScreen, …) and is what the
        //            user clicked on visually.
        //   Tier B : everything else — modifier methods (fillMaxSize,
        //            padding, …), factory functions (cardElevation,
        //            CardDefaults, …), property accessors, etc.
        //
        // A line like `Card(modifier = Modifier.fillMaxSize().padding(8.dp))`
        // has FOUR KtCallExpressions: Card, Modifier, fillMaxSize,
        // padding. Without this filter the inspector would frequently
        // return the deepest helper instead of the enclosing Card. With
        // the filter we ALWAYS land on the user-visible composable, and
        // fall back to helpers only when no composable was found.
        val composableTier = candidates.filter { (_, expr) ->
            val first = expr.calleeExpression?.text?.firstOrNull()
            first != null && first.isUpperCase()
        }
        val pool = if (composableTier.isNotEmpty()) composableTier else candidates

        // Within the chosen tier: nearest line wins, ties broken by
        // smallest text length (innermost call).
        val chosen = pool
            .sortedWith(compareBy({ it.first }, { it.second.textLength }))
            .first().second
        val funcName = chosen.calleeExpression?.text ?: return null

        // Resolve callee → declaration so we can recover parameter NAMES
        // for positional call sites. Without this, `Box(Modifier...)` shows
        // its modifier arg as "<positional>" and is read-only; with it we
        // can label and edit every Compose argument by name.
        val declaredParamNames: List<String> = resolveParameterNames(chosen)

        val args = chosen.valueArguments.mapIndexedNotNull { index, ktArg ->
            val expr = ktArg.getArgumentExpression() ?: return@mapIndexedNotNull null
            val explicitName = ktArg.getArgumentName()?.asName?.asString()
            // For positional args: fall back to the i-th declared parameter
            // name if we resolved the callee. Mark `resolvedFromDeclaration`
            // so the writer can re-write as a *named* argument (safer than
            // trying to write a bare positional in the same slot, which
            // would mis-bind if the user reordered things elsewhere).
            val resolvedName: String? = explicitName
                ?: declaredParamNames.getOrNull(index)?.takeIf { it.isNotEmpty() }
            ArgInfo(
                name = explicitName,
                resolvedName = resolvedName,
                currentValueText = expr.text,
                argument = ktArg,
                positionalIndex = if (explicitName == null) index else -1,
            )
        }
        val actualLine = document.getLineNumber(
            chosen.calleeExpression!!.textRange.startOffset,
        ) + 1
        return CallInfo(
            functionName = funcName,
            args = args,
            file = fileName,
            line = actualLine,
        )
    }

    /**
     * Resolve the call's callee to its declaration and return the list of
     * **user-visible** parameter names in declaration order.
     *
     * "User-visible" means: drop the synthetic parameters Compose Compiler
     * adds on every @Composable function (`$composer: Composer`,
     * `$changed: Int`, `$default: Int`). These appear in the JVM signature
     * exposed to PSI but never in source.
     *
     * Three code paths, in increasing fragility:
     *
     *   1. **Kotlin source available** → `KtNamedFunction.valueParameters`
     *      is the canonical list — Kotlin compiler params, no synthetics.
     *
     *   2. **Kotlin compiled-only** (library, like Compose Foundation's
     *      `Box`) → `KtLightMethod.kotlinOrigin` exposes a `KtFunction`
     *      that, while lighter than a source-resolved one, still iterates
     *      the user-declared params cleanly via [valueParameters].
     *
     *   3. **Pure Java method** → `PsiMethod.parameterList` is the only
     *      thing available; we filter out the Compose synthetics by name.
     *
     * If resolution fails altogether we return an empty list — the call
     * site still works, every arg is just labelled `<positional>` again.
     */
    private fun resolveParameterNames(call: KtCallExpression): List<String> {
        val resolved = try {
            call.calleeExpression?.mainReference?.resolve()
        } catch (t: Throwable) {
            thisLogger().debug("[ComposePreview] reference resolve failed: ${t.message}")
            null
        } ?: return emptyList()

        return when (resolved) {
            is KtNamedFunction -> resolved.valueParameters.map { it.name.orEmpty() }
            is KtLightMethod -> {
                val origin = resolved.kotlinOrigin
                if (origin is KtNamedFunction) {
                    origin.valueParameters.map { it.name.orEmpty() }
                } else {
                    filterSyntheticParams(resolved.parameterList.parameters.map { it.name })
                }
            }
            is PsiMethod -> filterSyntheticParams(resolved.parameterList.parameters.map { it.name })
            else -> emptyList()
        }
    }

    /**
     * Compose Compiler appends `$composer`, `$changed`, and (when defaults
     * exist) `$default` parameters at the JVM level. They follow the
     * user-declared params positionally — dropping them by name is the
     * simplest correct filter, and avoids hard-coded "drop last N" logic
     * that breaks if Compose changes its compiler ABI again.
     */
    private fun filterSyntheticParams(names: List<String>): List<String> {
        return names.filter { name ->
            name.isNotEmpty() &&
                !name.startsWith("\$composer") &&
                !name.startsWith("\$changed") &&
                !name.startsWith("\$default")
        }
    }

    private companion object {
        const val LINE_TOLERANCE = 5  // search ±5 lines around the bytecode-derived line
    }

    /**
     * Replace one argument's value expression with [newValueText],
     * then save the file. The save triggers our existing VFS listener
     * → Gradle compile → hot-swap → re-render, so the user sees the
     * change rendered within ~1s.
     *
     * [argName] may be either the call-site name (e.g. `color`) or the
     * resolved-declaration name (e.g. `modifier` for a positional arg
     * whose declaration we recovered). Whichever the panel sent is
     * matched against [ArgInfo.name] and [ArgInfo.resolvedName].
     */
    fun updateArgument(call: CallInfo, argName: String?, newValueText: String): Boolean {
        val app = ApplicationManager.getApplication()
        val arg = call.args.firstOrNull {
            it.name == argName || it.resolvedName == argName
        } ?: return false
        var ok = false
        app.invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Edit Compose Parameter", null, {
                try {
                    val factory = KtPsiFactory(project)
                    if (arg.name == null && arg.resolvedName != null) {
                        // Originally a positional arg, but we know its
                        // declared name → rewrite the WHOLE KtValueArgument
                        // as a named one (`name = value`). This is the
                        // safe path because the next render re-reads the
                        // file as PSI and benefits from named binding even
                        // if later positional args shift around.
                        val newArg = factory.createArgument(
                            /* expression = */ factory.createExpression(newValueText),
                            /* name = */ org.jetbrains.kotlin.name.Name.identifier(arg.resolvedName),
                        )
                        arg.argument.replace(newArg)
                    } else {
                        val newExpr = factory.createExpression(newValueText)
                        arg.argument.getArgumentExpression()?.replace(newExpr)
                    }
                    val ktFile = arg.argument.containingFile
                    val document = PsiDocumentManager.getInstance(project)
                        .getDocument(ktFile)
                    if (document != null) {
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                        FileDocumentManager.getInstance().saveDocument(document)
                    }
                    ok = true
                } catch (t: Throwable) {
                    thisLogger().warn("[ComposePreview] PSI edit failed: ${t.message}")
                }
            })
        }
        return ok
    }
}
