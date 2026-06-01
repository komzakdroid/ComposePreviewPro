package com.composepreviewpro.renderer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/**
 * Neutralises every `native` method in the bundled real-Android runtime
 * (org.robolectric:android-all) at class-load time, so off-device rendering
 * never hits an `UnsatisfiedLinkError`.
 *
 * Why a transformer instead of per-class shims
 * --------------------------------------------
 * android-all is real AOSP bytecode whose `native` methods have no JNI
 * implementation on a desktop JVM. Any android.* class whose code reaches a
 * native call crashes — `android.os.Build.<clinit>` alone calls
 * `SystemProperties.native_get` AND `dalvik.system.VMRuntime.is64Bit()`, and
 * countless other framework classes (Trace, Process, MessageQueue, …) have
 * their own. Shimming them one at a time is whack-a-mole.
 *
 * This is the systematic fix (the same principle as Robolectric's instrumenting
 * classloader): rewrite EVERY native method in the framework packages into an
 * ordinary method that returns a type-correct default (0 / false / 0L / null).
 * Registered once at startup via the JVM [Instrumentation] the renderer already
 * attaches, it fires as each framework class is first loaded by the user
 * classloader — covering classes we have never seen, with no maintenance.
 *
 * Fidelity note: a generic `native` returning null/0 is enough to get past
 * static initialisers and the layout/paint paths a static preview exercises
 * (drawing itself goes through Skiko, not android.graphics). Where a *parsed*
 * value matters — chiefly the build-identity system properties feeding
 * `Build.VERSION.SDK_INT` — the dedicated [android.os.SystemProperties] shim
 * (which shadows android-all via parent-first delegation) supplies sane values,
 * so those native getters are never even reached.
 */
internal object NativeMethodNeutralizer : ClassFileTransformer {

    /**
     * Internal-name prefixes of the framework packages android-all ships.
     * We deliberately do NOT touch `androidx/` (real AndroidX has no natives
     * and is the user's own dependency) or any non-framework code.
     */
    private val FRAMEWORK_PREFIXES = listOf(
        "android/",
        "dalvik/",
        "com/android/",
        "libcore/",
        "sun/misc/",
        "org/apache/harmony/",
        "java/lang/VMClassLoader", // a couple of dalvik-era stragglers
    )

    @Volatile private var installed = false

    /** Idempotently register the transformer. No-op if [inst] is null. */
    fun installInto(inst: Instrumentation?) {
        if (inst == null || installed) return
        synchronized(this) {
            if (installed) return
            inst.addTransformer(this, /* canRetransform = */ true)
            installed = true
            System.err.println("[NativeMethodNeutralizer] installed — android.* natives will return defaults.")
        }
    }

    override fun transform(
        loader: ClassLoader?,
        className: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray? {
        if (className == null) return null
        if (FRAMEWORK_PREFIXES.none { className.startsWith(it) }) return null
        return try {
            val cn = ClassNode()
            // EXPAND_FRAMES keeps every untouched method's stackmap frames in
            // re-emittable (F_NEW) form so COMPUTE_MAXS can write them back
            // verbatim. SKIP_FRAMES would DROP them → VerifyError on any method
            // with a branch. Our replacement native bodies have no branches and
            // therefore need no frames of their own.
            ClassReader(classfileBuffer).accept(cn, ClassReader.EXPAND_FRAMES)
            var changed = false
            for (m in cn.methods) {
                if (m.access and Opcodes.ACC_NATIVE != 0) {
                    m.access = m.access and Opcodes.ACC_NATIVE.inv()
                    m.instructions = defaultReturnBody(m.desc)
                    m.tryCatchBlocks = arrayListOf()
                    m.localVariables = null
                    m.visibleAnnotations = null // drop @FastNative/@CriticalNative etc.
                    m.invisibleAnnotations = null
                    changed = true
                }
            }
            if (!changed) return null
            // COMPUTE_MAXS only — our replacement bodies have no branches, so no
            // stackmap frames are needed and we avoid ClassWriter's
            // getCommonSuperClass (which would try to load framework types).
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            cn.accept(cw)
            cw.toByteArray()
        } catch (t: Throwable) {
            // A transformer must NEVER break class loading. On any failure,
            // return null (use the original bytes) and let the specific native
            // call fail later with its own diagnostic.
            System.err.println("[NativeMethodNeutralizer] skip $className: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** A minimal method body that returns the type-correct default for [desc]. */
    private fun defaultReturnBody(desc: String): InsnList {
        val insns = InsnList()
        when (Type.getReturnType(desc).sort) {
            Type.VOID -> insns.add(InsnNode(Opcodes.RETURN))
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                insns.add(InsnNode(Opcodes.ICONST_0))
                insns.add(InsnNode(Opcodes.IRETURN))
            }
            Type.LONG -> {
                insns.add(InsnNode(Opcodes.LCONST_0))
                insns.add(InsnNode(Opcodes.LRETURN))
            }
            Type.FLOAT -> {
                insns.add(InsnNode(Opcodes.FCONST_0))
                insns.add(InsnNode(Opcodes.FRETURN))
            }
            Type.DOUBLE -> {
                insns.add(InsnNode(Opcodes.DCONST_0))
                insns.add(InsnNode(Opcodes.DRETURN))
            }
            else -> { // object / array
                insns.add(InsnNode(Opcodes.ACONST_NULL))
                insns.add(InsnNode(Opcodes.ARETURN))
            }
        }
        return insns
    }
}
