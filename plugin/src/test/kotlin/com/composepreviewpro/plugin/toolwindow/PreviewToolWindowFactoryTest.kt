package com.composepreviewpro.plugin.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Ignore

/**
 * Sanity-checks the tool-window factory contract:
 *   • Instantiable
 *   • Implements DumbAware (so the panel works during indexing)
 *
 * Covers part of task #9.
 *
 * @see com.composepreviewpro.plugin.inspector.CallExpressionInspectorTest
 *      for the BasePlatformTestCase / kotlinx-coroutines clash.
 */
@Ignore("Blocked on IDE platform coroutines version clash — see CallExpressionInspectorTest KDoc")
class PreviewToolWindowFactoryTest : BasePlatformTestCase() {

    fun testFactoryInstantiable() {
        val factory = PreviewToolWindowFactory()
        assertNotNull(factory)
    }

    fun testFactoryIsDumbAware() {
        val factory = PreviewToolWindowFactory()
        assertTrue(
            "PreviewToolWindowFactory must be DumbAware so the tool window is usable during indexing",
            factory is DumbAware,
        )
    }
}
