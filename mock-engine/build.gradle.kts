/*
 * :mock-engine — Reflection-based auto-mock library.
 *
 * Given a KType (parameter type of a @Composable), produces a sensible
 * default value so the composable can render without the caller providing
 * real data. MVP supports primitives, () -> Unit lambdas, and Modifier.
 *
 * Depends on Compose runtime ONLY to know what `Modifier` is. Does NOT depend
 * on Compose UI for rendering — keep this layer slim. The CMP plugin's
 * `compose.runtime` / `compose.ui` DSL accessors were deprecated in 1.10;
 * we now use the explicit Maven coordinates from `libs.versions.toml`.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

dependencies {
    // kotlin-reflect is required to inspect KType, KClass, KParameter.
    api(libs.kotlin.reflect)

    // We need Modifier (and only Modifier) from Compose UI to recognise it.
    api(libs.compose.runtime)
    api(libs.compose.ui)
}
