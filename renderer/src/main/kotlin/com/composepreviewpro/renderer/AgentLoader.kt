package com.composepreviewpro.renderer

import com.composepreviewpro.agent.HotReloadAgent
import java.io.File
import java.lang.instrument.Instrumentation
import java.lang.management.ManagementFactory

/**
 * Loads the hot-reload Java Agent into the currently running JVM via the
 * Attach API, then exposes the [Instrumentation] reference the agent
 * captured.
 *
 * **Loading order (2026+)**: the primary path is `-javaagent:` at JVM
 * startup, injected into `bin/renderer` / `bin/renderer.bat` by the
 * `startScripts.doLast` block in `renderer/build.gradle.kts`. The
 * agent's [com.composepreviewpro.agent.HotReloadAgent.premain] hook
 * runs before `main()`, captures the [Instrumentation] reference into a
 * static field, and [ensureLoaded] short-circuits on the first line
 * because `HotReloadAgent.instrumentation` is already non-null. No
 * Attach API call is made in this case — JEP 451 stays happy and the
 * "Dynamic loading of agents will be disallowed" warning is silenced
 * because no dynamic loading is performed.
 *
 * **Self-attach is now the fallback** for legacy paths where the
 * startScript injection cannot be honoured: manual `java -cp …`
 * invocation, an obscure shell that mangles `$APP_HOME`, or a smoke
 * test that runs the renderer through Gradle's classpath rather than
 * the installed launcher. The renderer still ships
 * `-Djdk.attach.allowAttachSelf=true` and `-XX:+EnableDynamicAgentLoading`
 * in `applicationDefaultJvmArgs` so this fallback remains viable on
 * JDK 21+.
 *
 * Prerequisites for the fallback path:
 *   • `-Djdk.attach.allowAttachSelf=true` (Java 9+) — enables a process
 *     to attach to its own PID. Configured in renderer/build.gradle.kts.
 *   • `-XX:+EnableDynamicAgentLoading` (Java 21+) — opts in to the JEP 451
 *     transitional warning suppression. Also configured in build.gradle.kts.
 *   • The `jdk.attach` JDK module on the platform classloader — present
 *     in every standard JRE.
 */
object AgentLoader {

    /**
     * @return the Instrumentation instance after self-attach, or null if
     *         the agent could not be loaded (Attach API missing, JRE
     *         without jdk.attach, etc.). Callers must treat null as a
     *         signal to fall back to URLClassLoader recreation for
     *         "reloads".
     */
    fun ensureLoaded(): Instrumentation? {
        // Idempotent: if a previous call (or `-javaagent:`) already set
        // the static field, return it immediately.
        HotReloadAgent.instrumentation?.let { return it }

        val agentJar = locateAgentJar() ?: run {
            System.err.println("[renderer] cannot locate hot-reload-agent JAR — " +
                "hot swap will fall back to classloader recreation")
            return null
        }

        return try {
            selfAttach(agentJar)
            HotReloadAgent.instrumentation
        } catch (t: Throwable) {
            System.err.println("[renderer] self-attach failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * Locate the agent JAR on disk by asking the JVM where the agent
     * class was loaded from. Works whether the renderer is run from
     * installDist (lib/composepreviewpro-hot-reload-agent.jar) or from
     * the Gradle classpath at dev time.
     */
    private fun locateAgentJar(): File? {
        val loc = HotReloadAgent::class.java
            .protectionDomain
            ?.codeSource
            ?.location
            ?: return null
        val file = File(loc.toURI())
        // Sanity check — must be a JAR file with the agent manifest. We
        // can't reliably inspect the manifest from here without re-
        // opening the JAR, so we just require a .jar extension.
        return file.takeIf { it.isFile && it.name.endsWith(".jar") }
    }

    /**
     * Use the Attach API reflectively so this module does not need a
     * compile-time dependency on `com.sun.tools.attach` (which is in the
     * jdk.attach module, present at runtime but not always on the
     * compile classpath under all toolchains).
     */
    private fun selfAttach(agentJar: File) {
        val pid = currentPid()
        val vmClass = Class.forName("com.sun.tools.attach.VirtualMachine")
        val attach = vmClass.getMethod("attach", String::class.java)
        val vm = attach.invoke(null, pid)
        try {
            val loadAgent = vmClass.getMethod("loadAgent", String::class.java)
            loadAgent.invoke(vm, agentJar.absolutePath)
        } finally {
            vmClass.getMethod("detach").invoke(vm)
        }
    }

    private fun currentPid(): String =
        // ProcessHandle.current() is JDK 9+.
        ProcessHandle.current().pid().toString()
            // Fallback for ancient JVMs (we target JDK 21, so unreachable):
            .ifEmpty {
                ManagementFactory.getRuntimeMXBean().name.substringBefore('@')
            }
}
