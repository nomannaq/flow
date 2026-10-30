package dev.flow.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.text.DefaultCaret
import javax.swing.text.html.HTMLEditorKit
import org.intellij.markdown.MarkdownElementTypes as E
import org.intellij.markdown.MarkdownTokenTypes as T
import org.intellij.markdown.flavours.gfm.GFMElementTypes as G
import org.intellij.markdown.flavours.gfm.GFMTokenTypes as GT

/** Parse with the bundled Markdown library; generate only inert, allowlisted markup. */
internal class MarkdownMessage(private val scope: CoroutineScope? = null) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
    }
    internal data class Block(val text: String, val code: Boolean = false, val language: String = "")
    private var source = ""
    private var revision = 0L
    private var parsing: Job? = null
    private var blocks = emptyList<Block>()
    private var controls = emptyList<JComponent>()
    private var fontOffset = 0

    fun setFontOffset(value: Int) {
        if (fontOffset == value) return
        fontOffset = value.coerceIn(-4, 12)
        controls.forEach(::applyFont)
        revalidate()
    }

    private fun applyFont(control: JComponent) {
        val base = javax.swing.UIManager.getFont("Label.font")
        control.font = java.awt.Font(if (control is JBTextArea) java.awt.Font.MONOSPACED else base.family, java.awt.Font.PLAIN, (base.size + JBUI.scale(fontOffset)).coerceAtLeast(JBUI.scale(8)))
    }

    fun update(text: String) {
        if (source == text && controls.isNotEmpty()) return
        source = text
        val version = ++revision
        parsing?.cancel()
        if (scope == null) {
            display(parse(text))
        } else {
            if (controls.isEmpty()) add(FlowUi.note(text), BorderLayout.CENTER)
            parsing = scope.launch(Dispatchers.Default) {
                val parsed = runCatching { parse(text) }.getOrElse { listOf(Block(escape(text))) }
                withContext(Dispatchers.EDT) { if (version == revision) display(parsed) }
            }
        }
    }

    fun dispose() {
        revision++
        parsing?.cancel()
    }

    private fun display(next: List<Block>) {
        if (next.size != blocks.size || next.indices.any { next[it].code != blocks[it].code || next[it].language != blocks[it].language }) {
            removeAll()
            controls = next.map { block ->
                if (block.code) {
                    FlowUi.note(block.text).apply {
                        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size)
                        isOpaque = true
                        background = javax.swing.UIManager.getColor("TextField.background")
                        border = JBUI.Borders.empty(8)
                        accessibleContext.accessibleName = "Code block"
                    }
                } else {
                    object : JEditorPane() {
                        override fun getScrollableTracksViewportWidth() = true
                        override fun getPreferredSize(): java.awt.Dimension {
                            val available = (parent?.width?.takeIf { it > 0 } ?: JBUI.scale(320)) - insets.left - insets.right
                            val root = getUI().getRootView(this)
                            root.setSize(available.coerceAtLeast(1).toFloat(), Float.MAX_VALUE)
                            return java.awt.Dimension(0, kotlin.math.ceil(root.getPreferredSpan(javax.swing.text.View.Y_AXIS).toDouble()).toInt() + insets.top + insets.bottom)
                        }
                    }.apply {
                        editorKit = HTMLEditorKit().apply {
                            styleSheet.addRule("body { margin: 0; }")
                            styleSheet.addRule("p { margin-top: 0; margin-bottom: 8px; }")
                            styleSheet.addRule("h1,h2,h3 { margin-top: 6px; margin-bottom: 6px; }")
                            styleSheet.addRule("ul,ol { margin-left: 18px; }")
                            styleSheet.addRule("table { border-collapse: collapse; width: 100%; }")
                            styleSheet.addRule("td { border: 1px solid #808080; padding: 4px; }")
                        }
                        font = javax.swing.UIManager.getFont("Label.font")
                        foreground = javax.swing.UIManager.getColor("Label.foreground")
                        isEditable = false
                        isOpaque = false
                        border = JBUI.Borders.empty()
                        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                        (caret as DefaultCaret).updatePolicy = DefaultCaret.NEVER_UPDATE
                        accessibleContext.accessibleName = "Agent response"
                    }
                }
            }
            controls.forEach(::applyFont)
            val rendered = controls.mapIndexed { index, control ->
                if (!next[index].code) {
                    control
                } else {
                    JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border())
                        add(
                            JPanel(BorderLayout()).apply {
                                add(com.intellij.ui.components.JBLabel(next[index].language.ifBlank { "Code" }).apply { border = JBUI.Borders.emptyLeft(8) }, BorderLayout.CENTER)
                                add(
                                    FlowUi.iconButton(javax.swing.JButton("Copy code"), com.intellij.icons.AllIcons.Actions.Copy, "Copy exact code").apply {
                                        addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(blocks.getOrNull(index)?.text.orEmpty())) }
                                    },
                                    BorderLayout.EAST,
                                )
                            },
                            BorderLayout.NORTH,
                        )
                        add(control, BorderLayout.CENTER)
                    }
                }
            }
            add(FlowUi.column(*rendered.toTypedArray()), BorderLayout.CENTER)
        }
        next.forEachIndexed { index, block ->
            if (blocks.getOrNull(index) != block) {
                when (val control = controls[index]) {
                    is JBTextArea -> control.text = block.text
                    is JEditorPane -> {
                        val start = control.selectionStart
                        val end = control.selectionEnd
                        control.text = "<html><body>${block.text}</body></html>"
                        control.select(start.coerceAtMost(control.document.length), end.coerceAtMost(control.document.length))
                    }
                }
            }
        }
        blocks = next
        revalidate()
        repaint()
    }

    companion object {
        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        internal fun parse(text: String): List<Block> {
            val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
            fun raw(node: ASTNode) = text.substring(node.startOffset, node.endOffset)
            fun html(node: ASTNode): String {
                val tag = when (node.type) {
                    E.PARAGRAPH -> "p"
                    E.STRONG -> "b"
                    E.EMPH -> "i"
                    E.CODE_SPAN -> "code"
                    E.UNORDERED_LIST -> "ul"
                    E.ORDERED_LIST -> "ol"
                    E.LIST_ITEM -> "li"
                    E.BLOCK_QUOTE -> "blockquote"
                    G.TABLE -> "table"
                    G.HEADER, G.ROW -> "tr"
                    GT.CELL -> "td"
                    G.STRIKETHROUGH -> "strike"
                    E.ATX_1, E.SETEXT_1 -> "h1"
                    E.ATX_2, E.SETEXT_2 -> "h2"
                    E.ATX_3, E.ATX_4, E.ATX_5, E.ATX_6 -> "h3"
                    else -> null
                }
                // No agent-authored HTML, href, img, style or URL can enter the document.
                if (node.type in setOf(E.HTML_BLOCK, T.HTML_TAG, E.IMAGE, E.INLINE_LINK, E.AUTOLINK, E.FULL_REFERENCE_LINK, E.SHORT_REFERENCE_LINK, GT.GFM_AUTOLINK)) return escape(raw(node))
                if (node.type in setOf(T.ATX_HEADER, T.EMPH, T.BACKTICK, T.LIST_BULLET, T.LIST_NUMBER, T.SETEXT_1, T.SETEXT_2, T.BLOCK_QUOTE, GT.TABLE_SEPARATOR, GT.TILDE)) return ""
                val body = if (node.children.isEmpty()) escape(raw(node)) else node.children.joinToString("") { html(it) }
                return if (tag == null) body else "<$tag>$body</$tag>"
            }
            val result = mutableListOf<Block>()
            val prose = StringBuilder()
            fun flush() {
                if (prose.isNotBlank()) {
                    result.add(Block(prose.toString()))
                    prose.clear()
                }
            }
            for (node in tree.children) {
                if (node.type == E.CODE_FENCE || node.type == E.CODE_BLOCK) {
                    flush()
                    val contents = node.children.filter { it.type == T.CODE_FENCE_CONTENT || it.type == T.CODE_LINE || it.type == T.EOL }
                    // Preserve the original code range; only strip the fence's own separator.
                    val start = contents.firstOrNull { it.type != T.EOL }?.startOffset
                    val end = node.children.firstOrNull { it.type == T.CODE_FENCE_END }?.startOffset ?: node.endOffset
                    val code = if (start == null) "" else text.substring(start, end)
                    result.add(Block(code, true, node.children.firstOrNull { it.type == T.FENCE_LANG }?.let(::raw).orEmpty()))
                } else {
                    prose.append(html(node))
                }
            }
            flush()
            return result.ifEmpty { listOf(Block(escape(text))) }
        }
    }
}
