package com.composepreviewpro.plugin.toolwindow

import com.composepreviewpro.ipc.Click
import com.composepreviewpro.ipc.PreviewTheme
import com.composepreviewpro.ipc.RenderSize
import com.composepreviewpro.ipc.Scroll
import com.composepreviewpro.plugin.service.PreviewService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants

/**
 * Top-of-tool-window UI: a toolbar (device size, theme, zoom, refresh)
 * plus a scrollable content area that shows one of three states —
 * loading, image, error.
 *
 * The toolbar drives PreviewService directly. State changes flow:
 *   user clicks toolbar → PreviewService.updateRenderParams() → new
 *   RenderRequest → renderer → showImage() back here.
 */
class PreviewPanel(private val project: Project?) : JPanel(BorderLayout()), Disposable {

    // Legacy ctor for tests that don't have a Project on hand.
    constructor() : this(null)

    // ── Toolbar state ────────────────────────────────────────────
    private val deviceCombo = JComboBox(DevicePreset.entries.toTypedArray()).apply {
        selectedItem = DevicePreset.PHONE
    }
    private val themeCombo = JComboBox(PreviewTheme.entries.toTypedArray()).apply {
        selectedItem = PreviewTheme.LIGHT
    }
    private val zoomCombo = JComboBox(ZoomLevel.entries.toTypedArray()).apply {
        selectedItem = ZoomLevel.FIT
    }
    private val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Re-render the current preview"
        isFocusPainted = false
    }
    private val gotoSourceBtn = JButton(AllIcons.Actions.EditSource).apply {
        toolTipText = "Open the source declaration of the previewed composable (or Cmd/Ctrl-click the canvas)"
        isFocusPainted = false
    }
    private val interactiveToggle = JCheckBox("Interactive", true).apply {
        toolTipText = "Forward clicks and scrolls to the live preview"
        background = JBColor.background()
    }
    private val multiFrameToggle = JCheckBox("Multi-frame", false).apply {
        toolTipText = "Render Phone + Tablet + Desktop side-by-side"
        background = JBColor.background()
    }
    private val aiMocksToggle = JCheckBox("✨ AI Mocks", false).apply {
        toolTipText = "Use Claude for contextual String mocks (requires ANTHROPIC_API_KEY env var)"
        background = JBColor.background()
    }
    private val liveUiToggle = JCheckBox("🟢 Live UI", false).apply {
        toolTipText = "Mount user composable as a NATIVE Compose UI inside the IDE (no PNG round-trip). " +
            "Experimental — requires matching Compose Multiplatform versions and supports only " +
            "composables with default-only parameters in this MVP."
        background = JBColor.background()
    }
    private val inspectToggle = JCheckBox("🔍 Inspect", false).apply {
        toolTipText = "When ON, a left click on the preview SELECTS the underlying composable " +
            "and shows its arguments in the Parameters panel for live editing."
        background = JBColor.background()
    }

    private val livePanel = LivePreviewPanel()
    private val statusLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(0, 12, 0, 12)
        foreground = JBColor.foreground()
    }

    // ── Body area ────────────────────────────────────────────────
    private val header = JBLabel("No preview yet — click ▶ next to a @Composable function.").apply {
        border = JBUI.Borders.empty(8, 12)
        horizontalAlignment = SwingConstants.LEFT
    }
    private val body = JPanel(BorderLayout())
    private val scroll = JBScrollPane(body).apply { border = JBUI.Borders.empty() }
    private val paramsPanel = ParametersPanel(project)

    // ── Last-displayed image, so toolbar zoom changes don't require a
    //    re-render — we just re-scale the same BufferedImage.
    @Volatile private var lastImage: BufferedImage? = null
    @Volatile private var lastFqn: String = ""
    @Volatile private var lastSceneW: Int = 0
    @Volatile private var lastSceneH: Int = 0
    @Volatile private var lastDisplayScale: Double = 1.0

    init {
        background = JBColor.background()

        // Register livePanel as a child Disposable so its embedded
        // ComposePanel (Skia surface + AWT peer) and cached
        // URLClassLoader are released when this panel is disposed.
        // PreviewToolWindowFactory parents THIS panel against the
        // ToolWindow Content disposable, so the chain reaches all the
        // way to project close / tool window unregister.
        Disposer.register(this, livePanel)

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            background = JBColor.background()
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            add(JLabel("Device:"))
            add(deviceCombo)
            add(JLabel("Theme:"))
            add(themeCombo)
            add(JLabel("Zoom:"))
            add(zoomCombo)
            add(refreshBtn)
            add(gotoSourceBtn)
            add(interactiveToggle)
            add(inspectToggle)
            add(multiFrameToggle)
            add(aiMocksToggle)
            add(liveUiToggle)
            add(statusLabel)
        }

        val top = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(header, BorderLayout.SOUTH)
        }

        add(top, BorderLayout.NORTH)
        // Split image (centre) | parameters (right).
        val splitter = javax.swing.JSplitPane(
            javax.swing.JSplitPane.HORIZONTAL_SPLIT,
            scroll,
            paramsPanel,
        ).apply {
            resizeWeight = 0.8
            border = JBUI.Borders.empty()
            isContinuousLayout = true
            dividerSize = 4
        }
        add(splitter, BorderLayout.CENTER)

        wireToolbar()
    }

    private fun wireToolbar() {
        deviceCombo.addActionListener {
            val device = deviceCombo.selectedItem as DevicePreset
            project?.getService(PreviewService::class.java)?.updateRenderParams(size = device.toSize())
        }
        themeCombo.addActionListener {
            val theme = themeCombo.selectedItem as PreviewTheme
            project?.getService(PreviewService::class.java)?.updateRenderParams(theme = theme)
        }
        zoomCombo.addActionListener {
            // Zoom is a pure UI operation — re-scale the cached image
            // without round-tripping to the renderer.
            redrawAtCurrentZoom()
        }
        refreshBtn.addActionListener {
            project?.getService(PreviewService::class.java)?.rerenderLast()
        }
        gotoSourceBtn.addActionListener {
            project?.getService(PreviewService::class.java)?.navigateToCurrentSource()
        }
        multiFrameToggle.addActionListener {
            project?.getService(PreviewService::class.java)
                ?.setMultiFrameMode(multiFrameToggle.isSelected)
        }
        aiMocksToggle.addActionListener {
            project?.getService(PreviewService::class.java)
                ?.setUseAiMocks(aiMocksToggle.isSelected)
        }
        liveUiToggle.addActionListener {
            project?.getService(PreviewService::class.java)
                ?.setLiveUiMode(liveUiToggle.isSelected)
        }
    }

    /**
     * Switch the body area to the native ComposePanel and ask
     * [LivePreviewPanel] to mount the composable directly. Skips the
     * PNG IPC round-trip — the user's code runs in the plugin's JVM.
     */
    fun showLive(fqn: String, classpath: List<String>, theme: com.composepreviewpro.ipc.PreviewTheme) {
        header.text = "${fqn.shortName()} (live)"
        statusLabel.text = "🟢 live"
        body.removeAll()
        body.add(livePanel, BorderLayout.CENTER)
        body.revalidate()
        body.repaint()
        livePanel.show(fqn, classpath, theme)
    }

    // ── Multi-frame display ──────────────────────────────────────

    data class MultiFrameImage(
        val label: String,
        val pngBase64: String,
        val widthPx: Int,
        val heightPx: Int,
    )

    fun showMultiFrame(fqn: String, frames: List<MultiFrameImage>) {
        if (frames.isEmpty()) {
            return showError(fqn, "Multi-frame render produced no images", stackTrace = null)
        }
        header.text = "${fqn.shortName()}   (${frames.size} frames • ${themeCombo.selectedItem})"
        statusLabel.text = "✓ ${frames.size} frames"
        // Disable single-image cached redraw — multi-frame regenerates
        // entirely on toolbar changes via re-render.
        lastImage = null
        body.removeAll()
        val grid = JPanel(java.awt.GridLayout(1, frames.size, 12, 0)).apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(12)
        }
        // Compute a height each frame can fit into, scaling to viewport.
        val targetHeight = scroll.viewport.height.coerceAtLeast(400)
        for (frame in frames) {
            val bytes = Base64.getDecoder().decode(frame.pngBase64)
            val img = ImageIO.read(bytes.inputStream()) ?: continue
            val scale = targetHeight.toDouble() / img.height
            val scaled = img.getScaledInstance(
                (img.width * scale).toInt().coerceAtLeast(1),
                (img.height * scale).toInt().coerceAtLeast(1),
                Image.SCALE_SMOOTH,
            )
            val cell = JPanel(BorderLayout()).apply { background = JBColor.background() }
            cell.add(
                JBLabel("${frame.label}  •  ${frame.widthPx}×${frame.heightPx}", SwingConstants.CENTER).apply {
                    border = JBUI.Borders.emptyBottom(8)
                },
                BorderLayout.NORTH,
            )
            cell.add(JBLabel(ImageIcon(scaled)).apply { horizontalAlignment = SwingConstants.CENTER }, BorderLayout.CENTER)
            grid.add(cell)
        }
        body.add(grid, BorderLayout.CENTER)
        body.revalidate()
        body.repaint()
    }

    // ── State transitions (call on EDT) ──────────────────────────

    fun showLoading(fqn: String) {
        header.text = "Rendering: ${fqn.shortName()}…"
        statusLabel.text = "⏳ rendering"
        body.removeAll()
        body.add(JBLabel("Spawning renderer + composing…", SwingConstants.CENTER), BorderLayout.CENTER)
        body.revalidate()
        body.repaint()
    }

    fun showImage(fqn: String, pngBase64: String, widthPx: Int, heightPx: Int) {
        header.text = "${fqn.shortName()}   (${widthPx}×${heightPx} • ${themeCombo.selectedItem})"
        lastFqn = fqn
        lastSceneW = widthPx
        lastSceneH = heightPx
        val bytes = Base64.getDecoder().decode(pngBase64)
        val img = ImageIO.read(bytes.inputStream())
            ?: return showError(fqn, "Decoded image is null", stackTrace = null)
        lastImage = img
        statusLabel.text = "✓ ${bytes.size / 1024} KB"
        redrawAtCurrentZoom()
    }

    /**
     * Push a fresh parameter summary into the side panel. Called from
     * [PreviewService.executeSingleRender] right after [showImage].
     */
    fun showParameters(params: List<com.composepreviewpro.ipc.ParamInfo>) {
        paramsPanel.update(params)
    }

    /**
     * Switch the side panel into "element-edit" mode showing the
     * arguments of the call site the user clicked on. Edits flow back
     * through [PreviewService.setSelectedArgValue] → PSI write-back →
     * file save → hot reload.
     */
    fun showSelectedElement(
        functionName: String,
        params: List<com.composepreviewpro.ipc.ParamInfo>,
        file: String,
        line: Int,
    ) {
        paramsPanel.updateForElement(functionName, params, file, line)
    }

    fun showError(target: String, message: String, stackTrace: String?) {
        statusLabel.text = "⚠ failed"
        header.text = if (target.isEmpty()) "Preview failed" else "${target.shortName()} — failed"
        body.removeAll()
        val text = buildString {
            appendLine(message)
            if (stackTrace != null) {
                appendLine()
                appendLine("--- stack ---")
                append(stackTrace)
            }
        }
        val area = JTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = false
            font = JBUI.Fonts.create("Monospaced", 12)
            border = JBUI.Borders.empty(12)
            foreground = JBColor.RED
        }
        body.add(JBScrollPane(area), BorderLayout.CENTER)
        body.revalidate()
        body.repaint()
    }

    // ── Zoom-aware redraw ────────────────────────────────────────

    private fun redrawAtCurrentZoom() {
        val img = lastImage ?: return
        val zoom = zoomCombo.selectedItem as ZoomLevel
        val (scaled, scale) = if (zoom == ZoomLevel.FIT) {
            scaleToFit(img, scroll.viewport.width.coerceAtLeast(320), scroll.viewport.height.coerceAtLeast(240))
        } else {
            scale(img, zoom.factor) to zoom.factor
        }
        lastDisplayScale = scale
        val label = JBLabel(ImageIcon(scaled)).apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.TOP
            preferredSize = Dimension(scaled.getWidth(null), scaled.getHeight(null))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            // CRITICAL: Swing draws a dotted focus border around a
            // focused JLabel. After the user clicks the preview the
            // border sticks and overlays the rendered UI. We forbid the
            // label from taking keyboard focus — click events still
            // reach our listeners through MouseListener.
            isFocusable = false
            isRequestFocusEnabled = false
        }
        installInteractionListeners(label)
        body.removeAll()
        body.add(label, BorderLayout.CENTER)
        body.revalidate()
        body.repaint()
    }

    private fun scale(src: BufferedImage, factor: Double): Image {
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        return src.getScaledInstance(w, h, Image.SCALE_SMOOTH)
    }

    private fun scaleToFit(src: BufferedImage, maxW: Int, maxH: Int): Pair<Image, Double> {
        val ratio = minOf(maxW.toDouble() / src.width, maxH.toDouble() / src.height, 1.0)
        return scale(src, ratio) to ratio
    }

    // ── Interaction forwarding ────────────────────────────────────
    //
    // Translates panel-pixel events to scene-pixel events. The image is
    // displayed at `lastDisplayScale` × original size; the inverse takes
    // a click back to scene coordinates the renderer can feed into
    // ImageComposeScene.sendPointerEvent.

    private fun installInteractionListeners(target: JLabel) {
        val service = project?.getService(PreviewService::class.java) ?: return
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 1) Inspect mode is the most explicit signal — left
                //    click selects an element regardless of modifiers.
                // 2) Cmd / Ctrl click remains an always-on shortcut for
                //    advanced users who don't want to toggle the mode.
                // 3) Otherwise, when the Interactive toggle is on, the
                //    click is forwarded as a Compose pointer event.
                val (sx, sy) = toSceneCoords(e.x, e.y) ?: run {
                    service.navigateToCurrentSource()
                    return
                }
                if (inspectToggle.isSelected || e.isMetaDown || e.isControlDown) {
                    service.selectAtPoint(sx, sy)
                    service.navigateAtPoint(sx, sy)
                    return
                }
                if (!interactiveToggle.isSelected) return
                service.sendInteraction(Click(sx, sy))
            }
        }
        target.addMouseListener(mouse)

        val wheel = java.awt.event.MouseWheelListener { e: MouseWheelEvent ->
            if (!interactiveToggle.isSelected) return@MouseWheelListener
            val (sx, sy) = toSceneCoords(e.x, e.y) ?: return@MouseWheelListener
            // preciseWheelRotation is sub-tick where supported (macOS) so
            // trackpad scrolls feel smooth; 60 px per tick is a UX-tuned
            // multiplier that matches typical Compose scroll velocity.
            val deltaY = (e.preciseWheelRotation * 60.0).toFloat()
            service.sendInteraction(Scroll(sx, sy, deltaY))
        }
        target.addMouseWheelListener(wheel)
    }

    private fun toSceneCoords(panelX: Int, panelY: Int): Pair<Int, Int>? {
        if (lastSceneW == 0 || lastSceneH == 0 || lastDisplayScale <= 0) return null
        val sx = (panelX / lastDisplayScale).toInt().coerceIn(0, lastSceneW - 1)
        val sy = (panelY / lastDisplayScale).toInt().coerceIn(0, lastSceneH - 1)
        return sx to sy
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun String.shortName(): String =
        substringAfterLast('.').ifEmpty { this }

    /**
     * Called by Disposer when the parent ToolWindow Content is being
     * unregistered (project close, plugin disable, tool window removal).
     * Children registered with [Disposer.register(this, …)] — currently
     * just [livePanel] — are disposed automatically BEFORE this body
     * runs, so we don't need to call livePanel.dispose() ourselves.
     *
     * Swing components (deviceCombo, themeCombo, scroll, body, …) are
     * plain JVM objects with no native resources; they become GC-eligible
     * once the JFrame holding them goes away.
     */
    override fun dispose() {
        thisLogger().info("[ComposePreview] PreviewPanel disposed")
    }
}

// ─────────────────────────────────────────────────────────────────
//  Toolbar value types
// ─────────────────────────────────────────────────────────────────

internal enum class DevicePreset(val displayName: String, val widthPx: Int, val heightPx: Int) {
    PHONE("Phone (360×800)", 360, 800),
    PHONE_LARGE("Large Phone (411×891)", 411, 891),
    TABLET("Tablet (768×1024)", 768, 1024),
    DESKTOP("Desktop (1280×800)", 1280, 800),
    SQUARE("Square (480×480)", 480, 480);

    override fun toString(): String = displayName
    fun toSize(): RenderSize = RenderSize(widthPx, heightPx)
}

internal enum class ZoomLevel(val displayName: String, val factor: Double) {
    Z25("25%", 0.25),
    Z50("50%", 0.5),
    Z75("75%", 0.75),
    Z100("100%", 1.0),
    Z125("125%", 1.25),
    Z150("150%", 1.5),
    Z200("200%", 2.0),
    Z300("300%", 3.0),
    FIT("Fit", 0.0);

    override fun toString(): String = displayName
}
