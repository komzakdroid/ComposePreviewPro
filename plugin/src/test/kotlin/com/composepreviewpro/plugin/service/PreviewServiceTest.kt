package com.composepreviewpro.plugin.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Ignore

/**
 * Verifies PreviewService's contract:
 *   • Instantiable as a project service
 *   • Initial state: no last target, snapshot not primed
 *   • State transitions are observable through the public API
 *
 * Hot-reload paths cannot be fully exercised in unit tests (no real
 * compile + Skia render in the test JVM), but the state-machine surface
 * IS testable — which is what catches "the gutter click silently did
 * nothing" regressions.
 *
 * Covers part of task #11 (hot reload end-to-end).
 *
 * @see com.composepreviewpro.plugin.inspector.CallExpressionInspectorTest
 *      for the full explanation of the BasePlatformTestCase /
 *      kotlinx-coroutines clash. Same root cause; re-enable when fixed.
 */
@Ignore("Blocked on IDE platform coroutines version clash — see CallExpressionInspectorTest KDoc")
class PreviewServiceTest : BasePlatformTestCase() {

    private fun service(): PreviewService =
        project.getService(PreviewService::class.java)

    fun testServiceInstantiable() {
        assertNotNull("PreviewService must be registered as a project service", service())
    }

    fun testInitialStateIsEmpty() {
        val svc = service()
        assertNull("lastRenderTarget should be null on a fresh service", svc.lastRenderTarget())
        assertFalse("Snapshot should NOT be primed initially", svc.isSnapshotPrimed())
    }

    fun testSnapshotPrimeFlagTogglesViaPrimeSnapshot() {
        val svc = service()
        assertFalse(svc.isSnapshotPrimed())
        svc.primeSnapshot()
        assertTrue("primeSnapshot() must flip the flag", svc.isSnapshotPrimed())
    }
}
