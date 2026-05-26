/*
 * :hot-reload-agent — Java Agent JAR for in-place class redefinition.
 *
 * Built as a regular Kotlin/JVM library, but its produced JAR carries
 * Premain-Class / Agent-Class manifest attributes so the JVM accepts it
 * as a java.lang.instrument agent.
 *
 * The renderer self-attaches this agent on startup, captures the
 * Instrumentation reference into a static field, and from then on can
 * swap class bytecodes via [Instrumentation.redefineClasses] without a
 * full classloader recreation. That is what gives us state-preserving
 * hot reload — mutableStateOf values, ScrollState, remember { ... }
 * results all survive because the slot table is keyed off the Method
 * identity, which redefineClasses does NOT change.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // No deps. Agent uses only java.lang.instrument from the JDK.
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "com.composepreviewpro.agent.HotReloadAgent",
            "Agent-Class" to "com.composepreviewpro.agent.HotReloadAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
    archiveBaseName.set("composepreviewpro-hot-reload-agent")
    // No version suffix — the renderer locates this JAR by an unversioned
    // file name when self-attaching at runtime.
    archiveVersion.set("")
}
