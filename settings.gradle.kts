rootProject.name = "ComposePreviewPro"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        // IntelliJ Platform Gradle plugin is hosted on the IJ Maven repo
        // and also mirrored on gradlePluginPortal. We list both for resilience.
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

dependencyResolutionManagement {
    // PREFER_PROJECT lets module-level build.gradle.kts override if needed;
    // anything else falls through to these shared repos.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        google()
        // Compose Multiplatform stable artifacts live here.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // IntelliJ Platform dependencies repository — required by the
        // intellij.platform plugin to resolve IDE artifacts.
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

include(
    ":ipc",
    ":mock-engine",
    ":renderer",
    ":plugin",
    ":sample",
    ":sample-designsystem",
    ":sample-android",
    ":hot-reload-agent",
)
