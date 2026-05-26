package com.composepreviewpro.plugin.inspector

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests for the Modifier-chain pretty-printer used by the
 * Parameters panel. Verifies the four behaviours that matter for the UI:
 *
 *   1. Top-level chain segments break onto their own indented lines.
 *   2. Nested `.`s inside parentheses are NOT treated as chain boundaries.
 *   3. Multi-line source input is normalised before being re-broken.
 *   4. Non-chain expressions pass through (whitespace-normalised).
 *
 * The formatter is what fixed the "Modifier .size(10.dp) .clip(CircleShape)
 * .back…" horizontal mash-up regression — these tests pin that behaviour
 * down so a future refactor cannot silently undo it.
 */
class ChainFormatterTest {

    @Test fun `simple chain breaks on each top-level dot`() {
        val out = formatBuilderChain("Modifier.size(10.dp).clip(CircleShape).background(Red)")
        assertEquals(
            """
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Red)
            """.trimIndent(),
            out,
        )
    }

    @Test fun `nested dots stay on the same line`() {
        // `start = 4.dp` has a `.dp` deep inside parens — must NOT break.
        val out = formatBuilderChain("Modifier.padding(start = 4.dp, top = 8.dp)")
        assertEquals(
            """
                Modifier
                    .padding(start = 4.dp, top = 8.dp)
            """.trimIndent(),
            out,
        )
    }

    @Test fun `multiline input is normalised then re-broken`() {
        val input = """
            Modifier
                .fillMaxWidth()
                .height(64.dp)
        """.trimIndent()
        val out = formatBuilderChain(input)
        assertEquals(
            """
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            """.trimIndent(),
            out,
        )
    }

    @Test fun `non-chain expression passes through with whitespace collapsed`() {
        assertEquals("Color.Red", formatBuilderChain("Color.Red"))
        assertEquals("\"Hello world\"", formatBuilderChain("\"Hello world\""))
        assertEquals("MaterialTheme.colorScheme.background",
            formatBuilderChain("MaterialTheme . colorScheme . background"))
    }

    @Test fun `chain with deeply nested lambda still breaks at top level`() {
        // The trailing lambda body has its own braces / dots. We only
        // break at depth 0, so the `.let(...)` cleanly splits but the
        // inner `it.size.dp` stays put.
        val out = formatBuilderChain("Modifier.size(10.dp).let { it.size.dp }")
        // The "let { ... }" pushes depth via `{` and pops via `}`. While
        // inside, `it.size.dp` keeps depth > 0 → no break. Outside, the
        // top-level `.let` triggers a break.
        assertEquals(
            """
                Modifier
                    .size(10.dp)
                    .let { it.size.dp }
            """.trimIndent(),
            out,
        )
    }

    @Test fun `empty input returns empty string`() {
        assertEquals("", formatBuilderChain(""))
        assertEquals("", formatBuilderChain("   "))
    }
}
