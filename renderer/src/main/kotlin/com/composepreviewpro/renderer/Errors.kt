package com.composepreviewpro.renderer

import com.composepreviewpro.ipc.ErrorKind
import com.composepreviewpro.ipc.ErrorResponse
import java.io.PrintWriter
import java.io.StringWriter

internal fun Throwable.toErrorResponse(
    requestId: String?,
    kind: ErrorKind,
): ErrorResponse {
    val sw = StringWriter()
    printStackTrace(PrintWriter(sw))
    return ErrorResponse(
        requestId = requestId,
        kind = kind,
        message = userFriendlyMessage(this),
        stackTrace = sw.toString(),
    )
}

/**
 * Translate well-known platform throws into a one-sentence cause +
 * actionable fix. The raw `LocalContext not present` exception arrives
 * here when a Kotlin-Multiplatform composable was compiled for the
 * Android target (with `androidx.compose.ui.platform.LocalContext`
 * hard-baked into its call sites) and is then loaded in a JVM-only
 * renderer process. The user-facing message tells them exactly how to
 * fix it — adding a `desktopMain` source set.
 */
private fun userFriendlyMessage(t: Throwable): String {
    val raw = t.message ?: t::class.qualifiedName ?: "Unknown error"
    // Unwrap reflection wrappers to the real cause.
    val root = generateSequence(t) { it.cause }.lastOrNull() ?: t
    val rootMsg = root.message.orEmpty()

    // Whole-chain text — some DI failures hide the tell-tale phrase in a
    // wrapper frame rather than the root cause.
    val chainMsg = generateSequence(t) { it.cause }
        .joinToString(" | ") { it.message ?: it::class.qualifiedName.orEmpty() }

    if (
        rootMsg.contains("No ViewModelStoreOwner") ||
        chainMsg.contains("ViewModelStoreOwner") ||
        chainMsg.contains("KoinApplication has not been started") ||
        chainMsg.contains("No Koin Context") ||
        chainMsg.contains("HiltViewModelFactory") ||
        chainMsg.contains("hiltViewModel") ||
        chainMsg.contains("EntryPointAccessors") ||
        chainMsg.contains("dagger.hilt")
    ) {
        return buildString {
            appendLine("This composable obtains its ViewModel from a DI graph — " +
                "`koinViewModel()`, `hiltViewModel()`, or `viewModel()` with a custom factory. " +
                "An isolated preview has no running Koin/Hilt container and no host Activity, " +
                "so the ViewModel cannot be constructed. (Android Studio's @Preview fails the " +
                "same way for the same reason.)")
            appendLine()
            appendLine("Fix — preview the STATELESS content composable instead of the screen entry. " +
                "The common pattern (which this screen already follows) splits the UI in two:")
            appendLine()
            appendLine("  @Composable")
            appendLine("  fun XxxScreen(viewModel: XxxViewModel = koinViewModel()) {   // ← not previewable")
            appendLine("      val state by viewModel.state.collectAsStateWithLifecycle()")
            appendLine("      XxxContent(state = state, onEvent = viewModel::onEvent)")
            appendLine("  }")
            appendLine()
            appendLine("  @Composable")
            appendLine("  fun XxxContent(state: UiState, onEvent: (Event) -> Unit) { … }  // ← preview THIS")
            appendLine()
            appendLine("Point the preview at `XxxContent` and supply a sample `state`; the plugin " +
                "auto-mocks the state and callbacks. Alternatively, pass a fake ViewModel " +
                "constructed with canned data.")
        }.trim()
    }

    // androidx.compose.ui.res.painterResource(R.drawable.x) / the Android
    // resource table. Off-device we can fabricate strings (LocalResources stub)
    // but not decode a real vector/bitmap drawable — that needs the compiled
    // resources.arsc + native image decoders (layoutlib territory).
    if (chainMsg.contains("PainterResources_android") ||
        chainMsg.contains("VectorResources_android") ||
        chainMsg.contains("ImageResources_android") ||
        (chainMsg.contains("painterResource") && chainMsg.contains("Resources"))
    ) {
        return buildString {
            appendLine("This composable calls painterResource(R.drawable.…) (or imageResource/" +
                "vectorResource), which loads a drawable from the Android resource table. An " +
                "off-device preview can supply string resources but cannot decode a real " +
                "vector/bitmap drawable without the compiled resources.arsc and native image " +
                "decoders.")
            appendLine()
            appendLine("Workarounds:")
            appendLine("  • Guard the image with LocalInspectionMode and show a plain Box/Icon " +
                "placeholder in preview:")
            appendLine("      if (LocalInspectionMode.current) Box(Modifier.size(48.dp)) else " +
                "Image(painterResource(id), …)")
            appendLine("  • Or preview a sibling composable that doesn't load a drawable resource.")
            appendLine()
            appendLine("Everything else in the composable renders — only the drawable load is " +
                "unsupported off-device.")
        }.trim()
    }

    if (rootMsg.contains("LocalContext not present")) {
        return buildString {
            appendLine("Compose Multiplatform Resources (stringResource / painterResource) used " +
                "in this composable was compiled against the ANDROID target, which requires " +
                "an `android.content.Context` that this JVM-only renderer cannot provide.")
            appendLine()
            appendLine("Fix: add a `desktopMain` source set to your Gradle module so the same " +
                "common code is also compiled for the desktop/JVM target. Once that target " +
                "exists, the plugin will redirect to it automatically and the preview works " +
                "without Android-specific stubs.")
            appendLine()
            appendLine("Minimum module/build.gradle.kts addition:")
            appendLine("  kotlin {")
            appendLine("    jvm(\"desktop\")")
            appendLine("    sourceSets.getByName(\"desktopMain\").dependencies {")
            appendLine("      implementation(compose.desktop.currentOs)")
            appendLine("    }")
            appendLine("  }")
        }.trim()
    }
    return raw
}
