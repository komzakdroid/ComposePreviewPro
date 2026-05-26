/*
 * :renderer — Headless Compose Desktop host process.
 *
 * Standalone JVM app. Reads RenderRequest from stdin → loads user's compiled
 * classes via URLClassLoader → finds the @Composable function via reflection
 * → uses :mock-engine to fabricate arguments → renders inside a Compose
 * Desktop window → captures the surface as PNG bytes → writes RenderResult
 * to stdout.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    application
}

dependencies {
    implementation(project(":ipc"))
    implementation(project(":mock-engine"))
    // The agent JAR ships alongside the renderer in installDist's lib/.
    // The renderer self-attaches it via the Attach API so that
    // Instrumentation.redefineClasses() becomes available at runtime.
    implementation(project(":hot-reload-agent"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlin.reflect)
    // AI mock client uses java.net.http (Java 11+); kotlinx-serialization
    // is already pulled in via :ipc but declared explicitly here for the
    // Anthropic Messages API JSON formatting.
    implementation(libs.kotlinx.serialization.json)

    // Full Compose Desktop stack — window, material, runtime, foundation.
    // ImageComposeScene lives in androidx.compose.ui (ui-desktop artifact)
    // and is pulled in transitively via compose.desktop.currentOs.
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.data)
    // ASM for off-line bytecode parsing of Compose source-info strings.
    implementation(libs.asm.core)
    implementation(libs.asm.tree)
}

application {
    mainClass.set("com.composepreviewpro.renderer.RendererMainKt")
    // Self-attach requires `jdk.attach.allowAttachSelf=true` since Java 9.
    // The attach API is module `jdk.attach`, on the platform classloader,
    // available in standard JREs without further `--add-modules`.
    applicationDefaultJvmArgs = listOf(
        "-Djdk.attach.allowAttachSelf=true",
        // Silences the "Dynamic loading of agents will be disallowed in a
        // future release" warning. We deliberately load the hot-reload
        // agent at runtime via the Attach API; this flag is the JVM's
        // sanctioned way to opt in.
        "-XX:+EnableDynamicAgentLoading",
    )
}

// Standalone smoke test that bypasses IPC and writes PNGs to ./smoke-out/.
// Forks a fresh JVM with the runtime classpath; no stdin/stdout coupling.
tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Render every :sample composable to ./smoke-out/*.png"
    dependsOn(":sample:classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.composepreviewpro.renderer.SmokeTestKt")
    workingDir = rootDir
    standardOutput = System.out
    errorOutput = System.err
}

// Unit test for ComposeSourceMapper's classpath-filtering contract.
// Synthesises a user .class + a library .jar and verifies the JAR is
// silently dropped — the regression that caused click-to-source to land
// in LazyLayoutSemantics.kt instead of the user's file.
//   ./gradlew :renderer:sourceMapperTest
tasks.register<JavaExec>("sourceMapperTest") {
    group = "verification"
    description = "Verify ComposeSourceMapper skips .jar files on the classpath"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.composepreviewpro.renderer.SourceMapperTestKt")
    workingDir = rootDir
    standardOutput = System.out
    errorOutput = System.err
}

// End-to-end IPC integration test. Spawns the *real* renderer launcher via
// stdio and exercises Hello → multiple RenderRequests → Shutdown.
//   ./gradlew :renderer:ipcIntegrationTest
tasks.register<JavaExec>("ipcIntegrationTest") {
    group = "verification"
    description = "Full IPC round-trip test against a long-lived renderer subprocess"
    dependsOn(":sample:classes", "installDist")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.composepreviewpro.renderer.IpcIntegrationTestKt")
    workingDir = rootDir
    standardOutput = System.out
    errorOutput = System.err
}
