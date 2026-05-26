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

dependencies {
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    // material3 DSL accessor was deprecated in Compose 1.10 — use the
    // explicit Maven coordinate.
    implementation(libs.compose.material3)
}
