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

// Make the sandbox IDE launch (./gradlew :plugin:runIde) able to find the
// renderer launcher, and ensure it is built before the IDE starts.
tasks.named<JavaExec>("runIde") {
    dependsOn(":renderer:installDist")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dcomposepreviewpro.renderer.home=${rootDir}/renderer/build/install/renderer")
    })
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
        version = "0.1.0"

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
    pluginVerification {
        ides {
            // Pin to the IDE we explicitly target (243 = 2024.3.x).
            //
            // `recommended()` pulls "latest + previous" from JetBrains's
            // recommendation list, which currently includes a major
            // version (2025.3.x at the time of writing) that is not yet
            // published to the Maven repo the verifier uses. That breaks
            // the build for every developer with a `Could not find
            // idea:ideaIC:2025.3` error. Pinning by version is the
            // reproducible alternative.
            ide("IC", "2024.3")
        }
    }
}
