package dev.flow.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.UIManager

/** Shared native spacing and scrolling. Rows keep their natural height. */
internal object FlowUi {
    val background = JBColor.namedColor("ToolWindow.background", JBColor(0xFFFFFF, 0x1E1F22))
    val inputBackground = JBColor.namedColor("TextField.background", JBColor(0xF7F8FA, 0x2B2D30))

    fun column(vararg children: JComponent): JPanel = Column().apply {
        isOpaque = false
        children.forEachIndexed { index, child ->
            // Keep native minimum heights dynamic: wrapped text measured before
            // layout or a theme/font change otherwise freezes a huge stale row.
            add(
                child,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = index
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.NORTHWEST
                    insets = JBUI.insets(0, 0, 8, 0)
                },
            )
        }
        add(
            JPanel().apply { isOpaque = false },
            GridBagConstraints().apply {
                gridx = 0
                gridy = children.size
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            },
        )
    }

    fun heading(title: String, subtitle: String): JPanel = column(
        JBLabel(title).apply { font = font.deriveFont(java.awt.Font.BOLD, font.size2D + 3) },
        note(subtitle),
    )

    fun note(text: String, rows: Int = 0): JBTextArea = JBTextArea(text, rows, 0).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = UIManager.getFont("Label.font")
        foreground = JBColor.namedColor("ContextHelp.foreground", JBColor(0x616161, 0xA8ADB5))
        (caret as? javax.swing.text.DefaultCaret)?.updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
    }

    fun field(label: String, control: JComponent, hint: String? = null): JPanel = column(
        *listOfNotNull(JBLabel(label).apply { labelFor = control }, control, hint?.let { note(it) }).toTypedArray(),
    )

    fun actions(vararg controls: JComponent): JPanel = JPanel(WrapLayout()).apply {
        isOpaque = false
        controls.forEach { add(it) }
    }

    fun toolbar(vararg controls: JComponent): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        controls.forEach { add(it) }
    }

    fun card(vararg children: JComponent): JPanel = column(*children).apply {
        border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(12))
    }

    fun scroll(content: JComponent, height: Int? = null): JBScrollPane = JBScrollPane(
        if (content is Scrollable) content else WidthPanel(java.awt.BorderLayout()).apply { add(content, java.awt.BorderLayout.CENTER) },
    ).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        if (height != null) preferredSize = JBUI.size(0, height)
        minimumSize = JBUI.size(0, 40)
    }

    fun primary(button: JButton): JButton = button.apply { putClientProperty("JButton.buttonType", "default") }

    fun iconButton(button: JButton, icon: javax.swing.Icon, hint: String): JButton = button.apply {
        accessibleContext.accessibleName = text
        text = null
        this.icon = icon
        toolTipText = hint
        putClientProperty("JButton.buttonType", "toolBarButton")
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = true
        preferredSize = JBUI.size(30, 30)
        minimumSize = preferredSize
    }

    private class Column : WidthPanel(GridBagLayout())

    private open class WidthPanel(layout: java.awt.LayoutManager) :
        JPanel(layout),
        Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(20)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = (visibleRect.height - JBUI.scale(20)).coerceAtLeast(20)
    }

    /** FlowLayout wraps controls, but its default preferred height does not. */
    private class WrapLayout : FlowLayout(LEFT, JBUI.scale(6), JBUI.scale(4)) {
        override fun preferredLayoutSize(target: Container): Dimension {
            val width = (target.width.takeIf { it > 0 } ?: target.parent?.width?.takeIf { it > 0 } ?: JBUI.scale(360)) - target.insets.left - target.insets.right - hgap * 2
            var x = 0
            var row = 0
            var height = vgap * 2
            for (child: Component in target.components.filter { it.isVisible }) {
                val size = child.preferredSize
                // Match FlowLayout: it checks the fit before adding the next gap.
                if (x > 0 && x + size.width > width) {
                    height += row + vgap
                    x = 0
                    row = 0
                }
                x += size.width + if (x > 0) hgap else 0
                row = maxOf(row, size.height)
            }
            // These rows fill their container. Reporting its width as our preferred
            // width double-counts siblings in BorderLayout and collapses GridBag rows.
            return Dimension(0, height + row + target.insets.top + target.insets.bottom)
        }
        override fun minimumLayoutSize(target: Container) = Dimension(0, preferredLayoutSize(target).height)
    }
}

internal class Disclosure(title: String, content: JComponent, expanded: Boolean = false, trailing: JComponent? = null) : JPanel(java.awt.BorderLayout()) {
    private val toggle = JToggleButton(title, expanded).apply {
        putClientProperty("html.disable", true)
        horizontalAlignment = javax.swing.SwingConstants.LEFT
        accessibleContext.accessibleName = title
        isContentAreaFilled = false
        border = JBUI.Borders.empty(6, 0)
    }
    fun setTitle(title: String) {
        toggle.text = title
        toggle.accessibleContext.accessibleName = title
    }
    init {
        add(
            JPanel(java.awt.BorderLayout()).apply {
                isOpaque = false
                add(toggle, java.awt.BorderLayout.CENTER)
                if (trailing != null) add(trailing, java.awt.BorderLayout.EAST)
            },
            java.awt.BorderLayout.NORTH,
        )
        add(content, java.awt.BorderLayout.CENTER)
        fun update() {
            content.isVisible = toggle.isSelected
            toggle.icon = if (toggle.isSelected) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            revalidate()
            repaint()
        }
        toggle.addActionListener { update() }
        update()
    }
}
