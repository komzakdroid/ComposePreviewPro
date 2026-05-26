package com.composepreviewpro.plugin.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * Pure unit tests for the mtime+length diff logic that drives hot-swap.
 *
 * Previously extended `BasePlatformTestCase` for its temp-dir lifecycle,
 * but that pulls in the full IntelliJ Platform test fixture (and at
 * IDEA 2024.3 + Kotlin K2 currently fails with a kotlinx-coroutines
 * `tryResume` `NoSuchMethodError` — see KDoc on
 * [com.composepreviewpro.plugin.inspector.CallExpressionInspectorTest]).
 *
 * Since this class only needs temp directories and pure-logic assertions,
 * we use plain JUnit and create our own temp dirs. No fixture bootstrap,
 * no platform classpath clash, sub-second wall time.
 */
class ClasspathSnapshotTest {

    @Test fun `first call returns every existing class`() {
        val root = Files.createTempDirectory("snap-test-").toFile()
        writeClass(root, "com/example/Foo", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        writeClass(root, "com/example/Bar", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

        val snap = ClasspathSnapshot()
        val first = snap.diff(listOf(root))

        assertEquals(
            "First call should report every .class as 'changed'",
            setOf("com.example.Foo", "com.example.Bar"),
            first.classes.keys,
        )
    }

    @Test fun `second call returns empty when nothing changed`() {
        val root = Files.createTempDirectory("snap-test-").toFile()
        writeClass(root, "com/example/Foo", byteArrayOf(0xCA.toByte(), 0xFE.toByte()))

        val snap = ClasspathSnapshot()
        snap.diff(listOf(root))  // prime
        val second = snap.diff(listOf(root))

        assertTrue("Unchanged classes must not reappear in subsequent diffs", second.isEmpty)
    }

    @Test fun `touching a file surfaces as change`() {
        val root = Files.createTempDirectory("snap-test-").toFile()
        val file = writeClass(root, "com/example/Foo", byteArrayOf(0xCA.toByte(), 0xFE.toByte()))

        val snap = ClasspathSnapshot()
        snap.diff(listOf(root))  // prime

        // Rewrite with different content + bump mtime forward.
        file.writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0x01.toByte(), 0x02.toByte()))
        file.setLastModified(file.lastModified() + 1000)

        val second = snap.diff(listOf(root))
        assertEquals(setOf("com.example.Foo"), second.classes.keys)

        // The Base64 payload must match the new bytes.
        val decoded = Base64.getDecoder().decode(second.classes["com.example.Foo"]!!)
        assertEquals(4, decoded.size)
        assertEquals(0x01.toByte(), decoded[2])
    }

    @Test fun `nested class FQN keeps its dollar separator`() {
        val root = Files.createTempDirectory("snap-test-").toFile()
        writeClass(root, "com/foo/bar/Baz\$Inner", byteArrayOf(0xCA.toByte(), 0xFE.toByte()))

        val snap = ClasspathSnapshot()
        val diff = snap.diff(listOf(root))

        assertTrue(
            "Nested class FQN must keep its \$ separator",
            diff.classes.containsKey("com.foo.bar.Baz\$Inner"),
        )
    }

    private fun writeClass(root: File, relPath: String, bytes: ByteArray): File {
        val file = File(root, "$relPath.class")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        return file
    }
}
