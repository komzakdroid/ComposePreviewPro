package com.composepreviewpro.renderer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import java.util.jar.JarFile

/**
 * Extracts, from the user's compiled .class files, the data required to
 * point a panel click back at the exact source line of the composable
 * underneath the cursor.
 *
 * Why this exists: Compose Multiplatform 1.10.3 (a) does not expose
 * source positions through the tooling-data Group API and (b) the
 * Compose Compiler in this version does NOT emit `startReplaceableGroup`
 * wrappers around every composable invocation in the caller's body.
 * That means we can match the function's outer group key, but NOT the
 * keys of its children (those are emitted inside library classes like
 * Surface.kt, LazyColumn.kt — wrong source files for the user).
 *
 * Two pieces of data per user @Composable function:
 *
 *   • [FunctionInfo.funcKey]   — the slot key of the function's outer
 *     restart group (parsed from the LDC int immediately preceding the
 *     first `Composer.startRestartGroup(I)` invocation in the method).
 *
 *   • [FunctionInfo.subCallLines] — the source lines of every nested
 *     composable INVOKESTATIC/INVOKEINTERFACE inside the function body
 *     (i.e. every call whose descriptor mentions `Composer`). These
 *     correspond, in order, to the direct children of the function's
 *     outer group at runtime — so the i-th child can be navigated to
 *     the i-th sub-call line. THIS is what enables per-element
 *     navigation despite the missing wrappers.
 */
class ComposeSourceMapper {

    data class SourceLoc(val name: String?, val file: String?, val line: Int?)

    data class FunctionInfo(
        val funcKey: Int,
        val source: SourceLoc,
        /**
         * Source lines of every composable invocation in the function
         * body, in textual order. Empty for functions without
         * detectable sub-calls.
         */
        val subCallLines: List<Int>,
    )

    private val cache = mutableMapOf<String, Map<Int, FunctionInfo>>()

    fun mapFor(classpath: List<String>): Map<Int, FunctionInfo> {
        val signature = classpath.joinToString("|")
        cache[signature]?.let { return it }
        val map = mutableMapOf<Int, FunctionInfo>()
        for (entry in classpath) {
            val file = File(entry)
            if (!file.exists()) continue
            // CRITICAL: only parse DIRECTORIES (the user's compiled .class
            // output). Skip .jar files entirely — those are compose-
            // runtime / compose-material3 / kotlin-stdlib / etc.
            // Including library funcKeys in the map causes the
            // inspector to match runtime groups to internal Compose
            // source files (LazyLayoutSemantics.kt, SaveableStateHolder
            // .kt, …), and the user's click navigates AWAY from their
            // own code. User code under build/classes/... is the only
            // valid navigation target.
            if (!file.isDirectory) continue
            try {
                walkDirectory(file, map)
            } catch (t: Throwable) {
                System.err.println("[source-mapper] skipped $entry: ${t.message}")
            }
        }
        cache[signature] = map
        return map
    }

    private fun walkDirectory(root: File, out: MutableMap<Int, FunctionInfo>) {
        root.walkTopDown().forEach { f ->
            if (f.isFile && f.name.endsWith(".class")) {
                try { parseClassBytes(f.readBytes(), out) } catch (_: Throwable) { /* skip */ }
            }
        }
    }

    private fun walkJar(jar: File, out: MutableMap<Int, FunctionInfo>) {
        JarFile(jar).use { jf ->
            for (entry in jf.entries()) {
                if (!entry.name.endsWith(".class")) continue
                try { parseClassBytes(jf.getInputStream(entry).readBytes(), out) } catch (_: Throwable) { /* skip */ }
            }
        }
    }

    private fun parseClassBytes(bytes: ByteArray, out: MutableMap<Int, FunctionInfo>) {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES)
        for (method in cn.methods) {
            parseMethod(method)?.let { info ->
                out.putIfAbsent(info.funcKey, info)
            }
        }
    }

    /**
     * Two-pass walk of one method:
     *
     *   Pass 1: find the function's outer `startRestartGroup` to learn
     *           the funcKey, and the trailing `sourceInformation` to
     *           parse the function's source location.
     *
     *   Pass 2: between sourceInformation and the function's
     *           endRestartGroup, every INVOKE of a Compose-shaped
     *           method (descriptor contains `Composer`) is a nested
     *           composable call site. Record the LineNumberTable line
     *           that was last in effect at that instruction.
     */
    private fun parseMethod(method: MethodNode): FunctionInfo? {
        val arr = method.instructions?.toArray() ?: return null
        if (arr.isEmpty()) return null

        var funcKey: Int? = null
        var sourceInfo: String? = null
        var pendingInt: Int? = null
        var currentLine = 0
        var sawSourceInfo = false
        val subCallLines = mutableListOf<Int>()

        for (i in arr.indices) {
            val insn = arr[i]
            if (insn is LineNumberNode) currentLine = insn.line
            readIntConstant(insn)?.let { pendingInt = it }

            if (insn is MethodInsnNode) {
                // First start*Group call → captures funcKey.
                if (funcKey == null && insn.name in START_GROUP_METHODS && insn.owner.endsWith("Composer")) {
                    funcKey = pendingInt
                }
                // sourceInformation always follows the function-entry
                // startRestartGroup. The string is loaded by an LDC
                // right before the call.
                if (insn.name == "sourceInformation" && insn.owner.endsWith("ComposerKt")) {
                    val stringLdc = arr.getOrNull(i - 1) as? LdcInsnNode
                    val s = stringLdc?.cst as? String
                    if (s != null && (s.startsWith("C(") || s.startsWith("CC("))) {
                        sourceInfo = s
                        sawSourceInfo = true
                    }
                }
                // Every Composable INVOKE after the sourceInfo marker
                // is a nested call site. Its line is the most-recently
                // seen LineNumberNode line.
                if (sawSourceInfo && isComposableInvoke(insn) && currentLine > 0) {
                    subCallLines.add(currentLine)
                }
            }
        }

        val keyResolved = funcKey ?: return null
        val infoString = sourceInfo ?: return null
        return FunctionInfo(
            funcKey = keyResolved,
            source = parseSourceInfoString(infoString),
            subCallLines = subCallLines,
        )
    }

    private fun isComposableInvoke(insn: MethodInsnNode): Boolean {
        // Compose's compiled functions always carry a `Composer` parameter
        // in their JVM descriptor. We can spot a composable call site by
        // the presence of "Landroidx/compose/runtime/Composer;" anywhere
        // in the method's descriptor. We exclude the bookkeeping calls
        // ourselves (startGroup / sourceInformation / endGroup / changed
        // / updateScope).
        if (!insn.desc.contains("Landroidx/compose/runtime/Composer;")) return false
        if (insn.name in BOOKKEEPING_NAMES) return false
        return true
    }

    private fun parseSourceInfoString(s: String): SourceLoc {
        val name = NAME_REGEX.find(s)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val file = s.substringAfter(':', "").substringBefore('#', "")
            .takeIf { it.isNotEmpty() && it.endsWith(".kt") }
        val line = LINE_REGEX.find(s)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return SourceLoc(name = name, file = file, line = line)
    }

    private fun readIntConstant(insn: AbstractInsnNode): Int? = when {
        insn is LdcInsnNode && insn.cst is Int -> insn.cst as Int
        insn is IntInsnNode && (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) ->
            insn.operand
        insn is InsnNode -> when (insn.opcode) {
            Opcodes.ICONST_M1 -> -1
            Opcodes.ICONST_0 -> 0
            Opcodes.ICONST_1 -> 1
            Opcodes.ICONST_2 -> 2
            Opcodes.ICONST_3 -> 3
            Opcodes.ICONST_4 -> 4
            Opcodes.ICONST_5 -> 5
            else -> null
        }
        else -> null
    }

    private companion object {
        val START_GROUP_METHODS = setOf(
            "startRestartGroup",
            "startReplaceableGroup",
            "startReplaceGroup",
            "startMovableGroup",
        )
        val BOOKKEEPING_NAMES = setOf(
            "startRestartGroup", "startReplaceableGroup", "startReplaceGroup", "startMovableGroup",
            "endRestartGroup", "endReplaceableGroup", "endReplaceGroup", "endMovableGroup",
            "sourceInformation", "sourceInformationMarkerStart", "sourceInformationMarkerEnd",
            "changed", "changedInstance", "updateScope", "skipToGroupEnd", "skipCurrentGroup",
            "rememberComposableLambda", "rememberComposableLambdaN",
        )
        val NAME_REGEX = Regex("^CC?\\(([^)]*)\\)")
        val LINE_REGEX = Regex("\\)(?:N\\([^)]*\\))?(\\d+)@")
    }
}
