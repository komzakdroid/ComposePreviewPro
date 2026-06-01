/*
 * :plugin — IntelliJ Platform plugin (Android Studio + IntelliJ IDEA).
 *
 * Builds a .zip distributable installable into any 2024.3+ IDE. Tasks of
 * interest:
 *   • ./gradlew :plugin:runIde         — launch sandbox IDE with plugin loaded
 *   • ./gradlew :plugin:buildPlugin    — produce distributable .zip
 *   • ./gradlew :plugin:verifyPlugin   — JetBrains compatibility check
 */
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.intellij.platform)
}

// IntelliJ Platform plugin requires its dependencies to be resolved from a
// specific repository set declared via the `intellijPlatform { ... }` block
// inside `repositories`. We also need google() + JetBrains Compose maven
// to resolve the Compose Desktop deps the live-UI panel depends on.
repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":ipc"))
    implementation(libs.kotlinx.serialization.json)
    // CRITICAL: kotlinx-coroutines must be compileOnly. The IntelliJ
    // Platform bundles its own version on every release and uses
    // internal APIs that diverge between minor versions. Bundling our
    // own version causes runtime NoSuchMethodError in
    // CancellableContinuation.tryResume — see comments on
    // IntelliJ Platform Plugin SDK docs / IJPL-150 (post-2024.3).
    compileOnly(libs.kotlinx.coroutines.core)

    // Live UI support — Compose Desktop runtime embedded inside the IDE
    // plugin via androidx.compose.ui.awt.ComposePanel. Adds ~50MB to
    // plugin.zip (Skiko native libraries dominate). The plugin's own
    // classloader holds these — they are NOT shared with the IDE's
    // bundled Compose (Layout Inspector etc.), so no cross-pollination.
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)

    // BasePlatformTestCase ultimately extends junit.framework.TestCase
    // (JUnit 3/4 lineage). The IntelliJ test framework does NOT pull it
    // in transitively — we must declare it. We DELIBERATELY do NOT add
    // `kotlin("test-junit")`: it can pull a kotlinx-coroutines version
    // that conflicts with the one bundled inside the Kotlin IDE plugin
    // and causes TestLoggerAssertionError ("CancellableContinuation
    // .tryResume … [Plugin: org.jetbrains.kotlin]") during fixture
    // setUp. All test code uses plain JUnit assertions instead.
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Target IDE: IntelliJ IDEA Community 2024.3. Android Studio derived
        // from the same platform branch will accept this plugin.
        intellijIdeaCommunity(libs.versions.intellijTarget.get())

        // Required bundled plugins. Kotlin plugin exposes PSI for K2.
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

        // Pluggable verifier and test framework wiring.
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

// JetBrains Marketplace verifier rule: a plugin's bytecode must not
// reference deprecated or @ApiStatus.Internal Platform APIs. By default
// Kotlin generates a synthetic bridge override for every default method
// on the Java interfaces we implement (here: ToolWindowFactory's
// isApplicable, isDoNotActivateOnStart, getAnchor, getIcon, manage,
// isApplicableAsync, …). Each bridge contains an `invokespecial` to
// the platform interface method, and the verifier counts that as a
// usage of the deprecated/experimental API — even though our source
// code never mentions it. NO_COMPATIBILITY drops those bridges and lets
// JVM 8 default-method dispatch resolve the calls at runtime, removing
// every flagged reference from our bytecode.
//
// Safe because the :plugin module is a leaf: nothing else compiles
// against its interfaces, so we cannot break downstream callers by
// changing the default-method ABI shape of our classes.
kotlin {
    compilerOptions {
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

// Make the sandbox IDE launch (./gradlew :plugin:runIde) able to find the
// renderer launcher, and ensure it is built before the IDE starts.
tasks.named<JavaExec>("runIde") {
    dependsOn(":renderer:installDist")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dcomposepreviewpro.renderer.home=${rootDir}/renderer/build/install/renderer")
    })
}

// Bundle the renderer's full installDist tree INSIDE the packaged plugin
// directory so that production installs (Settings → Install Plugin from
// Disk…) include the subprocess launcher + every JAR it needs.
//
// Layout inside plugin.zip after this:
//
//   plugin/
//     ├── lib/             ← IDE plugin JARs (existing)
//     └── renderer/        ← bundled subprocess (NEW)
//          ├── bin/renderer   (the launch script)
//          └── lib/*.jar      (the renderer's classpath)
//
// At runtime, RendererProcess.resolveLauncher() walks two parents up
// from its own JAR (via Class.protectionDomain.codeSource) to find
// this `renderer/` directory — works whether the plugin lives in the
// sandbox (`.intellijPlatform/sandbox/...` since AGP plugin 2.12) or
// the user's production `~/Library/Application Support/<IDE>/plugins/`
// directory.
tasks.named<Sync>("prepareSandbox") {
    dependsOn(":renderer:installDist")
    // The IntelliJ Platform Gradle Plugin's prepareSandbox installs the
    // plugin under `<sandbox>/plugins/<project.name>/`. The same prefix
    // is used inside plugin.zip, so we copy renderer/ alongside lib/
    // under the same `plugin/` root. We hard-code `project.name` here
    // (resolves to "plugin") because the property is reliably
    // available at configuration time — the
    // `intellijPlatform.pluginConfiguration.name` provider is not.
    //
    // The Unix execute bit on the renderer launch scripts MUST be
    // preserved through the prepareSandbox copy AND the subsequent zip,
    // otherwise the user hits "Permission denied" when the plugin tries
    // to spawn the subprocess. We re-apply `0755` to the launchers
    // here; RendererProcess.kt also calls setExecutable(true) at runtime
    // as a final defense for ZIP extractors that drop Unix attrs.
    from("${rootDir}/renderer/build/install/renderer") {
        into("${project.name}/renderer")
        filesMatching(listOf("bin/renderer", "bin/renderer.bat")) {
            permissions { unix("755") }
        }
    }
}

intellijPlatform {
    // Headless IDE startup task we don't need for MVP. It launches the IDE
    // to extract Settings UI searchable strings — irrelevant because we
    // expose no Settings panel yet, and it sometimes fails on first-run
    // sandbox caches.
    buildSearchableOptions = false

    pluginConfiguration {
        id = "com.composepreviewpro"
        name = "Compose Preview Pro"
        version = "0.3.9"

        // Rich Marketplace description. Rendered as HTML on the listing
        // page (jetbrains.com/marketplace) — break paragraphs with <p>,
        // use <ul>/<li> for feature lists, and <code> for identifiers.
        description = """
            <h3>Device-less Jetpack / Compose Multiplatform preview, with auto-mocked arguments, hot reload, and click-to-source navigation.</h3>

            <p>Render any <code>@Composable</code> function — including ones with complex, custom-typed arguments — without booting an emulator or running the app. Edit a parameter in the side panel and the rendered preview updates in under a second.</p>

            <h4>What you get</h4>
            <ul>
              <li><b>Device-less rendering.</b> Out-of-process Compose Desktop renderer turns any <code>@Composable</code> into a PNG. No emulator, no Android target needed — works against KMP <code>commonMain</code>.</li>
              <li><b>Auto-mocked arguments.</b> Primitives, data classes, sealed hierarchies, enums, <code>List&lt;T&gt;</code>, <code>Map&lt;K, V&gt;</code>, <code>Modifier</code>, and function types are fabricated recursively. No more "preview-only" overloads.</li>
              <li><b>Hot reload via JVM Instrumentation.</b> Save the file, Gradle recompiles, and the renderer JVM swaps the new bytecode in ~50&nbsp;ms — no plugin restart, no full rebuild.</li>
              <li><b>Per-element parameter editor.</b> Click an element on the canvas to surface its source-call arguments. Edits write back into source via PSI, preserving formatting, comments, and trailing commas. Positional arguments are resolved to their declared parameter name and become editable as named args.</li>
              <li><b>Click-to-source navigation.</b> A two-tier ranker prefers <code>@Composable</code> callees over modifier / factory helpers, so clicks land on the user-visible composable instead of <code>fillMaxSize</code> or <code>cardElevation</code>.</li>
              <li><b>Multi-frame device grid.</b> One toggle to render the same composable simultaneously at phone, tablet, and desktop sizes.</li>
              <li><b>Theme switching.</b> <code>LIGHT</code> / <code>DARK</code> dropdown applied through Material&nbsp;3's <code>MaterialTheme</code>.</li>
              <li><b>Interactive preview.</b> Click and scroll events round-trip into the running composition — buttons press, lazy lists scroll, state hoists work.</li>
              <li><b>Live UI mode.</b> An embedded <code>ComposePanel</code> mounts the composable directly in the IDE — no PNG round-trip — for state-heavy workflows.</li>
              <li><b>AI-assisted mocks.</b> Optional Claude API toggle replaces auto-mocked strings with semantically appropriate content.</li>
            </ul>

            <h4>Requirements</h4>
            <ul>
              <li>IntelliJ IDEA Community / Ultimate 2024.3+ (or compatible Android Studio).</li>
              <li>Kotlin plugin enabled.</li>
              <li>JDK 21 (build-time).</li>
            </ul>

            <p>Open any file containing a <code>@Composable</code>, click the ▶ in the gutter, and the tool window renders the composable with mocked arguments. Enable <b>🔍 Inspect</b> in the toolbar and click an element to surface its arguments in the side panel.</p>

            <p><a href="https://github.com/komzakdroid/ComposePreviewPro">Source &amp; issue tracker on GitHub</a></p>
        """.trimIndent()

        // Per-version notes shown on the Marketplace "What's new" pane and
        // in the "Updated" tab of the in-IDE Plugins screen. Keep it short
        // and user-facing; technical detail belongs in CHANGELOG.md.
        changeNotes = """
            <h4>0.3.9 — Production-install fixes: renderer launch, default args, smooth scroll</h4>
            <ul>
              <li><b>Bundled renderer is found again in real installs.</b> Since 0.3.5 the plugin located its bundled renderer via the JVM <code>CodeSource</code>, but IntelliJ's <code>PluginClassLoader</code> doesn't populate one — so every "Install from Disk" install failed with <i>Renderer launcher not found</i>. Resolution now goes through the plugin's own <code>PluginAwareClassLoader.pluginDescriptor.pluginPath</code> (the JetBrains-recommended self-resolution path), with the <code>CodeSource</code> kept only as a dev fallback. Every lookup miss is now logged instead of failing silently.</li>
              <li><b>Renderer launches from paths containing spaces.</b> The <code>-javaagent:</code> option injected into the start script wasn't inner-quoted, so a space in the install path (e.g. <code>…/Application Support/…</code>) split the argument and the JVM aborted with <i>Error opening zip file or JAR manifest missing</i> before the IPC handshake. The agent option is now wrapped in escaped quotes the way Gradle encodes its own JVM opts.</li>
              <li><b>Deterministic renderer JVM.</b> The renderer subprocess now always runs under the IDE's own JBR (the same <code>java.home</code> as the IDE), so it no longer depends on whatever <code>java</code> happens to be on the user's <code>PATH</code> (a Java 17 there previously caused an <code>UnsupportedClassVersionError</code>).</li>
              <li><b>Default arguments now render correctly.</b> Composables with defaulted parameters — e.g. <code>colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()</code> — used to render blank because the synthetic Compose <code>${'$'}default</code> bitmask was hard-coded to 0 ("caller supplied everything"), so the author's defaults never applied and a fabricated, transparent value was used instead. The renderer now computes the <code>${'$'}default</code> mask and lets optional parameters fall back to their declared defaults; only <b>required</b> parameters (which carry the content) are auto-mocked. Far higher visual fidelity on real-world Material composables.</li>
              <li><b>Smooth interactive scroll.</b> Scrolling the preview used to spawn one background task per wheel event — a trackpad flick created hundreds of tasks, each blocking on the renderer's IPC lock, freezing the UI. Scroll events are now coalesced onto a single pump ("one render in flight + one accumulated delta"); clicks are never dropped.</li>
              <li><b>Clear startup diagnostics + restart on (un)install.</b> A renderer that dies on startup now reports its exit code and stderr tail instead of a cryptic deserialization error. The plugin is also marked <code>require-restart</code>, since it hosts a long-lived subprocess + JVM agent that can't be safely hot-swapped.</li>
            </ul>

            <h4>0.3.8 — Audit-driven hardening: inline value classes, renderer self-healing</h4>
            <ul>
              <li><b>Inline value classes are now mockable</b> — composables with parameters like <code>fun Profile(id: UserId)</code> where <code>@JvmInline value class UserId(val id: Long)</code> previously fell through every layer of the mock cascade to "No mocker registered". The mock engine now detects <code>KClass.isValue</code>, recursively mocks the underlying property type, and invokes the primary constructor to wrap the value. Framework value classes (<code>androidx.compose.ui.graphics.Color</code>, <code>kotlin.time.Duration</code>, etc.) are intentionally skipped so the specialised handlers downstream produce semantically correct instances instead of bit-packed garbage that crashes Skia.</li>
              <li><b>Renderer survives malformed wire messages</b> — a corrupted RenderRequest (truncated base64, protocol-version skew, transient encoder hiccup) used to call <code>exitProcess(1)</code>, the plugin marked the subprocess Crashed, and respawned a fresh JVM (~3 s of latency) only to potentially retry the same broken request. Now each loop iteration has its own error boundary: deserialisation errors and handler-level throws produce a <code>PROTOCOL_ERROR</code> response and the renderer continues serving the next request. Only EOF on stdin and an explicit <code>Shutdown</code> exit the loop.</li>
              <li><b>Subprocess-death reader unhang</b> — the I/O thread that reads renderer responses with a timeout used to interrupt itself on timeout. <code>Thread.interrupt()</code> does not unblock a thread parked inside <code>BufferedReader.readLine()</code>; the daemon thread stayed alive until JVM exit, holding the dead subprocess's pipe handle. The timeout path now closes the underlying stream from the outside, which makes the readLine() throw <code>IOException</code> and lets the thread terminate cleanly.</li>
              <li><b>Static-init guard around classifier inspection</b> — pathological user types whose companion-object init throws (rare but real on legacy code paths) used to bubble out of <code>type.classifier as? KClass&lt;*&gt;</code> and abort the entire render. They now report Unsupported with a clear message and the surrounding composable still renders for the well-behaved arguments.</li>
              <li>Regression-safe: the 83-test type-mocking suite remains all-green, and pluginVerifier stays Compatible against IC-243 + IC-252.</li>
            </ul>

            <h4>0.3.7 — JEP 451 hot-reload, Compose 1.10 dependency notation, remote-dev posture</h4>
            <ul>
              <li><b>Hot-reload agent now loads via <code>-javaagent:</code> at JVM startup</b> instead of self-attach via the Attach API. JDK 21's JEP 451 prints a warning on every self-load and a future JDK will make it fail by default. The Gradle <code>application</code> plugin's start-script template wraps <code>DEFAULT_JVM_OPTS</code> in single quotes (POSIX) and re-escapes <code>$</code> through its <code>printf | xargs | sed | eval</code> pipeline, so a literal <code>${'$'}APP_HOME</code> survives unexpanded — we work around this by injecting a second double-quoted <code>DEFAULT_JVM_OPTS</code> line in <code>startScripts.doLast</code>, prepending the <code>-javaagent:</code> flag with <code>${'$'}APP_HOME</code> properly expanded at runtime. Self-attach remains a fallback for paths where the start-script injection cannot be honoured (manual <code>java -cp …</code>, smoke tests, Mockito's inline mock maker).</li>
              <li><b>Compose Multiplatform 1.10 deprecations cleared.</b> The <code>compose.runtime</code> / <code>compose.ui</code> / <code>compose.foundation</code> Gradle DSL accessors were deprecated in CMP 1.10 in favour of explicit Maven coordinates. <code>:mock-engine</code> and <code>:sample</code> now use the typed catalog entries (<code>libs.compose.runtime</code>, <code>libs.compose.ui</code>, <code>libs.compose.foundation</code>) — same artifacts, no deprecation warnings, no surprise breakage in CMP 1.11.</li>
              <li><b>Remote Dev posture documented.</b> Until a 0.4.x Modular Plugin V2 refactor, the plugin runs on the backend host in JetBrains Gateway split mode (tool window absent on the JetBrains Client). The PNG-streaming render path is architecturally remote-friendly already; the Live-UI mode needs the frontend module split to ship a Compose surface to the client. <code>plugin.xml</code> now spells this out in a top-of-file comment.</li>
              <li><b>No user-visible behaviour change on the desktop.</b> pluginVerifier still "Compatible" against IC-243 (2024.3) and IC-252 (2025.2). The agent loads identically — only the path changes from self-attach to premain.</li>
            </ul>

            <h4>0.3.6 — Platform 2026 hardening: AGP plugin upgrade, modern service lifecycle, EDT discipline</h4>
            <ul>
              <li><b>IntelliJ Platform Gradle Plugin 2.3.0 → 2.16.0.</b> Thirteen minor versions of catch-up. Migrated to the typed <code>create("IC", "...")</code> verifier API, accommodated the sandbox move to <code>.intellijPlatform/sandbox</code>, and explicitly pinned verifier IDEs so the new auto-applied <code>recommended()</code> default doesn't surprise us with an unresolvable EAP artifact.</li>
              <li><b>Constructor-injected <code>CoroutineScope</code>.</b> <code>HotReloadCoordinator</code> no longer creates its own <code>CoroutineScope(SupervisorJob + IO)</code>; it now receives a scope from the platform via the IJPL-83 service-injection contract. Cancellation is automatic on project close / plugin disable / IDE shutdown. The VFS message-bus subscription is parented to the same scope via the <code>connect(CoroutineScope)</code> overload, so it tears down on the same signal. Net effect: zero manual lifecycle code, zero leak vectors.</li>
              <li><b>Proper <code>Disposable</code> chain on the live-UI panel.</b> <code>LivePreviewPanel</code> implements <code>Disposable</code>, registered as a child of <code>PreviewPanel</code>, which is itself parented to the tool window's <code>Content</code>. On disposal the embedded <code>ComposePanel</code> releases its Skia surface + AWT peer and the cached <code>URLClassLoader</code> is closed — previously these leaked on every project switch.</li>
              <li><b>EDT discipline.</b> <code>show()</code> and <code>clear()</code> on the live panel now assert <code>EDT.assertIsEdt()</code> — Swing mutation and <code>ComposePanel.setContent</code> are both EDT-only, and silent off-EDT calls were a latent crash waiting for a stricter platform release.</li>
              <li><b>Explicit <code>ModalityState</code> + project-disposed guard on every <code>invokeLater</code>.</b> All 12 panel-update sites in <code>PreviewService</code> now route through a single <code>runOnEdt</code> helper that pins <code>ModalityState.defaultModalityState()</code> and supplies <code>project.disposed</code> as the expiration condition. Future strictness in platform modality enforcement (planned for 2026.3+) won't silently drop our render results, and renders that complete after the project closes are dropped cleanly instead of resurrecting a half-disposed tool window.</li>
              <li><b>Bounded log allocation.</b> Hot-reload's "VFS change" log line previously joined every touched file name into one string — fine for editing one file, ruinous for a 4000-file monorepo refactor. Capped at 5 names plus "(+N more)".</li>
              <li><b>Pure cleanup; no user-visible behaviour change.</b> Both pluginVerifier and the test suite remain green on IC-243 (2024.3) and IC-252 (2025.2) with zero deprecated, internal, or experimental API usages flagged.</li>
            </ul>

            <h4>0.3.5 — Marketplace verifier 2026.2 compatibility</h4>
            <ul>
              <li><b>Internal API removed.</b> The bundled-renderer lookup no longer calls <code>PluginManagerCore.getPlugin(PluginId)</code> (marked <code>@ApiStatus.Internal</code> in 2026.2). It now self-resolves the plugin install directory via the JVM <code>CodeSource</code> of one of our own classes — pure stdlib, zero IntelliJ Platform API, future-proof against further internal-API churn.</li>
              <li><b>Synthetic bridge methods eliminated.</b> The plugin module now compiles with <code>jvm-default=no-compatibility</code>. Previously, Kotlin emitted bridge overrides for every default method on <code>ToolWindowFactory</code> (<code>isApplicable</code>, <code>isDoNotActivateOnStart</code>, <code>getAnchor</code>, <code>getIcon</code>, <code>manage</code>, …). Each bridge's <code>invokespecial</code> referenced a deprecated or experimental Platform API and the Marketplace verifier counted it as a usage by us. JVM 8 default-method dispatch now handles them transparently.</li>
              <li><b>Net effect:</b> 1 internal + 4 deprecated + 6 experimental API usages on 2026.2 EAP → 0/0/0. No user-facing behavioural change; pure verifier hygiene to unblock Marketplace publishing.</li>
            </ul>

            <h4>0.1.8 — Mockito inline mock maker for Android Context stub (final-class fix)</h4>
            <ul>
              <li>0.1.6/0.1.7 tried to build the Context stub with plain ByteBuddy subclassing, which crashed at <code>android.content.res.AssetManager</code> because that class is <code>final</code>. Switched to Mockito 5's inline mock maker, which uses JVMTI class redefinition to intercept methods on final classes — the AssetManager mock now works and Compose Resources' preview path can call <code>context.assets.open(path)</code> successfully (routed to <code>ClassLoader.getResourceAsStream</code>).</li>
              <li>Added detailed diagnostic logging at every step of the stub creation so future failures surface immediately instead of bubbling up as a generic <code>LocalContext not present</code>.</li>
            </ul>

            <h4>0.1.6 — Synthetic Android Context for KMP projects with no desktop target</h4>
            <ul>
              <li><b>The big one:</b> the renderer now generates a runtime <code>android.content.Context</code> stub via ByteBuddy and provides it through <code>LocalContext</code>, letting Compose Multiplatform Resources (<code>stringResource</code>, <code>painterResource</code>) work even when the user's Gradle module has only an <code>androidMain</code> target. Asset reads route to the URLClassLoader, so resources packaged into the user's JARs (<code>composeResources/&lt;module&gt;/values/...</code>) load cleanly.</li>
              <li>No-op for non-Android scenarios — non-existent <code>android.content.Context</code> on the classpath means we skip the stub entirely.</li>
            </ul>

            <h4>0.1.5 — Smarter KMP target ranking + user-friendly Android-only diagnostics</h4>
            <ul>
              <li>Module probing now considers many KMP target names (<code>desktopMain</code>, <code>jvmMain</code>, <code>desktopAndAndroidMain</code>, <code>skikoMain</code>, <code>nonAndroidMain</code>) plus fuzzy matching on <code>desktop</code>/<code>jvm</code>/<code>skiko</code> substrings. Hits sorted: pure-JVM first, Android as last resort with a logged warning.</li>
              <li>If the only available target is <code>androidMain</code> and the composable uses Compose Resources, the renderer now emits an actionable error explaining how to add a <code>desktopMain</code> source set, instead of dumping a raw <code>CompositionLocal LocalContext not present</code> stack trace.</li>
            </ul>

            <h4>0.1.4 — Kotlin Multiplatform commonMain support</h4>
            <ul>
              <li>Composables in a KMP <code>commonMain</code> source set used to fail with <i>TARGET_NOT_FOUND</i> because the commonMain module's compile output is metadata KLIB, not JVM bytecode. The plugin now redirects to a sibling JVM target (<code>desktopMain</code> → <code>jvmMain</code> → <code>androidMain</code>) at render time and uses ITS classpath instead.</li>
              <li>No-op for single-target JVM or Android-only projects.</li>
            </ul>

            <h4>0.1.3 — Renderer bundled inside the plugin (production-install fix)</h4>
            <ul>
              <li><b>Critical:</b> 0.1.0–0.1.2 only worked when launched from the source tree because the renderer subprocess (Compose Desktop host) wasn't packaged inside <code>plugin.zip</code>. Users who installed via "Install from Disk" hit <i>Renderer launcher not found</i>.</li>
              <li>0.1.3 bundles the full renderer install (<code>bin/renderer</code> + all classpath JARs, ~40&nbsp;MB) inside the plugin's own install directory and resolves it via <code>PluginManagerCore.getPlugin(...).pluginPath</code>.</li>
            </ul>

            <h4>0.1.2 — Wider gutter-icon coverage</h4>
            <ul>
              <li>▶ icon now appears on <code>@Composable</code> functions that live inside an <code>object</code> or <code>companion object</code>, not just top-level ones — covers common Material-design patterns (e.g. <code>NiaIcons</code>, <code>MaterialTheme</code>-style singletons).</li>
              <li>Skipped explicitly (require a receiver instance): extension functions, <code>class</code> members, anonymous <code>@Composable</code> lambdas.</li>
            </ul>

            <h4>0.1.1 — Kotlin K2 mode compatibility</h4>
            <ul>
              <li>Declare <code>supportsKotlinPluginMode supportsK2="true"</code> — the plugin now loads in Android Studio K2 mode (and IntelliJ IDEA 2024.3+) without the "incompatible plugin" warning.</li>
              <li>No code changes: our PSI usage was already K1/K2-agnostic; only the explicit manifest declaration was missing.</li>
            </ul>

            <h4>0.1.0 — Initial public release</h4>
            <ul>
              <li>Device-less Compose preview via out-of-process renderer.</li>
              <li>Auto-mocked arguments (data class, sealed, enum, List, Map, Modifier, lambdas).</li>
              <li>Hot reload via JVM Instrumentation (~50&nbsp;ms swap).</li>
              <li>Per-element parameter editor with PSI write-back.</li>
              <li>Positional → named argument resolution.</li>
              <li>Click-to-source navigation with composable-tier ranking.</li>
              <li>Multi-frame device grid, theme + zoom controls, interactive preview, live UI mode.</li>
              <li>Optional AI mock-string generation via Anthropic Claude.</li>
            </ul>
        """.trimIndent()

        vendor {
            name = "Compose Preview Pro"
            email = "komzak080@gmail.com"
            url = "https://github.com/komzakdroid/ComposePreviewPro"
        }
        ideaVersion {
            // 243 = 2024.3.x. Leave untilBuild unset (null provider) so the
            // descriptor omits the <until-build> tag entirely — JetBrains
            // treats that as "compatible with every future build" and the
            // plugin verifier accepts it. An empty-string value, in
            // contrast, fails verifier with "until-build does not match
            // the multi-part build number format".
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    // Plugin signing — required for JetBrains Marketplace distribution.
    // Values come from environment variables so no secret material lives in
    // version control; in CI they map to GitHub Actions repository secrets
    // (see .github/workflows/release.yml). Generate the chain + key per the
    // SDK guide: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    //   • CERTIFICATE_CHAIN      — full PEM chain (begins -----BEGIN CERTIFICATE-----)
    //   • PRIVATE_KEY            — PEM private key (begins -----BEGIN … PRIVATE KEY-----)
    //   • PRIVATE_KEY_PASSWORD   — passphrase the key was encrypted with
    // When these are unset (ordinary local `buildPlugin`/`runIde`) the
    // signPlugin task simply isn't invoked, so day-to-day builds are
    // unaffected; only `signPlugin`/`publishPlugin` need them.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Marketplace publishing. PUBLISH_TOKEN is a Marketplace permanent token
    // (Profile → My Tokens). The release channel defaults to "default"
    // (Stable); set PUBLISH_CHANNEL=beta or =eap to push a pre-release to a
    // non-stable channel without a code change.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.environmentVariable("PUBLISH_CHANNEL")
            .map { listOf(it) }
            .orElse(listOf("default"))
    }

    pluginVerification {
        ides {
            // AGP plugin 2.12+ removed the deprecated `ide("IC", "...")`
            // helper in favour of the typed `create(...)` form.
            //
            // AGP plugin 2.14+ auto-applies `recommended()` if nothing
            // is configured here. We DON'T want that: `recommended()`
            // pulls "latest + previous" from JetBrains's recommendation
            // list, which can include a major version not yet published
            // to the Maven repo the verifier uses — breaking the build
            // for every developer with a `Could not find idea:ideaIC:
            // <version>` error.
            //
            // We pin two checkpoints:
            //   • 2024.3 — our `sinceBuild`, the floor we must support.
            //   • 2025.2 — the latest stable line, what most users run.
            //
            // 2026.2 EAP (the build that originally flunked 0.3.4 with
            // 1 internal, 4 deprecated, 6 experimental API usages) is
            // intentionally omitted here: EAP artifacts are not in any
            // Maven repo we can resolve from, so adding it would break
            // the build for everyone. The fix is structural — we removed
            // every flagged reference from our bytecode (jvm-default=
            // no-compatibility kills the synthetic ToolWindowFactory
            // bridges; CodeSource self-resolution avoids the internal
            // PluginManagerCore API) — so passing on 2024.3 and 2025.2
            // implies passing on 2026.2 too. Confirm via the Marketplace
            // verifier dashboard after publishing.
            create("IC", "2024.3")
            create("IC", "2025.2")
        }
    }
}
