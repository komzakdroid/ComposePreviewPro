package com.composepreviewpro.ipc

import kotlinx.serialization.Serializable

/**
 * One rendered node's position + source identity, used by the plugin to
 * resolve panel clicks to the exact line in the user's Kotlin source.
 *
 * Coordinates are in *scene* space (the canvas widthPx × heightPx). The
 * plugin translates panel-pixel clicks via the displayed image scale,
 * then walks [HitMap.entries] picking the SMALLEST containing box.
 */
@Serializable
data class HitNode(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Composable function name (`"Button"`, `"Text"`, user-defined `"RoleChip"`). */
    val name: String,
    /** Absolute or repo-relative path of the .kt file, if known. */
    val sourceFile: String?,
    /** 1-based source line, if known. */
    val sourceLine: Int?,
) {
    fun contains(x: Int, y: Int): Boolean =
        x in left until right && y in top until bottom

    /** Area in pixels² — used to pick the most specific hit. */
    val area: Int get() = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
}

@Serializable
data class HitMap(val entries: List<HitNode>) {
    companion object {
        val EMPTY = HitMap(emptyList())
    }
}
