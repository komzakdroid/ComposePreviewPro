package com.composepreviewpro.plugin.toolwindow

import com.composepreviewpro.ipc.ParamInfo
import com.composepreviewpro.plugin.inspector.formatBuilderChain
import com.composepreviewpro.plugin.service.PreviewService
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The right-hand side panel that surfaces the currently-rendered
 * composable's parameter values and lets the user override editable
 * ones (primitives + enums). On commit, the panel asks
 * [PreviewService.setArgOverride] which re-renders with the new value.
 *
 * Layout: header + a grid of (name • type chip • editor) rows + a
 * "Reset overrides" button at the bottom.
 */
class ParametersPanel(private val project: Project?) : JPanel(BorderLayout()) {

    private val grid = JPanel(GridBagLayout())
    private val resetBtn = JButton("Reset", AllIcons.Actions.Rollback).apply {
        toolTipText = "Discard all overrides and return to auto-mocked values"
        isFocusPainted = false
    }
    private val clearSelectionBtn = JButton("Exit Edit", AllIcons.Actions.Close).apply {
        toolTipText = "Return to function-level parameters view"
        isFocusPainted = false
    }
    private val header = JBLabel("Parameters").apply {
        border = JBUI.Borders.empty(8, 12, 4, 12)
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
    }

    init {
        background = JBColor.background()
        border = JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0)
        preferredSize = Dimension(320, 100)

        val footer = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            background = JBColor.background()
            add(resetBtn)
            add(clearSelectionBtn)
        }

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(grid).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)

        resetBtn.addActionListener {
            project?.getService(PreviewService::class.java)?.clearArgOverrides()
        }
        clearSelectionBtn.addActionListener {
            project?.getService(PreviewService::class.java)?.clearElementSelection()
        }
    }

    /**
     * Element-level mode — replaces the function-level argument list
     * with the current SELECTED element's call arguments. Edits go
     * back into the source via PSI through PreviewService.
     */
    fun updateForElement(functionName: String, params: List<ParamInfo>, file: String, line: Int) {
        header.text = "▸ Edit: $functionName ($file:$line)"
        header.foreground = java.awt.Color(0x22C55E)
        grid.removeAll()

        if (params.isEmpty()) {
            grid.add(JBLabel("(no editable arguments at this call)").apply {
                border = JBUI.Borders.empty(12)
                foreground = JBColor.GRAY
            })
            grid.revalidate(); grid.repaint()
            return
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
        }
        for (p in params) {
            val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                background = JBColor.background()
                add(JBLabel(p.name).apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) })
                add(JBLabel(p.typeName).apply {
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.emptyLeft(8)
                })
            }
            gbc.gridx = 0; gbc.weightx = 1.0
            grid.add(row1, gbc); gbc.gridy++

            val editor = makeValueComponent(p, elementMode = true)
            grid.add(editor, gbc); gbc.gridy++
            grid.add(JLabel(" "), gbc); gbc.gridy++
        }
        grid.revalidate(); grid.repaint()
    }

    /**
     * Build the value editor for a single ParamInfo.
     *
     *   • Editable  → single-line [JBTextField] (commit on Enter / focus loss).
     *   • Read-only → multi-line [JTextArea] with line-wrap on, and a chain-
     *     aware formatter applied. This is what fixes the "Modifier .size(...)
     *     .clip(...) spread on one line" issue: instead of a JBLabel that
     *     collapses newlines to single spaces, we use a real multi-line
     *     component, and we PRE-FORMAT the text so each top-level `.method`
     *     in a builder chain lives on its own indented line.
     *
     * The [elementMode] flag toggles whose service method receives commits
     * (function-level overrides vs. selected-element PSI edits).
     */
    private fun makeValueComponent(p: ParamInfo, elementMode: Boolean): JComponent {
        if (p.editable) {
            return JBTextField(p.currentValue).apply {
                addCommitListener { value ->
                    if (value != p.currentValue) {
                        val svc = project?.getService(PreviewService::class.java)
                        if (elementMode) svc?.setSelectedArgValue(p.name, value)
                        else svc?.setArgOverride(p.name, value)
                    }
                }
            }
        }
        val formatted = formatBuilderChain(p.currentValue)
        return JTextArea(formatted).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = false
            background = JBColor.background()
            foreground = JBColor.GRAY
            font = JBFont.label()
            border = JBUI.Borders.empty(4, 6)
            margin = Insets(0, 0, 0, 0)
            toolTipText = "Positional argument — click the element directly in the canvas to edit"
        }
    }


    fun update(params: List<ParamInfo>) {
        header.text = "Parameters"
        header.foreground = JBColor.foreground()
        grid.removeAll()
        if (params.isEmpty()) {
            grid.add(
                JBLabel("(no parameters)").apply {
                    border = JBUI.Borders.empty(12)
                    foreground = JBColor.GRAY
                },
            )
            grid.revalidate()
            grid.repaint()
            return
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
        }

        for (p in params) {
            // Row 1: parameter name + type tag
            val nameLabel = JBLabel(p.name).apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
            }
            val typeTag = JBLabel(p.typeName).apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyLeft(8)
            }
            val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                background = JBColor.background()
                add(nameLabel)
                add(typeTag)
            }
            gbc.gridx = 0
            gbc.weightx = 1.0
            grid.add(row1, gbc)
            gbc.gridy++

            // Row 2: editor or read-only display
            val editor = makeValueComponent(p, elementMode = false)
            grid.add(editor, gbc)
            gbc.gridy++

            // Spacer row
            grid.add(JLabel(" "), gbc)
            gbc.gridy++
        }
        grid.revalidate()
        grid.repaint()
    }

    /**
     * Commit on Enter or focus-loss (whichever is first). Without this
     * users would have to click Apply every time — too clunky for a
     * tight edit loop.
     */
    private fun JBTextField.addCommitListener(onCommit: (String) -> Unit) {
        var lastCommitted = text
        addActionListener {
            if (text != lastCommitted) {
                lastCommitted = text
                onCommit(text)
            }
        }
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (text != lastCommitted) {
                    lastCommitted = text
                    onCommit(text)
                }
            }
        })
        // Track typing for highlighting but don't fire on every keystroke
        // — that would issue an IPC round-trip per character.
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {}
            override fun removeUpdate(e: DocumentEvent) {}
            override fun changedUpdate(e: DocumentEvent) {}
        })
    }
}
