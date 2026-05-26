package com.composepreviewpro.plugin.inspector

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Ignore

/**
 * End-to-end tests of [CallExpressionInspector] against real PSI fixtures.
 *
 * Pins the two behaviours added in the recent fix pass:
 *
 *   1. **Composable-tier filter** — on a line containing both a Compose
 *      call (Uppercase) and helper calls (lowercase modifier / factory
 *      methods), the inspector must resolve to the Compose call.
 *
 *   2. **Multi-edit (PSI re-resolve)** — issuing two consecutive
 *      `updateArgument` calls on the same logical call site succeeds for
 *      both, because the inspector re-walks the file each time instead
 *      of trusting a stale [KtValueArgument] reference. Pre-fix, the
 *      second write silently no-op'd because the first write invalidated
 *      the PSI element captured in CallInfo.
 *
 * Both regressions had material user impact: #1 sent users to
 * fillMaxSize/padding/cardElevation lines instead of the Box/Card they
 * clicked, and #2 made the Parameters panel feel broken when users tried
 * to iterate on a color/text value.
 *
 * ── KNOWN ISSUE — currently @Ignored ─────────────────────────────────
 *
 * At IntelliJ IDEA 2024.3 + Kotlin plugin K2, the test framework hits
 * `NoSuchMethodError: kotlinx.coroutines.CancellableContinuation
 * .tryResume(Object, Object, Function1)` during `BasePlatformTestCase`
 * setUp. Root cause: the plugin's runtime classpath ships Compose
 * Desktop (for Live UI mode), which transitively pulls in
 * `kotlinx-coroutines-core` 1.10.x — that version has the 3-arg
 * `tryResume` overload above. The IDE-bundled coroutines is 1.8.0 and
 * has a different signature (`Function3` instead of `Function1`). When
 * the test framework bootstraps the Kotlin plugin, the Kotlin K2
 * plugin's K2-mode code resolves to the 1.10 signature but the IDE
 * platform supplies 1.8.0 at call time, throwing.
 *
 * In production this is not an issue: the renderer process loads user
 * classes through an isolated URLClassLoader and Live UI mode uses
 * its own classloader. The clash only manifests when the test fixture
 * tries to coexist with both.
 *
 * Re-enable once the Kotlin plugin / IntelliJ Platform align their
 * coroutines version, or once Compose Desktop's transitive coroutines
 * are downgraded to 1.8.x. Run `./gradlew :plugin:test --tests
 * CallExpressionInspectorTest` to check the current state.
 */
@Ignore("Blocked on IDE platform coroutines version clash — see class KDoc")
class CallExpressionInspectorTest : BasePlatformTestCase() {

    fun testComposableTierPreferredOverModifierHelpers() {
        // Card on line 2, modifier helpers on the same line.
        myFixture.configureByText(
            "Sample.kt",
            """
                fun host() {
                    Card(modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(8.dp)) { }
                }
            """.trimIndent(),
        )
        val inspector = CallExpressionInspector(project)
        val call = inspector.callAtLine("Sample.kt", 2)
        assertNotNull("Inspector should resolve at least one call on line 2", call)
        assertEquals(
            "On a line with both 'Card' (Uppercase) and 'fillMaxSize/padding' " +
                "(lowercase modifier helpers), the inspector must prefer the composable.",
            "Card",
            call!!.functionName,
        )
    }

    fun testLowercaseFallbackWhenNoComposableOnLine() {
        // No Uppercase call on the line → fall back to whatever helper IS there.
        myFixture.configureByText(
            "OnlyHelpers.kt",
            """
                fun host() {
                    val m = androidx.compose.ui.Modifier.fillMaxSize().padding(8.dp)
                }
            """.trimIndent(),
        )
        val inspector = CallExpressionInspector(project)
        val call = inspector.callAtLine("OnlyHelpers.kt", 2)
        assertNotNull(
            "Inspector must still resolve SOMETHING on a line with no composables " +
                "— navigation falling back to a helper is preferable to landing nowhere.",
            call,
        )
        // The choice is "fillMaxSize" or "padding" depending on tie-breaks;
        // either is acceptable. The key is that the tier filter did not
        // *fail* to produce a result when the composable tier was empty.
        assertTrue(
            "Lowercase fallback should pick one of the helpers on the line",
            call!!.functionName in setOf("fillMaxSize", "padding", "Modifier"),
        )
    }

    fun testConsecutiveEditsBothPersist() {
        // The body has one named arg (`text`) so we can edit it twice
        // without dealing with positional-resolution. The point of this
        // test is that PSI invalidation between writes is handled.
        val source = """
            fun screen() {
                Text(text = "first")
            }
        """.trimIndent()
        val psiFile = myFixture.configureByText("MultiEdit.kt", source)
        val inspector = CallExpressionInspector(project)

        val firstResolve = inspector.callAtLine("MultiEdit.kt", 2) ?: error("first resolve failed")
        val firstOk = inspector.updateArgument(firstResolve, "text", "\"second\"")
        assertTrue("first edit must succeed", firstOk)

        // Re-resolve from the now-modified file (this is what
        // PreviewService.setSelectedArgValue does in production).
        val secondResolve = inspector.callAtLine("MultiEdit.kt", 2) ?: error("re-resolve after edit failed")
        val secondOk = inspector.updateArgument(secondResolve, "text", "\"third\"")
        assertTrue(
            "second edit must succeed AGAINST a re-resolved CallInfo — this is the " +
                "exact scenario that pre-fix returned false silently.",
            secondOk,
        )

        val finalText = psiFile.text
        assertTrue(
            "Final file must contain \"third\" (not \"first\" or \"second\"); got: $finalText",
            finalText.contains("\"third\""),
        )
        assertFalse(
            "Final file must NOT contain \"first\" — both edits should have landed.",
            finalText.contains("\"first\""),
        )
    }
}
