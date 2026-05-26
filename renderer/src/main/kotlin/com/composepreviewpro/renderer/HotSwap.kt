package com.composepreviewpro.renderer

import java.lang.instrument.ClassDefinition
import java.lang.instrument.Instrumentation
import java.util.Base64

/**
 * In-place redefinition of class bytecodes via JVM Instrumentation.
 *
 * Given a map of `FQN → Base64(newBytecode)` plus a classloader that
 * already has those classes loaded, swap the bytecode in-place. Method
 * references stay valid, which is what preserves Compose's slot-table
 * identity (hence mutableStateOf values across reloads).
 *
 * Behaviour:
 *   • If a class has not yet been loaded → silently skipped. Compose only
 *     re-renders code that's actually executed, so cold classes don't
 *     need swapping.
 *   • If the new bytecode is structurally incompatible → throws
 *     UnsupportedOperationException (caller falls back to URLClassLoader
 *     recreation).
 */
internal object HotSwap {

    sealed interface Outcome {
        data class Success(val redefinedFqns: List<String>, val skipped: List<String>) : Outcome
        data class Failed(val reason: String, val stackTrace: String) : Outcome
    }

    fun redefine(
        instrumentation: Instrumentation,
        classLoader: ClassLoader,
        classes: Map<String, String>,
    ): Outcome {
        if (!instrumentation.isRedefineClassesSupported) {
            return Outcome.Failed(
                reason = "JVM does not support redefineClasses (Instrumentation flag)",
                stackTrace = "",
            )
        }

        val redefined = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val definitions = mutableListOf<ClassDefinition>()

        for ((fqn, base64) in classes) {
            // Use loadClass so that a class which exists on the URL
            // classpath but hasn't been touched yet gets eagerly loaded,
            // making it eligible for redefinition. Skipping silently is
            // for classes that are genuinely missing from the classpath.
            val klass = try {
                classLoader.loadClass(fqn)
            } catch (_: ClassNotFoundException) {
                null
            } catch (_: NoClassDefFoundError) {
                null
            }
            if (klass == null) {
                skipped += fqn
                continue
            }
            val bytes = Base64.getDecoder().decode(base64)
            definitions += ClassDefinition(klass, bytes)
            redefined += fqn
        }

        if (definitions.isEmpty()) {
            return Outcome.Success(redefinedFqns = emptyList(), skipped = skipped)
        }

        return try {
            instrumentation.redefineClasses(*definitions.toTypedArray())
            Outcome.Success(redefinedFqns = redefined, skipped = skipped)
        } catch (t: Throwable) {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            Outcome.Failed(
                reason = "${t.javaClass.simpleName}: ${t.message ?: ""}",
                stackTrace = sw.toString(),
            )
        }
    }

}
