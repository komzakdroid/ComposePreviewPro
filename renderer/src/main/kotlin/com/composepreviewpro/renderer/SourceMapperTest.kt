package com.composepreviewpro.renderer

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Standalone test for [ComposeSourceMapper]'s classpath-filtering contract.
 *
 * Pins the behaviour that fixed the "click navigates to LazyLayoutSemantics
 * .kt / SaveableStateProvider.kt instead of the user's file" bug: when the
 * source map is built from a classpath containing both a directory of user
 * .class files AND library .jar files, ONLY the directory entries must
 * contribute to the map. Including JAR contents leaks library composables'
 * slot keys into the map and routes clicks at library files.
 *
 * Two scenarios:
 *   1. Directory-only classpath → user funcKeys present.
 *   2. Directory + JAR classpath → JAR entries silently skipped; map size
 *      identical to scenario 1.
 *
 * Run via: ./gradlew :renderer:sourceMapperTest
 */
fun main() {
    val tmpRoot = Files.createTempDirectory("source-mapper-test-").toFile()
    try {
        val classesDir = File(tmpRoot, "classes").apply { mkdirs() }
        val libsDir = File(tmpRoot, "libs").apply { mkdirs() }

        // Synthesize a user .class file with a composable-shaped method.
        val userClassBytes = synthesizeComposableClass(
            internalName = "com/composepreviewpro/sample/UserCompose",
            functionName = "UserFn",
            funcKeyConstant = 12_345_678,
            sourceInfo = "C(UserFn):UserFile.kt#abc",
        )
        val userClassFile = File(classesDir, "com/composepreviewpro/sample/UserCompose.class")
        userClassFile.parentFile.mkdirs()
        userClassFile.writeBytes(userClassBytes)

        // Synthesize a library .class that would, if parsed, contribute a
        // DIFFERENT funcKey to the map — we shall verify this key is
        // NEVER present when the JAR is given alongside the directory.
        val libClassBytes = synthesizeComposableClass(
            internalName = "androidx/compose/runtime/saveable/LazyLib",
            functionName = "LibraryComposable",
            funcKeyConstant = 99_998_888,
            sourceInfo = "C(LibraryComposable):LazyLib.kt#xyz",
        )
        val libJar = File(libsDir, "compose-lib.jar")
        JarOutputStream(libJar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("androidx/compose/runtime/saveable/LazyLib.class"))
            jos.write(libClassBytes)
            jos.closeEntry()
        }

        val mapper = ComposeSourceMapper()

        // Scenario 1 — directory only.
        val mapDirOnly = mapper.mapFor(listOf(classesDir.absolutePath))
        require(mapDirOnly.containsKey(12_345_678)) {
            "Expected user funcKey 12345678 in directory-only map, got keys=${mapDirOnly.keys}"
        }
        require(!mapDirOnly.containsKey(99_998_888)) {
            "Library funcKey leaked into directory-only map: ${mapDirOnly.keys}"
        }
        println("  ✓ directory-only: 1 user entry, 0 library entries")

        // Scenario 2 — directory + JAR. JAR must be silently ignored.
        // Use a FRESH ComposeSourceMapper to avoid its internal cache
        // serving the previous result and masking a regression.
        val mapper2 = ComposeSourceMapper()
        val mapWithJar = mapper2.mapFor(listOf(classesDir.absolutePath, libJar.absolutePath))
        require(mapWithJar.containsKey(12_345_678)) {
            "User funcKey missing from dir+jar map: ${mapWithJar.keys}"
        }
        require(!mapWithJar.containsKey(99_998_888)) {
            "Library funcKey LEAKED when JAR is on classpath — JAR exclusion regressed. " +
                "Keys: ${mapWithJar.keys}"
        }
        require(mapWithJar.size == mapDirOnly.size) {
            "Map size changed when JAR added (${mapDirOnly.size} → ${mapWithJar.size}): " +
                "library entries silently included."
        }
        println("  ✓ dir + jar: JAR entries skipped, ${mapWithJar.size} entry total")

        println()
        println("[source-mapper-test] PASSED — JAR exclusion contract holds")
    } finally {
        tmpRoot.deleteRecursively()
    }
}

/**
 * Generate the minimum bytecode that [ComposeSourceMapper.parseMethod]
 * recognises as a composable function:
 *
 *   • Class with a method named [functionName]
 *   • Inside that method: LDC [funcKeyConstant] → invokevirtual
 *     `Composer.startRestartGroup(I)Landroidx/compose/runtime/Composer$ScopeUpdateScope;`
 *   • Then: LDC [sourceInfo] string → invokestatic
 *     `ComposerKt.sourceInformation(Composer, String)V`
 *   • Method returns void.
 *
 * That's enough for the parser to extract `funcKey` and `source.file`.
 */
private fun synthesizeComposableClass(
    internalName: String,
    functionName: String,
    funcKeyConstant: Int,
    sourceInfo: String,
): ByteArray {
    val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
    cw.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC,
        internalName,
        null,
        "java/lang/Object",
        null,
    )

    val mv: MethodVisitor = cw.visitMethod(
        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
        functionName,
        "(Landroidx/compose/runtime/Composer;)V",
        null,
        null,
    )
    mv.visitCode()

    // composer = arg0
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitLdcInsn(funcKeyConstant)
    mv.visitMethodInsn(
        Opcodes.INVOKEINTERFACE,
        "androidx/compose/runtime/Composer",
        "startRestartGroup",
        "(I)Landroidx/compose/runtime/Composer\$ScopeUpdateScope;",
        true,
    )
    mv.visitInsn(Opcodes.POP)

    // ComposerKt.sourceInformation(composer, "C(UserFn):UserFile.kt#abc")
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitLdcInsn(sourceInfo)
    mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "androidx/compose/runtime/ComposerKt",
        "sourceInformation",
        "(Landroidx/compose/runtime/Composer;Ljava/lang/String;)V",
        false,
    )

    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()

    cw.visitEnd()
    return cw.toByteArray()
}
