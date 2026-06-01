package com.composepreviewpro.sampleandroid

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/*
 * Android-only render paths the desktop samples can't exercise:
 *   • stringResource(R.string.…)               — Android resource table (text)
 *   • painterResource(R.drawable.…)            — Android resource table (vector)
 *   • windowInsetsPadding(WindowInsets.statusBars) — android-all + native runtime
 *
 * These compile to real Android bytecode against AndroidX Compose, and AGP
 * generates the R class + resources. The renderer must render each off-device.
 */

@Composable
fun AndroidStringCard() {
    Card(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.android_card_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.android_card_subtitle), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun AndroidIconRow() {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.preview_icon),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Text(stringResource(R.string.android_card_title), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun AndroidFullCard() {
    Card(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painter = painterResource(R.drawable.preview_icon),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Text(stringResource(R.string.android_card_title), style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(R.string.android_card_subtitle), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
