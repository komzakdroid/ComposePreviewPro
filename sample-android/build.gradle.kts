/*
 * :sample-android — a REAL Android library module (AGP) used ONLY as a test
 * fixture for the renderer. Its compiled composables + resources let us
 * validate Android-specific render paths that a desktop sample cannot
 * reproduce: painterResource(R.drawable.…), stringResource(R.string.…),
 * WindowInsets, and the android-all native runtime.
 *
 * It is not bundled into the plugin; only :renderer:androidRenderTest consumes
 * its build output.
 */
plugins {
    // AGP 9 ships built-in Kotlin — no separate kotlin.android plugin.
    // An APPLICATION module (not library) so AGP generates the COMPLETE R class
    // set for every dependency (androidx.core.R, etc.) — a library module only
    // generates its own R, leaving ViewCompat's androidx.core.R references
    // unresolved. Real previewed projects (NiA, MultiTask) are app modules, so
    // this matches what the plugin's PreviewService sees in production.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.composepreviewpro.sampleandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.composepreviewpro.sampleandroid"
        minSdk = 24
        targetSdk = 36
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
}

// Emit the full render classpath (this module's compiled classes + R.jar + all
// runtime dependency jars, i.e. the AndroidX Compose AAR classes) to a file so
// :renderer:androidRenderTest can drive the real renderer against real Android
// bytecode + resources — the same shape the plugin's PreviewService assembles.
tasks.register("dumpRenderClasspath") {
    dependsOn("assembleDebug")
    // Resolve dependency artifacts as EXTRACTED classes jars (not raw .aar —
    // a URLClassLoader can't read classes out of an .aar archive). The
    // `android-classes-jar` artifactType triggers AGP's AAR→classes.jar
    // transform, exactly what the IDE/PreviewService consumes.
    val classesView = configurations.named("debugRuntimeClasspath").map { cfg ->
        cfg.incoming.artifactView {
            attributes {
                attribute(
                    org.gradle.api.attributes.Attribute.of("artifactType", String::class.java),
                    "android-classes-jar",
                )
            }
        }.files
    }
    val classesDir = layout.buildDirectory.dir(
        "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
    )
    // App modules generate the full dependency R set; its jar lives under a
    // build-type-specific path that varies by AGP version. Collect every R.jar
    // under intermediates so androidx.core.R etc. are all on the classpath.
    val intermediates = layout.buildDirectory.dir("intermediates")
    val outFile = layout.buildDirectory.file("render-classpath.txt")
    outputs.file(outFile)
    doLast {
        val rJars = intermediates.get().asFile.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jar") }
            .filter { it.path.contains("r_class") || it.name == "R.jar" }
            .map { it.absolutePath }
            .toList()
        val entries = buildList {
            add(classesDir.get().asFile.absolutePath)
            addAll(rJars)
            addAll(classesView.get().files.map { it.absolutePath })
        }
        outFile.get().asFile.writeText(entries.distinct().joinToString("\n"))
    }
}
