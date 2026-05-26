# Changelog

All notable changes to this plugin are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this plugin adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/komzakdroid/ComposePreviewPro/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/komzakdroid/ComposePreviewPro/releases/tag/v0.1.0
