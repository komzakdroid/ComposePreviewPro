package com.composepreviewpro.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.ColorPainter
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/**
 * Mockito default-answer that fabricates NON-NULL values for the return types
 * that crash a Compose preview when null:
 *
 *   • `String` / `CharSequence` — `Text(text = media.fileName)` declares its
 *     `text` parameter non-null; a plain mock returns null → NPE.
 *   • `Brush` / `Shape` / `Painter` — `Modifier.background(palette.heroGradient)`
 *     needs a non-null Brush.
 *   • enums — a `when (media.type)` over an enum needs a real constant.
 *
 * Value-class getters (Color, Dp) are unboxed to primitives at the JVM level,
 * so Mockito's `0L`/`0` already yields a valid `Color(0)`/`Dp(0)` — only OBJECT
 * return types need help. Everything else falls through to Mockito's standard
 * defaults (null / 0 / false).
 *
 * Shared by the value-class Java invocation path ([JavaComposableInvoker]) and
 * the user-CompositionLocal discoverer (theme bundles).
 */
internal object SmartMockAnswer : Answer<Any?> {

    override fun answer(invocation: org.mockito.invocation.InvocationOnMock): Any? {
        val rt = invocation.method.returnType
        return when (rt.name) {
            "java.lang.String", "java.lang.CharSequence" -> "Preview"
            "androidx.compose.ui.graphics.Brush" -> SolidColor(Color.Transparent)
            "androidx.compose.ui.graphics.Shape" -> RectangleShape
            "androidx.compose.ui.graphics.painter.Painter" -> ColorPainter(Color.Transparent)
            else -> if (rt.isEnum) {
                rt.enumConstants?.firstOrNull() ?: Mockito.RETURNS_DEFAULTS.answer(invocation)
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
    }

    /** Build a stub-only mock of [type] that uses this smart answer. */
    fun mock(type: Class<*>): Any? = try {
        Mockito.mock(type, Mockito.withSettings().defaultAnswer(this).stubOnly())
    } catch (_: Throwable) {
        null
    }
}
