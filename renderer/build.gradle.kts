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

    // Mockito 5 (inline mock maker — default since 5.0.0) is what
    // synthesises the Android Context graph at runtime for KMP
    // composables whose only target is `androidMain`. Plain ByteBuddy
    // subclass cannot work here because `android.content.res
    // .AssetManager` is declared `final` and Compose Resources's
    // preview reader path goes through `Context.assets.open(...)`.
    // Mockito's inline mock maker uses bytecode redefinition (via
    // ByteBuddy + JVMTI agent under the hood) to intercept method
    // dispatch on final classes — which is exactly what we need.
    // The renderer already opts into agent loading via
    // `-XX:+EnableDynamicAgentLoading` so Mockito's agent attaches
    // cleanly.
    implementation("org.mockito:mockito-core:5.14.2")
}

application {
    mainClass.set("com.composepreviewpro.renderer.RendererMainKt")
    applicationDefaultJvmArgs = listOf(
        // Backstop for legacy paths where the startScript-injected
        // `-javaagent:` line cannot be honoured — manual `java -cp …`
        // invocation, an obscure shell that mangles `$APP_HOME`,
        // Mockito's inline mock maker that wants its own JVMTI attach
        // for the Android Context stub. Self-attach via the Attach
        // API stays viable as long as these two flags are set. Both
        // flags are no-ops once the primary `-javaagent:` path
        // succeeds, because [com.composepreviewpro.agent.HotReloadAgent.premain]
        // populates the static `instrumentation` field before
        // `AgentLoader.ensureLoaded` ever runs — the loader is
        // idempotent and returns the existing reference.
        "-Djdk.attach.allowAttachSelf=true",
        "-XX:+EnableDynamicAgentLoading",
    )
}

// Inject `-javaagent:$APP_HOME/lib/composepreviewpro-hot-reload-agent.jar`
// into the generated `bin/renderer` / `bin/renderer.bat` start scripts so
// the agent's `premain` hook captures the JVM `Instrumentation` reference
// at startup. This is the JEP 451-safe path: JDK 21+ prints a warning on
// every Attach-API self-load and a future JDK will make self-attach fail
// by default. Loading the agent through `-javaagent:` at JVM start is the
// long-term sanctioned mechanism — same Instrumentation reference, no
// warning, no future breakage.
//
// We cannot inject this through `applicationDefaultJvmArgs` because the
// generated script wraps `DEFAULT_JVM_OPTS` in single quotes (Unix) /
// re-escapes `$` via sed (POSIX `xargs … sed … eval` pipeline), so a
// literal `$APP_HOME` survives verbatim and is passed to the JVM
// un-expanded. Instead we prepend a SECOND `DEFAULT_JVM_OPTS=…`
// assignment in double quotes so `$APP_HOME` (set earlier in the script)
// expands at the assignment line, before the eval pipeline runs.
tasks.startScripts {
    doLast {
        val agentJar = "composepreviewpro-hot-reload-agent.jar"

        // Unix: anchor on the original DEFAULT_JVM_OPTS line generated
        // by the application plugin (it's stable across Gradle versions
        // — only the contents inside the single quotes vary).
        val unix = unixScript
        if (unix.exists()) {
            val text = unix.readText()
            val anchor = Regex("^DEFAULT_JVM_OPTS=.*$", RegexOption.MULTILINE).find(text)
            if (anchor != null) {
                val injection = "\n# [ComposePreviewPro] JEP 451-safe agent load — prepend before eval pipeline.\n" +
                    "DEFAULT_JVM_OPTS=\"-javaagent:\$APP_HOME/lib/$agentJar \$DEFAULT_JVM_OPTS\""
                unix.writeText(text.substring(0, anchor.range.last + 1) + injection + text.substring(anchor.range.last + 1))
            } else {
                throw GradleException("startScripts (Unix) — DEFAULT_JVM_OPTS line not found; cannot inject -javaagent:")
            }
        }

        // Windows .bat: `set DEFAULT_JVM_OPTS=…` line, similar anchor.
        val win = windowsScript
        if (win.exists()) {
            val text = win.readText()
            val anchor = Regex("^set DEFAULT_JVM_OPTS=.*$", RegexOption.MULTILINE).find(text)
            if (anchor != null) {
                val injection = "\r\n@rem [ComposePreviewPro] JEP 451-safe agent load.\r\n" +
                    "set DEFAULT_JVM_OPTS=-javaagent:\"%APP_HOME%\\lib\\$agentJar\" %DEFAULT_JVM_OPTS%"
                win.writeText(text.substring(0, anchor.range.last + 1) + injection + text.substring(anchor.range.last + 1))
            } else {
                throw GradleException("startScripts (Windows) — DEFAULT_JVM_OPTS line not found; cannot inject -javaagent:")
            }
        }
    }
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

// Comprehensive test of the type-mocking cascade (MockEngine →
// ComposableLambdaSynth → ComposeTypeMocks → AdvancedTypeMocks).
// Catches regressions in any single layer plus integration paths.
//   ./gradlew :renderer:typeMockTest
tasks.register<JavaExec>("typeMockTest") {
    group = "verification"
    description = "Exercise every type-mocking layer with positive and negative cases"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.composepreviewpro.renderer.TypeMockTestKt")
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
