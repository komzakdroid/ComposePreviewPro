package com.composepreviewpro.sample

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Hand-picked composables that exercise every type the MVP mock engine
 * supports. Each one should successfully render via the preview plugin
 * without any annotations or @Preview boilerplate.
 *
 *   Greeting  → smart-string heuristic (name → "Jane Doe")
 *   Counter   → Int param + () -> Unit lambda
 *   ProfileBadge → multiple primitives + Modifier
 *   PriceTag  → Double + String + nullable
 *   ActionRow → multiple lambdas, exercise Proxy path
 */

@Composable
fun Greeting(name: String) {
    Surface(color = Color.Green) {
        LazyColumn {
            item {
                Text(
                    text = "Hello, $name!",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
            item {
                LazyRow {
                    item {
                        Button(onClick = {}) {
                            Text("Test")
                        }
                    }
                    item {
                        Card(
                            border = BorderStroke(width = 2.dp, color = Color.Red)
                        ) {
                            Text("hi", modifier = Modifier.width(100.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Counter(count: Int, onIncrement: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Count: $count", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onIncrement) {
            Text("Increment")
        }
    }
}

@Composable
fun ProfileBadge(
    name: String,
    email: String,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.padding(16.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .height(12.dp)
                        .background(if (isOnline) Color.Green else Color.Gray)
                )
                Spacer(Modifier.height(8.dp))
                Text(name, style = MaterialTheme.typography.titleMedium)
            }
            Text(email, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PriceTag(label: String, amount: Double, currency: String?) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = "%.2f %s".format(amount, currency ?: "USD"),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
fun ActionRow(
    title: String,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Column(Modifier.padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onApprove) { Text("Approve") }
            Button(onClick = onReject) { Text("Reject") }
        }
    }
}
