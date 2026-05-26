# Compose Preview Pro

**Device-less Jetpack/Compose Multiplatform preview, with auto-mocked arguments, hot reload, and click-to-source navigation — for IntelliJ IDEA & Android Studio.**

Render any `@Composable` function — including ones with complex, custom-typed arguments — without booting an emulator or running the app. Edit a parameter in the side panel and the rendered preview updates in under a second.

---

## Features

| | |
|---|---|
| **Device-less rendering** | Out-of-process Compose Desktop renderer turns any `@Composable` into a PNG. No emulator, no Android target needed — works against KMP `commonMain`. |
| **Auto-mocked arguments** | The mock engine fabricates values for any parameter type — primitives, data classes, sealed hierarchies, enums, `List<T>`, `Map<K, V>`, `Modifier`, function types — recursively. |
| **Hot reload (JVM Instrumentation)** | Save a file → recompile via Gradle → `Instrumentation.redefineClasses` swaps the new bytecode into the live renderer JVM → preview re-renders. Median 51 ms swap. |
| **Per-element parameter editor** | Click an element on the canvas → its source-call arguments appear in an editable side panel. Edits are written back into source via PSI, preserving formatting, comments, and trailing commas. |
| **Positional → named resolution** | The inspector resolves the callee's declaration so positional arguments (`Box(Modifier.size(40.dp))`) are surfaced with their declared parameter name and become editable as `modifier = …`. |
| **Click-to-source navigation** | Click any rendered element → IDE editor jumps to the exact `@Composable` invocation. A two-tier ranker (Composable callees over modifier/factory helpers) keeps navigation on the user-visible call. |
| **Multi-frame device grid** | One toggle to render the same composable simultaneously at phone, tablet, and desktop sizes. |
| **Theme switching** | `LIGHT` / `DARK` dropdown, applied through Material 3's `MaterialTheme`. |
| **Interactive preview** | Click and scroll events round-trip into the running composition — buttons press, lazy lists scroll, state hoists work. |
| **Live UI mode** | An embedded `ComposePanel` mounts the composable directly in the IDE — no PNG round-trip — for state-heavy workflows. |
| **AI-assisted mocks** | Optional Claude API toggle replaces auto-mocked strings with semantically appropriate content. |

---

## How it works

```
┌──────────────────┐                         ┌─────────────────────┐
│   IDE plugin     │       NDJSON / stdio     │  Renderer subprocess │
│                  │  ◀────────────────────▶  │  (Compose Desktop +  │
│  · gutter ▶ icon │                          │   ImageComposeScene) │
│  · tool window   │                          │                      │
│  · params panel  │     hot-swap classes     │  · JVM agent attached│
│  · VFS listener  │  ───────────────────▶    │  · long-lived session│
└──────────────────┘                          └─────────────────────┘
        │
        │  PSI write-back (KtPsiFactory) on parameter edits
        ▼
   user's source
```

Module layout:

| Module | Role |
|---|---|
| `:plugin` | IntelliJ Platform plugin: gutter actions, tool window, parameters panel, PSI inspector, hot-reload coordinator, renderer client. |
| `:renderer` | Long-lived standalone JVM app. Hosts `ImageComposeScene`, loads user classes via `URLClassLoader`, parses bytecode (ASM) for per-element source mapping. |
| `:mock-engine` | Reflection-based argument fabricator. |
| `:ipc` | NDJSON message types (`kotlinx-serialization` sealed hierarchies). |
| `:hot-reload-agent` | Tiny JAR with `Premain-Class`/`Agent-Class` manifest — exposes `Instrumentation` to the renderer. |
| `:sample` | Sample `@Composable` screens used for smoke tests and integration tests. |

---

## Install

### From source (until Marketplace publish)

```bash
git clone https://github.com/komzakdroid/ComposePreviewPro
cd ComposePreviewPro

# JDK 21 required (matches the IntelliJ Platform plugin's target).
export JAVA_HOME=/path/to/openjdk-21

./gradlew :plugin:buildPlugin
# → plugin/build/distributions/plugin.zip
```

Install the `.zip` via **Settings → Plugins → ⚙ → Install Plugin from Disk…** in IntelliJ IDEA Community / Ultimate 2024.3+ or any compatible Android Studio.

### Try it without installing

```bash
./gradlew :plugin:runIde
```

This launches a sandbox IDE with the plugin pre-loaded and the sample module ready.

---

## Usage

1. **Open any file containing a `@Composable` function.** A green ▶ gutter icon appears next to each composable.
2. **Click ▶.** The tool window opens and renders the composable with auto-mocked arguments at the default 360×800 phone size.
3. **Toggle features from the toolbar:**
    - `Device` / `Theme` / `Zoom` dropdowns.
    - `🔍 Inspect` — click on a rendered element to select it.
    - `Multi-frame` — fan out to phone + tablet + desktop sizes.
    - `🟢 Live UI` — mount the composable natively inside the IDE.
4. **Edit a parameter.** With `🔍 Inspect` enabled, click any element. The Parameters panel turns green and shows that call's editable arguments. Edit a value, press Enter — the file is updated via PSI write-back and the preview re-renders.
5. **Navigate to source.** Plain click (Inspect on) or `Cmd/Ctrl+Click` jumps the IDE caret to the exact `@Composable` call line.

---

## Building

| Task | Purpose |
|---|---|
| `./gradlew :plugin:buildPlugin` | Produce `plugin.zip` for distribution. |
| `./gradlew :plugin:runIde` | Launch a sandbox IDE with the plugin loaded. |
| `./gradlew :plugin:verifyPlugin` | JetBrains plugin verifier. |
| `./gradlew :plugin:test` | Plugin unit / integration tests (`BasePlatformTestCase` + pure JUnit). |
| `./gradlew :renderer:installDist` | Build the standalone renderer launcher script. |
| `./gradlew :renderer:smokeTest` | Render every `:sample` composable to `./smoke-out/`. |
| `./gradlew :renderer:ipcIntegrationTest` | End-to-end IPC test against a real renderer subprocess. |
| `./gradlew :renderer:sourceMapperTest` | Verify the ASM source-mapper's JAR-exclusion contract. |

---

## Requirements

- **IntelliJ IDEA Community / Ultimate 2024.3+** (or compatible Android Studio).
- **JDK 21** at build time.
- **Kotlin 2.3.20** target (matches the renderer compiler).

---

## Architecture choices worth knowing

- **Out-of-process renderer.** Hosting Compose Desktop inside the IDE's classloader risks clashing with the bundled Compose used by Layout Inspector. A separate JVM keeps both sides clean and bounded.
- **NDJSON IPC.** Newline-delimited JSON over stdio is portable, debuggable, and avoids GRPC/Socket setup. `kotlinx-serialization` sealed types give us type-safe message dispatch.
- **JVM Instrumentation hot-swap.** The renderer self-attaches a tiny agent JAR via `com.sun.tools.attach.VirtualMachine`, then accepts `RedefineClasses` payloads with the changed bytecode. Median latency: 51 ms.
- **ASM bytecode parser.** Compose Multiplatform 1.10.3 does not expose source positions through tooling-data `Group.location` — so we parse `LineNumberTable` ourselves from the user's `.class` files to recover per-call line numbers.
- **PSI for parameter edits.** Argument writes go through `KtPsiFactory` inside a `WriteCommandAction`, preserving formatting and undo history. Regex-based rewrites would clobber comments / trailing commas.
- **JAR exclusion in the source map.** Only directories (the user's `build/classes/...`) contribute to the bytecode-derived source map. Library `.jar`s are silently skipped — otherwise click-to-source would land on `LazyLayoutSemantics.kt` instead of the user's file.

---

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).

---

## Contributing

PRs are welcome. Please run the test suite locally before sending:

```bash
./gradlew :renderer:sourceMapperTest \
          :renderer:ipcIntegrationTest \
          :plugin:test
```

For UI-affecting changes, attach a screenshot of `./gradlew :plugin:runIde` exercising the new behavior.
