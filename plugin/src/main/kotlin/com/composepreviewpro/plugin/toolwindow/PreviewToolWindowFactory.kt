package com.composepreviewpro.plugin.toolwindow

import com.composepreviewpro.plugin.service.PreviewService
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Creates and registers the single content panel shown inside the
 * "Compose Preview" tool window. Implements DumbAware so the window is
 * usable during indexing — the actual render still requires a built
 * classpath, but the panel itself does not depend on PSI.
 */
class PreviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        thisLogger().info("[ComposePreview] createToolWindowContent for project: ${project.name}")
        val panel = PreviewPanel(project)

        // CRITICAL ORDER: add the panel to the tool window FIRST, then ask
        // the service to remember it. If the service throws during attach,
        // the panel is still visible to the user.
        val content = ContentFactory.getInstance().createContent(
            /* component   = */ panel,
            /* displayName = */ "",
            /* isLockable  = */ false,
        )
        toolWindow.contentManager.addContent(content)
        thisLogger().info("[ComposePreview] PreviewPanel content added to tool window")

        try {
            project.getService(PreviewService::class.java).attachPanel(panel)
            thisLogger().info("[ComposePreview] PreviewService.attachPanel completed")
        } catch (t: Throwable) {
            thisLogger().error("[ComposePreview] Failed to attach preview panel to service", t)
        }
    }
}
