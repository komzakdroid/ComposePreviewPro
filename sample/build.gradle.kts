/*
 * :sample — Throwaway composables used to manually verify the preview plugin.
 *
 * Compiles to a directory of .class files that :renderer can load through
 * URLClassLoader. Has no main() — it is only a class library at this stage.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Emit per-composable source positions into the compiled bytecode so the
// renderer's Layout-Inspector tree walker can map clicks → file:line.
composeCompiler {
    includeSourceInformation.set(true)
}

// The CMP plugin's `compose.runtime` / `compose.foundation` / `compose.ui` /
// `compose.material3` DSL accessors were deprecated in 1.10 in favour of
// explicit Maven coordinates, declared in `libs.versions.toml`.
dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
