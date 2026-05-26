package com.composepreviewpro.plugin.reload

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Ignore

/**
 * Verifies the coordinator can be instantiated as a project service and
 * survives its init block (which subscribes to the project's MessageBus
 * for VFS_CHANGES). The most common regression source.
 *
 * Covers part of task #11 (hot reload pipeline reachability).
 *
 * @see com.composepreviewpro.plugin.inspector.CallExpressionInspectorTest
 *      for the BasePlatformTestCase / kotlinx-coroutines clash.
 */
@Ignore("Blocked on IDE platform coroutines version clash — see CallExpressionInspectorTest KDoc")
class HotReloadCoordinatorTest : BasePlatformTestCase() {

    fun testServiceInstantiable() {
        // Touching the service in any way forces eager construction; if
        // its init throws (e.g. messageBus.connect() fails on this
        // platform version) this test fails immediately with the actual
        // exception, not a silent miss.
        val service = project.getService(HotReloadCoordinator::class.java)
        assertNotNull("HotReloadCoordinator must be registered as a project service", service)
    }
}
