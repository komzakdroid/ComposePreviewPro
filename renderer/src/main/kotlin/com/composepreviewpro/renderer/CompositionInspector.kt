package com.composepreviewpro.renderer

import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.tooling.data.asTree
import com.composepreviewpro.ipc.HitMap
import com.composepreviewpro.ipc.HitNode

/**
 * Walks the captured [CompositionData] and emits one [HitNode] per
 * rendered group, annotated with the precise source position of the
 * composable that produced it.
 *
 * Source-mapping strategy — POSITIONAL MATCHING, because Compose
 * Compiler 2.3.20 doesn't emit per-call slot-key wrappers around
 * library composables inside user code:
 *
 *   1. When we descend into a [Group] whose [Group.key] matches one of
 *      our ASM-extracted [ComposeSourceMapper.FunctionInfo.funcKey]s,
 *      we are inside a user-defined composable. Its source line is
 *      known from the function's source-information string.
 *
 *   2. The DIRECT CHILDREN of that group correspond, in order, to the
 *      composable invocations in the function body. We pair the i-th
 *      child with the function's i-th captured `subCallLines` entry.
 *
 *   3. Children deeper than the first level inherit the source line of
 *      their nearest ancestor that produced a positional mapping. That
 *      way clicking on a Text inside a Button inside a LazyColumn
 *      navigates to the LazyColumn call site — the closest the user's
 *      source can resolve.
 *
 *   4. When we re-enter a user-defined composable nested inside another
 *      (e.g. RoleChip used by UserProfileScreen), the inner function's
 *      funcKey matches and the context is replaced — pointer events on
 *      that subtree resolve to RoleChip's own lines.
 */
@OptIn(UiToolingDataApi::class, InternalComposeApi::class)
class CompositionInspector(
    private val sourceMap: Map<Int, ComposeSourceMapper.FunctionInfo>,
) {

    fun walk(tables: Set<CompositionData>): HitMap {
        val out = mutableListOf<HitNode>()
        for (data in tables) {
            for (root in data.compositionGroups) {
                walkContextual(
                    group = root.asTree(),
                    out = out,
                    parentFunction = null,
                    contextLine = null,
                    contextFile = null,
                    contextName = null,
                )
            }
        }
        return HitMap(out.sortedBy { it.area })
    }

    private fun walkContextual(
        group: Group,
        out: MutableList<HitNode>,
        parentFunction: ComposeSourceMapper.FunctionInfo?,
        contextLine: Int?,
        contextFile: String?,
        contextName: String?,
    ) {
        val keyInt = group.key as? Int
        val matched = keyInt?.let { sourceMap[it] }

        val effectiveFunction = matched ?: parentFunction
        val effectiveFile = matched?.source?.file ?: contextFile
        val effectiveLine = matched?.source?.line ?: contextLine
        val effectiveName = matched?.source?.name ?: contextName

        // Emit a HitNode for this group's bounds.
        val box = group.box
        if (box.right > box.left && box.bottom > box.top) {
            out += HitNode(
                left = box.left,
                top = box.top,
                right = box.right,
                bottom = box.bottom,
                name = effectiveName ?: "<group>",
                sourceFile = effectiveFile,
                sourceLine = effectiveLine,
            )
        }

        // Children walk:
        //   • If THIS node matched a user function, the i-th direct
        //     child maps to that function's i-th sub-call line.
        //   • Otherwise children inherit the same context.
        val children = group.children
        if (matched != null && matched.subCallLines.isNotEmpty()) {
            for ((i, child) in children.withIndex()) {
                val childLine = matched.subCallLines.getOrNull(i)
                    ?: matched.source.line
                    ?: effectiveLine
                walkContextual(
                    group = child,
                    out = out,
                    parentFunction = matched,
                    contextLine = childLine,
                    contextFile = matched.source.file ?: effectiveFile,
                    contextName = matched.source.name ?: effectiveName,
                )
            }
        } else {
            for (child in children) {
                walkContextual(
                    group = child,
                    out = out,
                    parentFunction = effectiveFunction,
                    contextLine = effectiveLine,
                    contextFile = effectiveFile,
                    contextName = effectiveName,
                )
            }
        }
    }
}
