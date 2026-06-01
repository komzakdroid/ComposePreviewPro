# Changelog

All notable changes to this plugin are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this plugin adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.9] — 2026-06-01

### Fixed
- **Bundled renderer not found in production installs.** Since 0.3.5 the
  plugin resolved its bundled renderer directory via the JVM
  `CodeSource` of one of its own classes. IntelliJ's `PluginClassLoader`
  does not populate a `CodeSource`, so the lookup returned `null` in
  every real install ("Install Plugin from Disk…") and the plugin failed
  with *Renderer launcher not found* — while passing in the sandbox,
  where `runIde` injects `-Dcomposepreviewpro.renderer.home`. Resolution
  now uses the plugin's own `PluginAwareClassLoader.pluginDescriptor
  .pluginPath` (the JetBrains-recommended self-resolution bridge now that
  `PluginManagerCore.getPlugin` is `@ApiStatus.Internal`), with the
  `CodeSource` retained only as a dev/non-plugin-classloader fallback.
  Every resolution miss is logged instead of returning `null` silently.
- **Renderer failed to launch from paths containing spaces.** The
  `-javaagent:` option injected into the Gradle `application` start
  script was not wrapped in escaped inner quotes. Gradle's launcher runs
  `eval "set -- $DEFAULT_JVM_OPTS …"`, which word-splits the value, so a
  space in the install path (e.g. macOS `…/Application Support/…`) split
  the option in two — the JVM received `-javaagent:…/Application` and
  aborted with *Error opening zip file or JAR manifest missing* before
  the IPC handshake. The option is now encoded the same way Gradle
  encodes its own default JVM opts, surviving spaces intact.
- **Composables with default arguments rendered blank.** The Compose
  compiler emits per-parameter default initialisation guarded by a
  synthetic `$default` bitmask; the renderer hard-coded that mask to 0
  ("caller supplied every argument"), so author defaults such as
  `colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()` were
  never applied and a fabricated, transparent value was used instead —
  rendering, for example, an invisible `TopAppBar` with no error. The
  renderer now computes the `$default` mask (shared `ComposableInvoker`
  used by both the offscreen and interactive paths) so omitted parameters
  fall back to their declared defaults. Verified pixel-exact: the default
  container colour now appears in the rendered output.
- **Interactive scroll froze the UI and spawned hundreds of tasks.** Each
  mouse-wheel event spawned its own `Task.Backgroundable`; a trackpad
  momentum-scroll created hundreds of background tasks that all blocked
  on the renderer's single IPC lock and flooded the EDT. Scroll events
  are now coalesced onto a single-thread pump — at most one render in
  flight plus one accumulated delta — while discrete events (clicks) are
  never dropped.

### Changed
- **Optional parameters use their author-declared defaults, not mocks.**
  Real-world composables put styling/config/callbacks in defaulted
  parameters (`colors`, `modifier`, `onClick`); the author's default is
  always visually correct, whereas a fabricated mock can be blank or
  garbage. Only **required** parameters — which carry the content — are
  auto-mocked now. Any optional parameter can still be populated
  explicitly through the parameter-editor panel.
- **Renderer subprocess pinned to the IDE's JBR.** The subprocess is
  spawned with `JAVA_HOME` set to the IDE's own `java.home`, so it runs
  on the same JVM (JBR 21) as the IDE rather than depending on the
  ambient `java`/`JAVA_HOME` — a stale Java 17 on `PATH` previously
  produced an `UnsupportedClassVersionError`.
- **Plugin now requires an IDE restart on install/update/uninstall**
  (`require-restart`). It hosts a long-lived out-of-process renderer
  (Compose Desktop + Skiko native libs) and a JVM agent that cannot be
  safely hot-swapped; a restart guarantees a clean renderer state and
  fresh bundled-path resolution.

### Added
- **Self-describing renderer startup failures.** When the renderer dies
  during the handshake, the error now includes the process exit code and
  a tail of its stderr, instead of surfacing as a cryptic
  `kotlinx.serialization` "Expected JsonObject … JSON input: Error".

## [0.3.8] — 2026-05-29

### Added
- **Inline value class auto-mocking.** Composables with parameters like
  `@JvmInline value class UserId(val id: Long)` used to fall through the
  whole mock cascade to "No mocker registered" — value classes are
  neither `data class` nor `sealed`, so none of the structural fallbacks
  matched. The mock engine now detects `KClass.isValue`, recursively
  mocks the underlying property type, and invokes the primary
  constructor to wrap the value. This covers the very common
  modern-Kotlin pattern of type-safe ID/email/duration wrappers, plus
  user-defined inline classes for measurement units, currency, etc.
- Framework value classes (`androidx.compose.ui.graphics.Color`,
  `kotlin.time.Duration`, `kotlin.UInt`/`ULong`/`UShort`/`UByte`,
  `kotlin.ranges.*`) are explicitly excluded so the specialised
  handlers downstream (`ComposeTypeMocks` for Color/Dp/TextUnit/...,
  `UniversalTypeMocks` for `kotlin.time` and unsigned ints) produce
  semantically correct instances. Color, for example, is a value class
  whose ULong packs RGBA + a colour-space index — passing `ULong(42)`
  to its constructor produces a Color whose `colorSpace` lookup throws
  `ArrayIndexOutOfBoundsException` deep inside the Skia path; the
  exclusion list keeps that path safe while the new branch still
  serves every user-defined inline class.

### Changed
- **Renderer survives malformed wire messages.** The main loop in
  `RendererMain` used to `exitProcess(1)` on any uncaught throw —
  deserialisation failure on a corrupted incoming JSON line, an NPE in
  a handler, anything. The plugin then saw the pipe close, marked the
  subprocess Crashed, and respawned a fresh JVM (~3 s of latency); if
  the offending request was retried verbatim, we entered a permanent
  restart loop. Each loop iteration now has its own error boundary:
  deserialisation errors and handler-level throws produce a
  `PROTOCOL_ERROR` response and the renderer continues serving the
  next request. Only EOF on stdin (plugin disconnected) and an explicit
  `Shutdown` exit the loop.

- **Subprocess-death reader thread no longer leaks.** The I/O thread
  that reads renderer responses with a timeout used to `Thread
  .interrupt()` itself when the budget was exhausted. That does
  **not** unblock a thread parked inside `BufferedReader.readLine()`
  — `readLine()` is a blocking I/O syscall that ignores the Java
  interrupt flag. The daemon thread survived as long as the JVM did,
  holding a reference to the dead subprocess's pipe handle. The
  timeout path now closes the underlying stream from the outside,
  which makes `readLine()` throw `IOException` and lets the thread
  terminate cleanly.

### Fixed
- **Static-init guard around classifier inspection.** Pathological user
  types whose companion-object init block throws — rare but real on
  legacy code paths and on classes that read environment / config in a
  `companion object { init { … } }` — used to bubble the throw out of
  `type.classifier as? KClass<*>` and abort the entire render. The
  classifier lookup now lives inside a try/catch; the offending type is
  reported Unsupported with a clear message and the surrounding
  composable still renders for the well-behaved arguments.

### Internal
- 83/83 type-mocking tests stay green (the new inline-value-class branch
  is regression-safe, and the framework-value-class exclusion list
  matches the existing `ComposeTypeMocks` coverage exactly).
- pluginVerifier "Compatible" against IC-243 (2024.3) and IC-252
  (2025.2) — unchanged from 0.3.7.
- The Live-UI ComposePanel disposal chain (added in 0.3.6) and the JEP
  451 `-javaagent:` injection (added in 0.3.7) carry forward unchanged.

## [0.3.7] — 2026-05-29

### Changed
- **Hot-reload agent loads via `-javaagent:` at JVM startup** instead of
  self-attach via the Attach API. JEP 451 (finalised in JDK 21) prints a
  warning on every dynamic agent load and a future JDK will make it fail
  by default. The Gradle `application` plugin's start-script template
  wraps `DEFAULT_JVM_OPTS` in single quotes (POSIX) and re-escapes `$`
  through its `printf | xargs | sed | eval` pipeline, so a literal
  `$APP_HOME` survives unexpanded. The workaround in
  `renderer/build.gradle.kts` injects a *second*, double-quoted
  `DEFAULT_JVM_OPTS` line in `startScripts.doLast`, prepending the
  `-javaagent:` flag with `$APP_HOME` expanded at runtime (verified
  empirically — the JVM resolves the agent JAR by absolute path before
  `main`). Self-attach via the Attach API remains a fallback for paths
  where the start-script injection cannot be honoured (manual
  `java -cp …` invocation, Mockito's inline mock maker which uses its
  own JVMTI attach for the Android Context stub).

- **Compose Multiplatform 1.10 dependency notation.** The
  `compose.runtime`, `compose.ui`, and `compose.foundation` Gradle DSL
  accessors were deprecated in CMP 1.10 in favour of explicit Maven
  coordinates. `:mock-engine` and `:sample` modules now use typed
  catalog entries (`libs.compose.runtime`, `libs.compose.ui`,
  `libs.compose.foundation`). Same artifacts resolved, no deprecation
  warnings on the build log, and the build won't break when the
  deprecated accessors are removed in CMP 1.11.

### Documented
- **Remote Dev / JetBrains Gateway posture.** Until a 0.4.x Modular
  Plugin V2 refactor splits this plugin into `shared` + `frontend`
  content modules, the platform falls back to loading it on the
  backend host only — the tool window will not appear in a JetBrains
  Client connected to a remote backend. The PNG-streaming render path
  is architecturally remote-friendly already (out-of-process subprocess
  on the backend, PNG bytes shipped via the tool-window content RPC);
  the Live-UI mode needs the frontend module split to ship a Compose
  surface to the client. This is now spelled out in a top-of-file
  comment in `plugin.xml` rather than left as undocumented behaviour.

### Internal
- pluginVerifier "Compatible" against IC-243 (2024.3) and IC-252
  (2025.2) — unchanged from 0.3.6.
- Hot-reload agent semantics unchanged: same `Instrumentation`
  reference captured, same hot-swap pipeline, the only delta is the
  loading mechanism (`premain` vs. runtime `agentmain`).

## [0.3.6] — 2026-05-29

### Changed
- **IntelliJ Platform Gradle Plugin 2.3.0 → 2.16.0.** Migrated to the
  typed `create("IC", "...")` verifier API (the old `ide(...)` helper
  was removed in 2.12). Accommodated the sandbox path move to
  `.intellijPlatform/sandbox/` (AGP plugin 2.12+). Explicitly pinned
  the two verifier IDEs so the auto-applied `recommended()` default
  (new in 2.14) does not surprise the build with an EAP artifact that
  is not yet resolvable from any Maven repo.

- **Constructor-injected `CoroutineScope` (IJPL-83 contract).**
  `HotReloadCoordinator` no longer manually constructs
  `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. The platform
  injects a scope into the service constructor and cancels it
  automatically on project close, plugin disable, or IDE shutdown.
  The VFS message-bus subscription is parented to the same scope via
  the new `MessageBus.connect(CoroutineScope)` overload, so its
  teardown happens on the same signal. We no longer implement
  `Disposable` on this service — there is nothing left to dispose
  manually.

- **Disposable chain for the live-UI panel.** `LivePreviewPanel`
  implements `Disposable`, registered as a child of `PreviewPanel`,
  which is itself parented to the tool window's `Content` disposable
  in `PreviewToolWindowFactory`. On disposal the panel closes its
  cached `URLClassLoader` (releasing user project JARs) and the
  embedded `ComposePanel` (releasing the Skia surface + AWT peer).
  Without this chain, every project switch leaked one Skiko
  framebuffer + the entire production classpath of the previous
  project — visible as monotonically growing memory after a few
  dozen switches.

- **EDT discipline on the live-UI panel.** `show()` and `clear()`
  assert `EDT.assertIsEdt()`. Swing widget mutation and
  `ComposePanel.setContent` are both EDT-only operations, and silent
  off-EDT calls were a latent crash waiting for a stricter platform
  release.

- **Explicit `ModalityState` + project-disposed guard on every
  `invokeLater`.** All twelve panel-update sites in `PreviewService`
  now route through a single `runOnEdt` helper that pins
  `ModalityState.defaultModalityState()` and passes `project.disposed`
  as the runnable's expiration condition. Future modality strictness
  (planned for 2026.3+) will not silently drop render results, and a
  render that completes after the project closes is dropped cleanly
  instead of resurrecting a tearing-down tool window.

### Fixed
- **Bounded log allocation in hot reload.** The "VFS change" log line
  previously stringified every touched file name —
  `files.joinToString(", ") { it.name }` — fine for a single-file edit,
  ruinous for a 4000-file monorepo refactor where a single log call
  could allocate megabytes that nobody ever reads. Capped at 5 names
  plus a `(+N more)` ellipsis.

- **Dead service lookup removed.** `PreviewPanel.installInteraction
  Listeners` had a duplicate `project?.getService(...)` whose result
  was discarded — Kotlin warned about the unnecessary safe call, but
  the deeper bug was a no-op service touch inside the mouse handler.
  Removed.

### Internal
- pluginVerifier remains "Compatible" against IC-243 (2024.3) and
  IC-252 (2025.2) with zero deprecated, internal, or experimental API
  usages flagged — unchanged from 0.3.5.

## [0.3.5] — 2026-05-28

### Fixed
- **Marketplace verifier 2026.2 EAP blockers.** The previous build flunked the
  JetBrains Marketplace verifier against IntelliJ IDEA 2026.2 EAP with 1
  internal-API usage, 4 deprecated-API usages, and 6 experimental-API
  usages. Two root causes, both addressed without changing user-facing
  behaviour:
  - **Internal API.** `RendererProcess.pluginInstallDir()` called
    `PluginManagerCore.getPlugin(PluginId)`, marked `@ApiStatus.Internal`
    in 2026.2. The replacement candidates (`PluginManager.findEnabledPlugin`,
    `PluginManager.getPluginByClass`) are also `@ApiStatus.Internal`, and
    the blessed public `PluginDetailsService` does not expose `pluginPath`.
    Switched to self-resolving via `Class.protectionDomain.codeSource` —
    walks `<plugin-root>/lib/plugin.jar` → `<plugin-root>/`. Pure JVM
    stdlib, zero Platform API, identical behaviour in sandbox and
    production installs.
  - **Deprecated + experimental APIs.** Kotlin (with the default
    `jvmDefault=enable`) emits a synthetic bridge override for every
    default method on the Java interfaces we implement. For
    `ToolWindowFactory` that meant generated bridges for `isApplicable`,
    `isDoNotActivateOnStart`, `getAnchor`, `getIcon`, `manage`,
    `isApplicableAsync`, … — each containing an `invokespecial` to the
    Platform's deprecated/experimental signature, which the verifier
    counted as a plugin-side usage. Set `jvmDefault=NO_COMPATIBILITY` on
    the `:plugin` module's Kotlin compiler so the bridges are no longer
    generated; JVM 8 default-method dispatch resolves the calls at
    runtime.

## [0.1.0] — 2026-05-26

### Added
- **Device-less Compose preview.** Out-of-process renderer (Compose Desktop + `ImageComposeScene`) hosts any `@Composable` and returns a PNG. Long-lived subprocess; median ~50 ms per re-render once warm.
- **Auto-mocked arguments.** Reflection-driven `MockEngine` fabricates values for primitives, data classes, sealed hierarchies, enums, `List<T>`, `Map<K, V>`, `Modifier`, and function types.
- **Hot reload via JVM Instrumentation.** Self-attached agent JAR exposes `Instrumentation.redefineClasses`; `ClasspathSnapshot` diffs `.class` files by mtime + length and ships only the changed payload.
- **Per-element parameter editor.** `🔍 Inspect` toolbar toggle: click an element on the canvas → side panel shows that call's arguments. Edits write back via `KtPsiFactory` inside a `WriteCommandAction`.
- **Positional → named argument resolution.** Inspector resolves the callee declaration via `mainReference.resolve()` and surfaces positional args under their declared parameter name (filtering Compose's synthetic `$composer` / `$changed` / `$default`).
- **Click-to-source navigation.** Two-tier ranker prefers `@Composable` callees (Uppercase) over modifier/factory helpers; navigation lands on the user-visible composable.
- **Builder-chain pretty-printer.** Multi-line `Modifier` chains render in the side panel as a vertically indented tree instead of a horizontal mash-up.
- **ASM bytecode source-mapper.** Bridges the gap in CMP 1.10.3 where tooling-data `Group.location` is unavailable — per-element source nav now resolves ~18–40 hits/composable.
- **Multi-frame device grid.** Phone + tablet + desktop side-by-side.
- **Theme** (`LIGHT` / `DARK`) and **device-size** dropdowns, **zoom** control.
- **Interactive preview.** Click + scroll events round-trip into the live composition.
- **Live UI mode.** Native `ComposePanel` mounted directly in the IDE, no PNG round-trip.
- **AI-assisted mocks.** Optional Claude API toggle for semantically appropriate mock strings.

### Internal
- 5-module Gradle build: `:ipc`, `:mock-engine`, `:renderer`, `:plugin`, `:sample`, plus `:hot-reload-agent`.
- Tests: `:plugin:test` (pure JUnit + BasePlatformTestCase suites), `:renderer:smokeTest`, `:renderer:ipcIntegrationTest`, `:renderer:sourceMapperTest`.

### Known issues
- Several `BasePlatformTestCase` test suites are temporarily `@Ignore`d on
  IntelliJ IDEA 2024.3 + Kotlin K2: the IDE-bundled
  `kotlinx-coroutines-core` 1.8.0 clashes with the 1.10.x version that
  Compose Desktop transitively pulls onto the plugin's runtime classpath
  for Live UI mode. The clash throws `NoSuchMethodError: tryResume` during
  fixture setUp. Production code paths are unaffected (the renderer uses
  an isolated `URLClassLoader`). See
  `CallExpressionInspectorTest`'s KDoc for the full diagnosis and
  re-enable conditions. `ChainFormatterTest`,
  `ClasspathSnapshotTest`, and the renderer's standalone tests
  (`smokeTest`, `ipcIntegrationTest`, `sourceMapperTest`) all run green
  in the meantime.

[Unreleased]: https://github.com/komzakdroid/ComposePreviewPro/compare/v0.3.9...HEAD
[0.3.9]: https://github.com/komzakdroid/ComposePreviewPro/compare/v0.3.8...v0.3.9
[0.3.8]: https://github.com/komzakdroid/ComposePreviewPro/compare/v0.3.7...v0.3.8
[0.3.7]: https://github.com/komzakdroid/ComposePreviewPro/compare/v0.3.6...v0.3.7
[0.3.6]: https://github.com/komzakdroid/ComposePreviewPro/compare/v0.3.5...v0.3.6
[0.3.5]: https://github.com/komzakdroid/ComposePreviewPro/compare/v0.1.0...v0.3.5
[0.1.0]: https://github.com/komzakdroid/ComposePreviewPro/releases/tag/v0.1.0
