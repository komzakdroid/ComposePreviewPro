package com.composepreviewpro.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Shared design system — mirrors a real `:core:designsystem` module.
 *
 * The pattern that breaks naive preview renderers (and is exactly what
 * MultiTask uses): a theme bundle exposed through a CompositionLocal whose
 * default THROWS unless wrapped in the app theme, plus widgets that read it.
 * The renderer must discover the Local in this *separate module* and satisfy
 * it for a cold preview to work.
 */

/** Theme bundle, like MultiTask's `AuroraColors`. An ordinary data class. */
data class Palette(
    val accent: Color,
    val onAccent: Color,
    val heroGradient: Brush,
) {
    companion object {
        val Default = Palette(
            accent = Color(0xFF6750A4),
            onAccent = Color.White,
            heroGradient = Brush.linearGradient(listOf(Color(0xFF6750A4), Color(0xFF9A82DB))),
        )
    }
}

/** Throwing-default Local — the cold-preview cliff a real app theme creates. */
val LocalPalette = staticCompositionLocalOf<Palette> {
    error("LocalPalette not provided — wrap content in DesignSystemTheme { }")
}

/** Theme wrapper (used by real screens; the preview tool targets widgets directly). */
@Composable
fun DesignSystemTheme(palette: Palette = Palette.Default, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) { content() }
}

/**
 * Shared gradient card — mirrors MultiTask's `GradientCard`. Takes a
 * value-class `Color` param (→ JVM name-mangling) AND reads the cross-module
 * [LocalPalette]. The single composable that stresses both fixes at once.
 */
@Composable
fun GradientCard(
    title: String,
    accent: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val resolved = if (accent == Color.Unspecified) palette.accent else accent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(palette.heroGradient)
            .padding(20.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(resolved)
                .padding(14.dp),
        ) {
            androidx.compose.foundation.layout.Column {
                Text(title, color = palette.onAccent, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }
}

/** Simple shared chip with a value-class param — cross-module reuse target. */
@Composable
fun PaletteChip(label: String, tone: Color = Color(0xFF1B998B)) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tone.copy(alpha = 0.2f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, color = tone, fontWeight = FontWeight.SemiBold)
    }
}
