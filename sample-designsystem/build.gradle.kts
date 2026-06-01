/*
 * :sample-designsystem — a shared design-system module, mirroring the
 * `:core:designsystem` shape of a real multi-module app (MultiTask's
 * MultiTaskTheme / aurora colors / GradientCard). Exists to prove the
 * renderer resolves cross-module user types, theme CompositionLocals, and
 * shared widgets — not just single-module composables.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

composeCompiler {
    includeSourceInformation.set(true)
}

dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
