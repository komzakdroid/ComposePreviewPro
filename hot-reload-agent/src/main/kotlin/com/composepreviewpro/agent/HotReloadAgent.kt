package com.composepreviewpro.agent

import java.lang.instrument.Instrumentation

/**
 * Java Agent that publishes the JVM's [Instrumentation] reference into a
 * static singleton so the host application (the renderer) can issue class
 * redefinition requests at runtime.
 *
 * Lifecycle:
 *   • Loaded via `-javaagent:` JVM flag → [premain] runs before main.
 *   • Loaded via self-attach API at runtime → [agentmain] runs.
 *   • Either way, [instrumentation] becomes non-null and is published to
 *     the application classloader.
 *
 * Why a separate JAR? The JVM's agent loader enforces a manifest contract
 * (Premain-Class / Agent-Class). Inlining the agent into the renderer JAR
 * would force the renderer to advertise itself as an agent, which would
 * break the application classloader/system classloader separation that
 * the JVM relies on for agent isolation.
 */
@Suppress("unused")  // Entry points are invoked by the JVM reflectively.
object HotReloadAgent {

    /**
     * The Instrumentation reference. Initialised by [premain] or
     * [agentmain]. Stays null if the agent was never loaded — callers
     * must handle that case gracefully (e.g. fall back to URLClassLoader
     * recreation).
     */
    @JvmStatic
    @Volatile
    var instrumentation: Instrumentation? = null
        private set

    /**
     * Called by the JVM when the agent is attached at startup via
     * `-javaagent:agent.jar`. The second parameter (agentArgs) is
     * ignored — we accept no configuration.
     */
    @JvmStatic
    fun premain(@Suppress("UNUSED_PARAMETER") agentArgs: String?, inst: Instrumentation) {
        instrumentation = inst
        System.err.println("[hot-reload-agent] premain: instrumentation ready " +
            "(redefineClassesSupported=${inst.isRedefineClassesSupported}, " +
            "retransformClassesSupported=${inst.isRetransformClassesSupported})")
    }

    /**
     * Called by the JVM when the agent is loaded at runtime via the
     * Attach API — see [VirtualMachine.loadAgent] in the renderer's
     * AgentLoader.
     */
    @JvmStatic
    fun agentmain(@Suppress("UNUSED_PARAMETER") agentArgs: String?, inst: Instrumentation) {
        instrumentation = inst
        System.err.println("[hot-reload-agent] agentmain: instrumentation ready " +
            "(redefineClassesSupported=${inst.isRedefineClassesSupported}, " +
            "retransformClassesSupported=${inst.isRetransformClassesSupported})")
    }
}
