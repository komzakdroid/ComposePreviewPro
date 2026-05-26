package com.composepreviewpro.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Realistic UI surfaces exercised by the auto-mocked Compose Preview.
 *
 * Each screen takes domain data classes (User, ChatThread, Product, …)
 * so the auto-mock engine has to recurse through nested structures,
 * collections, enums, and nullable fields. If any of these renders
 * correctly, the mock pipeline is end-to-end production-grade for
 * day-to-day product UI work.
 *
 * No @Preview annotations and no manual fakes — that is the whole point.
 */

// ════════════════════════════════════════════════════════════════════
//  Domain types
// ════════════════════════════════════════════════════════════════════

data class User(
    val name: String,
    val email: String,
    val role: UserRole,
    val isOnline: Boolean,
    val postsCount: Int,
    val followersCount: Int,
    val bio: String?,
)

enum class UserRole { ADMIN, MEMBER, GUEST }

data class ChatThread(
    val name: String,
    val lastMessage: String,
    val unreadCount: Int,
    val isOnline: Boolean,
)

data class Product(
    val title: String,
    val description: String,
    val price: Double,
    val currency: String,
    val ratingOutOfFive: Double,
    val inStock: Boolean,
    val tags: List<String>,
)

data class DashboardStats(
    val activeUsers: Int,
    val revenue: Double,
    val growthPercent: Double,
)

data class Event(
    val title: String,
    val timestamp: String,
    val type: EventType,
)

enum class EventType { LOGIN, PURCHASE, ERROR, INFO }

// ════════════════════════════════════════════════════════════════════
//  Screens
// ════════════════════════════════════════════════════════════════════

@Composable
fun UserProfileScreen(user: User) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RectangleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        user.name.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(user.name, style = MaterialTheme.typography.titleLarge)
                    Text(user.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        user.email + "TEst",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoleChip(user.role)
                        Spacer(Modifier.width(8.dp))
                        StatusDot(isOnline = user.isOnline)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            user.bio?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(label = "Posts", value = user.postsCount.toString())
                StatCard(label = "Followers", value = user.followersCount.toString())
            }
        }
    }
}

@Composable
private fun RoleChip(role: UserRole) {
    val color = when (role) {
        UserRole.ADMIN -> MaterialTheme.colorScheme.primary
        UserRole.MEMBER -> MaterialTheme.colorScheme.secondary
        UserRole.GUEST -> MaterialTheme.colorScheme.outline
    }
    Surface(color = color, shape = RoundedCornerShape(8.dp)) {
        Text(
            role.name,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatusDot(isOnline: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (isOnline) Color(0xFF22C55E) else Color.Gray),
    )
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(
        modifier = Modifier.padding(0.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ────────────────────────────────────────────────────────────────────

@Composable
fun ChatListScreen(threads: List<ChatThread>) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn {
            item {
                Text(
                    "Messages",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(20.dp),
                )
            }
            items(threads) { thread ->
                ChatRow(thread)
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}

@Composable
private fun ChatRow(thread: ChatThread) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                thread.name.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(thread.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (thread.isOnline) {
                    Spacer(Modifier.width(6.dp))
                    StatusDot(isOnline = true)
                }
            }
            Text(
                thread.lastMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
        if (thread.unreadCount > 0) {
            Badge { Text(thread.unreadCount.toString()) }
        }
    }
}

// ────────────────────────────────────────────────────────────────────

@Composable
fun ProductDetailScreen(product: Product) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp)) {
            // Hero image placeholder
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    product.title.take(3).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(product.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "%.1f ★".format(product.ratingOutOfFive),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    "%.2f %s".format(product.price, product.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                product.tags.take(4).forEach { tag ->
                    AssistChip(onClick = {}, label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(product.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                if (product.inStock) "In stock" else "Out of stock",
                style = MaterialTheme.typography.labelLarge,
                color = if (product.inStock) Color(0xFF22C55E) else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(stats: DashboardStats, recentEvents: List<Event>) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.padding(20.dp)) {
            item {
                Text("Dashboard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(label = "Active users", value = stats.activeUsers.toString())
                    StatCard(label = "Revenue", value = "$%.0f".format(stats.revenue))
                    StatCard(
                        label = "Growth",
                        value = "%+.1f%%".format(stats.growthPercent),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            items(recentEvents) { event ->
                EventRow(event)
            }
        }
    }
}

@Composable
private fun EventRow(event: Event) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(event.type.tintColor().copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                event.type.symbol(),
                color = event.type.tintColor(),
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(event.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun EventType.tintColor(): Color = when (this) {
    EventType.LOGIN -> Color(0xFF22C55E)
    EventType.PURCHASE -> Color(0xFF3B82F6)
    EventType.ERROR -> Color(0xFFEF4444)
    EventType.INFO -> Color(0xFF6B7280)
}

private fun EventType.symbol(): String = when (this) {
    EventType.LOGIN -> "↗"
    EventType.PURCHASE -> "$"
    EventType.ERROR -> "!"
    EventType.INFO -> "i"
}
