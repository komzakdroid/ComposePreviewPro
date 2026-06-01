package com.composepreviewpro.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * ── Renderer stress battery ───────────────────────────────────────────────
 *
 * Progressively harder composables that exercise the render pipeline beyond
 * the happy path:
 *
 *   • value-class parameters → Kotlin name-mangling (the `accent: Color` bug)
 *   • custom @JvmInline value class params → different mangling hash
 *   • private composables (private static JVM methods)
 *   • deep nesting, complex local state, derivedStateOf, lazy lists
 *   • default + nullable + vararg + generic signatures
 *   • sealed-interface state with exhaustive `when`
 *   • ViewModel obtained via multiplatform `viewModel()` → owner provider
 *   • StateFlow + collectAsState UDF
 *   • interaction (clickable state mutation)
 *   • user-defined CompositionLocal with a throwing default
 *
 * Every function here is meant to be pointed at directly by the preview tool.
 */

// ── 1. Value-class parameter (the exact shape that name-mangles) ───────────
// `accent: Color` has NO default → the renderer must auto-mock a value class
// AND resolve a method whose JVM name is `AccentChip-<hash>`.
@Composable
fun AccentChip(label: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent == Color.Unspecified) Color(0xFF6750A4) else accent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

// ── 2. Custom @JvmInline value class param (default-supplied) ──────────────
@JvmInline
value class Money(val cents: Long) {
    val display: String get() = "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

@Composable
fun MoneyTag(price: Money = Money(2599), accent: Color = Color(0xFF1B998B)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(price.display, style = MaterialTheme.typography.titleMedium)
    }
}

// ── 3. PRIVATE composable with a value-class param (private + mangled) ─────
@Composable
private fun PrivateBadge(tone: Color = Color(0xFFE76F51)) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tone.copy(alpha = 0.18f))
            .padding(12.dp),
    ) {
        Text("private ✓", color = tone, fontWeight = FontWeight.Bold)
    }
}

// Public entry that also lets the tool target PrivateBadge directly.
@Composable
fun PrivateBadgeHost() {
    Column(Modifier.padding(16.dp)) { PrivateBadge() }
}

// ── 4. Deep nesting (recursive, 6 composable levels) ───────────────────────
@Composable
fun DeeplyNested(depth: Int = 6) {
    if (depth <= 0) {
        Text("core", fontWeight = FontWeight.Bold)
        return
    }
    Box(
        modifier = Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.hsv((depth * 40f) % 360f, 0.4f, 0.95f))
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        DeeplyNested(depth - 1)
    }
}

// ── 5. Complex local state: remember + derivedStateOf + mutableStateListOf ─
@Composable
fun ComplexStateBoard() {
    val items = remember { mutableStateListOf(3, 1, 4, 1, 5, 9, 2, 6) }
    val total by remember { derivedStateOf { items.sum() } }
    val max by remember { derivedStateOf { items.maxOrNull() ?: 0 } }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Σ=$total  max=$max", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { v ->
                Box(
                    modifier = Modifier
                        .size(width = 18.dp, height = (8 + v * 6).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

// ── 6. Lazy list with stable keys + contentType (scrollable) ───────────────
@Composable
fun LazyFeed(count: Int = 40) {
    val rows = remember { List(count) { "Row #$it — lazy item with a stable key" } }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it }, contentType = { "row" }) { label ->
            Card(Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

// ── 7. Confusing signature: defaults + nullable + vararg ───────────────────
@Composable
fun ConfusingSignature(
    title: String = "Defaults",
    subtitle: String? = null,
    accent: Color = Color(0xFF2A9D8F),
    vararg tags: String,
) {
    Column(Modifier.padding(16.dp)) {
        Text(title, color = accent, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val shown = if (tags.isEmpty()) arrayOf("auto", "mock") else tags
            shown.forEach { Text("#$it", color = accent.copy(alpha = 0.8f)) }
        }
    }
}

// ── 8. Generic composable (type param does NOT mangle the name) ────────────
@Composable
fun <T> GenericList(values: List<T> = emptyList(), render: (T) -> String = { it.toString() }) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (values.isEmpty()) {
            Text("• (generic composable, empty default)")
        } else {
            values.forEach { Text("• ${render(it)}") }
        }
    }
}

// ── 9. Sealed-interface state with exhaustive when ─────────────────────────
sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(val label: String) : LoadState
    data class Failed(val reason: String) : LoadState
}

@Composable
fun SealedStateCard(state: LoadState = LoadState.Ready("loaded")) {
    Card(Modifier.padding(16.dp)) {
        Box(Modifier.padding(20.dp)) {
            when (state) {
                LoadState.Loading -> Text("Loading…")
                is LoadState.Ready -> Text("✓ ${state.label}", color = Color(0xFF2A9D8F))
                is LoadState.Failed -> Text("✗ ${state.reason}", color = Color(0xFFE63946))
            }
        }
    }
}

// ── 10. ViewModel via multiplatform viewModel() (owner-provider test) ──────
class CounterViewModel : ViewModel() {
    private val _count = MutableStateFlow(42)
    val count: StateFlow<Int> = _count.asStateFlow()
    fun increment() {
        _count.value += 1
    }
}

@Composable
fun ViewModelScreen() {
    // Obtains the VM from LocalViewModelStoreOwner — which an isolated render
    // only has because the renderer now provides a stub owner universally.
    val vm: CounterViewModel = viewModel()
    val count by vm.count.collectAsState()
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ViewModel count: $count", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = vm::increment) { Text("Increment") }
    }
}

// ── 11. Interactive: clickable local-state mutation (interact path) ────────
@Composable
fun InteractiveCounter() {
    var n by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tapped $n times", style = MaterialTheme.typography.titleLarge)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable { n++ }
                .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Text("Tap me", color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

// ── 12. User-defined CompositionLocal with a THROWING default ──────────────
// staticCompositionLocalOf with no default → reading it without a provider
// throws. Exercises UserCompositionLocalsDiscoverer (the renderer must find
// and satisfy this Local from the user's classpath).
val LocalBrandColor = staticCompositionLocalOf<Color> {
    error("LocalBrandColor not provided — must be wrapped in BrandTheme")
}

@Composable
fun BrandedHeader() {
    // Reads the throwing Local directly; only renders if the renderer supplies
    // a value for it. (We also self-provide here as a fallback for normal app
    // use, but the preview tool targets the read path before this provider.)
    val brand = LocalBrandColor.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(brand)
            .padding(20.dp),
    ) {
        Text("Branded", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BrandedHeaderHost() {
    CompositionLocalProvider(LocalBrandColor provides Color(0xFF8338EC)) {
        BrandedHeader()
    }
}

// ── 12b. MediaCard shape: value-class Color param (→ Java path) + a
//        data-class param whose String fields are read inside Text(...).
//        Reproduces MultiTask's MediaCard: the Java invoker must fabricate a
//        REAL MediaItem (non-null fileName/url) via the mock cascade, not a
//        bare Mockito mock that returns null → `Text(text = null)` NPE.
enum class MediaKind { Image, Video }
data class MediaItem(val fileName: String, val url: String, val kind: MediaKind)

@Composable
fun MediaRow(item: MediaItem, accent: Color) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(item.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Text(
            text = if (item.kind == MediaKind.Video) "▶ video" else "▣ image",
            color = if (accent == Color.Unspecified) Color(0xFF6750A4) else accent,
        )
    }
}

// ── 12c. Real-world model shape (NiA NewsResourceCardExpanded): a data class
//        with an INTERNAL primary constructor + a nested data-class list + a
//        third-party (java.time) field, whose String fields are read inside
//        Text(...). The mock engine must build a REAL instance (accessible
//        ctor + externalMocker for java.time), else a null-field fallback NPEs.
data class Author(val name: String, val handle: String)

data class FeedArticle internal constructor(
    val id: String,
    val title: String,
    val summary: String,
    val authors: List<Author>,
    val publishedAt: java.time.Instant,
    val score: Int,
) {
    constructor(title: String) : this("0", title, "", emptyList(), java.time.Instant.EPOCH, 0)
}

@Composable
fun FeedArticleCard(article: FeedArticle) {
    Card(Modifier.padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(article.title, style = MaterialTheme.typography.titleMedium) // NPE if title null
            Text(article.summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // NPE if authors null (bare mock) — must be a real (possibly empty) list.
                article.authors.forEach { Text("· ${it.name}") }
            }
            Text("score: ${article.score}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── 13. Heavy mangling: Color + custom value class + defaults together ─────
@Composable
fun MixedSignature(
    price: Money = Money(4999),
    accent: Color = Color(0xFFFB8500),
    label: String = "Mixed",
) {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(accent))
        Text(label, fontWeight = FontWeight.Bold)
        Text(price.display, color = accent)
    }
}
