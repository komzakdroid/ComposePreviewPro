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

// ── Real Android runtime (off the main classpath) ──────────────────────
//
// `org.robolectric:android-all` is the AOSP framework compiled to REAL
// bytecode — the same jar Robolectric/Paparazzi use to run Android code
// off-device. The renderer prepends it to the *user-code* classloader at
// runtime (see ComposableResolver / AndroidRuntimeProvisioner) so that
// android.* classes resolve to real method bodies instead of the SDK
// `android.jar` stub's `throw new RuntimeException("Stub!")`.
//
// It is deliberately kept OUT of the renderer's own runtime classpath (its
// own configuration, not `implementation`) so android-all's transitive
// framework packages (org.json, legacy apache-http, kxml, …) never collide
// with Skiko / kotlinx on the renderer's parent classloader. We only ship
// the single jar into the install image and inject it into the child loader.
//
// Bump the coordinate to track newer platforms; any published
// `android-all` (or `android-all-instrumented`) version works.
val androidRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    androidRuntime("org.robolectric:android-all:14-robolectric-10818077")
}

// Bundle the resolved android-all jar into installDist under
// `$APP_HOME/android-runtime/`, a sibling of `lib/`. AndroidRuntimeProvisioner
// derives this location from the renderer jar's own code source at runtime.
distributions {
    named("main") {
        contents {
            from(androidRuntime) {
                into("android-runtime")
            }
        }
    }
}

// The in-process verification tasks (smokeTest, typeMockTest) run the
// renderer on this module's runtimeClasspath, so there is no installDist
// image to discover the bundled jar in. Point them at the resolved file via
// the system property AndroidRuntimeProvisioner checks first. Resolution is
// deferred to execution time so configuring an unrelated task never forces a
// network fetch of android-all.
fun JavaExec.useBundledAndroidRuntime() {
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            // Keep this key in sync with AndroidRuntimeProvisioner.PROPERTY
            // (cannot be referenced here — it lives in the module's own source).
            val jar = androidRuntime.files.firstOrNull { it.name.startsWith("android-all") }
            if (jar != null) {
                listOf("-Dcomposepreviewpro.androidRuntimeJar=${jar.absolutePath}")
            } else {
                emptyList()
            }
        },
    )
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
                // CRITICAL — the whole `-javaagent:…` option must be wrapped
                // in ESCAPED inner double-quotes inside DEFAULT_JVM_OPTS.
                // Gradle's start script later runs
                //   eval "set -- $DEFAULT_JVM_OPTS $JAVA_OPTS …"
                // which word-splits the value on whitespace. $APP_HOME for a
                // production install is e.g.
                //   /Users/<user>/Library/Application Support/Google/…/renderer
                // — the space in "Application Support" splits the bare option
                // into `-javaagent:/Users/<user>/Library/Application` and a
                // stray `Support/…/agent.jar` token, so the JVM aborts with
                //   Error opening zip file or JAR manifest missing : …/Application
                // before main() and the IPC handshake never happens. The
                // escaped-quote form (the same encoding Gradle uses for its
                // own DEFAULT_JVM_OPTS tokens) keeps the spaced path as ONE
                // argument through the eval. Do NOT "simplify" the quoting.
                val injection = "\n# [ComposePreviewPro] JEP 451-safe agent load — prepend before eval pipeline.\n" +
                    "DEFAULT_JVM_OPTS=\"\\\"-javaagent:\$APP_HOME/lib/$agentJar\\\" \$DEFAULT_JVM_OPTS\""
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
    useBundledAndroidRuntime()
}

// :sample's full runtime classpath (its classes + ALL transitive deps), so
// the stress battery resolves user-code dependencies — lifecycle-viewmodel-
// compose, coroutines, etc. — exactly as the plugin's PreviewService does in
// production. Without this the renderer's URLClassLoader only sees :sample's
// own classes and ViewModel()/CompositionLocal targets fail to link.
val sampleRuntimeClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    sampleRuntimeClasspath(project(":sample"))
}

// Systematic off-device Android native fix: install NativeMethodNeutralizer,
// load android-all's native-heavy framework classes, assert no
// UnsatisfiedLinkError + Build.VERSION.SDK_INT is sane.
//   ./gradlew :renderer:nativeTest
tasks.register<JavaExec>("nativeTest") {
    group = "verification"
    description = "Verify framework-native neutralisation against the bundled android-all runtime"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.composepreviewpro.renderer.NativeNeutralizerTestKt")
    workingDir = rootDir
    standardOutput = System.out
    errorOutput = System.err
    // Self-attach the JVMTI agent so AgentLoader can hand us Instrumentation.
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    useBundledAndroidRuntime()
}

// End-to-end render of REAL Android composables (AGP-compiled :sample-android,
// AndroidX Compose + R class + resources) — validates stringResource,
// painterResource(R.drawable), and WindowInsets off-device.
//   ./gradlew :renderer:androidRenderTest
tasks.register<JavaExec>("androidRenderTest") {
    group = "verification"
    description = "Render real AGP-compiled Android composables through the full path"
    dependsOn(":sample-android:dumpRenderClasspath")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.composepreviewpro.renderer.AndroidRenderTestKt")
    workingDir = rootDir
    standardOutput = System.out
    errorOutput = System.err
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    useBundledAndroidRuntime()
}

// Hard-mode render battery through the FULL InteractiveSession path:
// value-class mangling, deep nesting, complex state, lazy lists, viewModel(),
// interaction, user CompositionLocals. Renders :sample/StressComposables.kt
// to ./stress-out/*.png.
//   ./gradlew :renderer:stressTest
tasks.register<JavaExec>("stressTest") {
    group = "verification"
    description = "Render the hard-mode composable battery via InteractiveSession"
    dependsOn(":sample:classes", ":sample-designsystem:classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.composepreviewpro.renderer.StressTestKt")
    workingDir = rootDir
    standardOutput = System.out
    errorOutput = System.err
    useBundledAndroidRuntime()
    // Prepend the design-system module's class-output DIRECTORY (not its jar)
    // so UserCompositionLocalsDiscoverer — which scans dir roots and skips
    // jars — can find LocalPalette defined in that separate module. This
    // mirrors how the plugin's PreviewService adds sibling-module class dirs.
    val dsClassesDir = project(":sample-designsystem")
        .layout.buildDirectory.dir("classes/kotlin/main")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            val entries = listOf(dsClassesDir.get().asFile.absolutePath) +
                sampleRuntimeClasspath.files.map { it.absolutePath }
            listOf("-DsampleClasspath=${entries.joinToString(File.pathSeparator)}")
        },
    )
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
    useBundledAndroidRuntime()
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
