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
