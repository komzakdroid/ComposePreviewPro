package com.composepreviewpro.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composepreviewpro.designsystem.GradientCard
import com.composepreviewpro.designsystem.Palette
import com.composepreviewpro.designsystem.PaletteChip

/*
 * Multi-module / shared-UI stress targets. These composables live in :sample
 * but consume widgets, theme Locals, and types from the SEPARATE
 * :sample-designsystem module — the exact `:feature:* → :core:designsystem`
 * topology of a real app (and of MultiTask's downloader screen). The renderer
 * must resolve cross-module classes AND satisfy a theme CompositionLocal
 * declared in the other module, all from a cold preview with no app wrapper.
 */

// ── Cross-module widget + cross-module throwing Local, NO theme wrapper ────
// Mirrors MultiTask `GradientCard` usage: reads :sample-designsystem's
// LocalPalette (throwing default) and takes a value-class Color param.
@Composable
fun FeatureCardNoTheme() {
    GradientCard(title = "Cross-module card") {
        Text("Content from :sample, card from :sample-designsystem", color = Color.White)
    }
}

// ── Cross-module type as a parameter (Palette comes from the other module) ──
@Composable
fun FeatureWithPalette(palette: Palette = Palette.Default) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("accent = ${palette.accent}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaletteChip("Shared")
            PaletteChip("Chip", tone = palette.accent)
        }
    }
}

// ── Feature screen composing several cross-module widgets ──────────────────
@Composable
fun FeatureDashboard() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Feature dashboard", style = MaterialTheme.typography.titleLarge)
        GradientCard(title = "Header", accent = Color(0xFFFB8500)) {
            Text("Nested cross-module content", color = Color.White)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaletteChip("Alpha")
            PaletteChip("Beta")
            PaletteChip("Gamma")
        }
    }
}
