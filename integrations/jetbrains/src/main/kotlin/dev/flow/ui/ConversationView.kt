package dev.flow.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.flow.bridge.AgentEvent
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret

/** Bounded native transcript. Streaming follows only while the reader is at the end. */
internal class ConversationView(
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
    private val quote: ((String) -> Unit)? = null,
    emptyTitle: String = "What are we building?",
    emptyDescription: String = "Ask anything, or describe a change.",
    private val project: com.intellij.openapi.project.Project? = null,
    private val controller: dev.flow.bridge.EngineController? = null,
    private val rewind: ((String, AgentEvent) -> Unit)? = null,
    private val changes: javax.swing.JComponent? = null,
    private val draftTask: ((String) -> Unit)? = null,
) : JPanel(BorderLayout()) {
    private val search = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "Find in loaded messages"
        accessibleContext.accessibleName = "Find in loaded conversation messages"
    }
    private val searchStatus = JBLabel("0 matching messages")
    private val searchBar: JPanel = FlowUi.column(
        search,
        FlowUi.actions(
            JButton("Previous", AllIcons.Actions.PreviousOccurence).apply { addActionListener { find(-1) } },
            JButton("Next", AllIcons.Actions.NextOccurence).apply { addActionListener { find(1) } },
            searchStatus,
            JButton("Close find", AllIcons.Actions.Close).apply { addActionListener { searchBar.isVisible = false } },
        ),
    ).apply { isVisible = false }
    private var searchIndex = -1
    private val messages = object : JPanel(BorderLayout()), javax.swing.Scrollable {
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = rendered.isEmpty()
        override fun getScrollableUnitIncrement(rect: java.awt.Rectangle, orientation: Int, direction: Int) = JBUI.scale(20)
        override fun getScrollableBlockIncrement(rect: java.awt.Rectangle, orientation: Int, direction: Int) = (rect.height - JBUI.scale(20)).coerceAtLeast(20)
    }
    internal val scroll = FlowUi.scroll(messages)
    private val latest = JButton("Jump to latest", AllIcons.Actions.MoveDown).apply {
        accessibleContext.accessibleName = "Jump to latest message"
        addActionListener {
            follow = true
            scrollToEnd()
        }
    }
    private val providerMark = JBLabel(AllIcons.Toolwindows.ToolWindowAskAI).apply { horizontalAlignment = javax.swing.SwingConstants.CENTER }
    private val empty = FlowUi.column(
        providerMark,
        JBLabel(emptyTitle, javax.swing.SwingConstants.CENTER).apply { font = font.deriveFont(java.awt.Font.BOLD, font.size2D + 3) },
        FlowUi.note(emptyDescription).apply { alignmentX = CENTER_ALIGNMENT },
        FlowUi.column(
            *listOf(
                "Understand this project" to "Explain how this project is organized and where I should start reading.",
                "Review recent changes" to "Review the recent committed changes for bugs and missing tests. Explain your findings before changing files.",
                "Plan a change" to "Help me plan a change to this project. Ask me what I want to build first.",
            ).map { (label, task) ->
                JButton(label, AllIcons.Actions.Forward).apply {
                    horizontalAlignment = javax.swing.SwingConstants.LEFT
                    isContentAreaFilled = false
                    margin = JBUI.insets(8, 10)
                    addActionListener { draftTask?.invoke(task) }
                }
            }.toTypedArray(),
        ).apply { isVisible = draftTask != null },
    ).apply { border = JBUI.Borders.empty(20) }
    private var rendered = emptyList<AgentEvent>()
    private var rows = emptyList<Pair<AgentEvent, javax.swing.JComponent>>()
    private var session: String? = null
    private var cards = emptyList<javax.swing.JComponent>()
    private var pictures = emptyList<TranscriptAttachments>()
    private var follow = true
    private var rendering = false
    private var revision = 0L
    private var fontOffset = 0
    private var provider = ""

    fun setProvider(value: String) {
        if (provider == value) return
        provider = value
        val icon = ProviderIcons.forAgent(value) ?: AllIcons.Toolwindows.ToolWindowAskAI
        providerMark.icon = com.intellij.util.IconUtil.scale(icon, providerMark, JBUI.scale(40).toFloat() / icon.iconHeight.coerceAtLeast(1))
    }

    private fun showWelcome() {
        messages.add(
            JPanel(java.awt.GridBagLayout()).apply {
                isOpaque = false
                add(empty)
            },
            BorderLayout.CENTER,
        )
        if (changes != null) messages.add(changes, BorderLayout.SOUTH)
    }

    fun setFontOffset(value: Int) {
        if (fontOffset == value) return
        fontOffset = value.coerceIn(-4, 12)
        rows.forEach { (_, body) -> applyFont(body) }
        revalidate()
    }

    private fun applyFont(body: javax.swing.JComponent) {
        if (body is MarkdownMessage) {
            body.setFontOffset(fontOffset)
        } else {
            val base = javax.swing.UIManager.getFont("Label.font")
            body.font = base.deriveFont((base.size2D + JBUI.scale(fontOffset)).coerceAtLeast(JBUI.scale(8).toFloat()))
        }
    }

    init {
        background = FlowUi.background
        messages.isOpaque = false
        scroll.viewport.background = background
        scroll.viewport.isOpaque = true
        changes?.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentShown(event: java.awt.event.ComponentEvent) {
                empty.isVisible = false
                messages.revalidate()
            }
            override fun componentHidden(event: java.awt.event.ComponentEvent) {
                empty.isVisible = rendered.isEmpty()
                messages.revalidate()
            }
        })
        getAccessibleContext().accessibleName = "Agent conversation"
        add(searchBar, BorderLayout.NORTH)
        search.addActionListener { find(1) }
        search.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) {
                searchIndex = -1
                searchStatus.text = "${matches().size} matching messages"
            }
        })
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "flow.find")
        actionMap.put(
            "flow.find",
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) = showFind()
            },
        )
        add(scroll, BorderLayout.CENTER)
        add(
            FlowUi.actions(latest).apply {
                isVisible = false
                name = "latest-actions"
            },
            BorderLayout.SOUTH,
        )
        latest.isVisible = false
        showWelcome()
        scroll.verticalScrollBar.addAdjustmentListener {
            if (!rendering || it.valueIsAdjusting) {
                val bar = scroll.verticalScrollBar
                follow = bar.maximum - bar.visibleAmount - bar.value <= JBUI.scale(24)
                latest.isVisible = !follow && bar.maximum - bar.visibleAmount > JBUI.scale(24)
                latest.parent.isVisible = latest.isVisible
            }
        }
        scroll.addMouseWheelListener { if (it.wheelRotation < 0) follow = false }
    }

    fun update(allEvents: List<AgentEvent>, sessionId: String? = null) {
        if (session != sessionId) {
            pictures.forEach { it.dispose() }
            pictures = emptyList()
            rows.map { it.second }.filterIsInstance<MarkdownMessage>().forEach { it.dispose() }
            rendered = emptyList()
            rows = emptyList()
            cards = emptyList()
            follow = true
            session = sessionId
            messages.removeAll()
        }
        empty.isVisible = changes?.isVisible != true
        val events = messages(allEvents)
        if (events == rendered && messages.componentCount > 0) return
        val version = ++revision
        val previous = rendered
        rendered = events
        val oldPosition = scroll.viewport.viewPosition
        val stick = follow
        rendering = true
        // Update existing text components when streaming; keep selection and disclosures.
        if (events.isNotEmpty() && events.size == rows.size && events.indices.all {
                events[it].kind == previous.getOrNull(it)?.kind && events[it].toolCallId == previous.getOrNull(it)?.toolCallId && events[it].turnId == previous[it].turnId && events[it].images == previous[it].images && events[it].attachments == previous[it].attachments &&
                    (events[it].kind == "tool.call" || events[it].text == previous[it].text || events[it].kind == "message.delta" && events[it].text.startsWith(previous[it].text))
            }
        ) {
            events.forEachIndexed { index, event ->
                when (val body = rows[index].second) {
                    is ToolCallView -> body.update(event)
                    is MarkdownMessage -> body.update(event.text)
                    is JBTextArea -> if (body.text != event.text) {
                        if (event.text.startsWith(body.text)) body.append(event.text.substring(body.text.length)) else body.text = event.text
                    }
                }
            }
        } else {
            pictures.forEach { it.dispose() }
            pictures = emptyList()
            messages.removeAll()
            rows.map { it.second }.filterIsInstance<MarkdownMessage>().forEach { it.dispose() }
            rows = events.map { event ->
                event to if (event.kind == "tool.call") {
                    ToolCallView(event)
                } else if (event.kind == "message.delta") {
                    MarkdownMessage(scope).apply { update(event.text) }
                } else {
                    FlowUi.note(event.text).apply {
                        foreground = javax.swing.UIManager.getColor("Label.foreground")
                        accessibleContext.accessibleName = when (event.kind) {
                            "turn.started" -> "Your message"
                            "message.delta" -> "Agent response"
                            else -> "Agent activity details"
                        }
                        (caret as DefaultCaret).updatePolicy = DefaultCaret.NEVER_UPDATE
                    }
                }
            }
            rows.forEach { (_, body) -> applyFont(body) }
            cards = rows.mapIndexed { rowIndex, (event, body) ->
                val card = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    border = JBUI.Borders.empty(if (event.kind == "turn.started") 10 else 6, 14)
                    isOpaque = event.kind == "turn.started"
                    if (event.kind == "turn.started") {
                        background = com.intellij.ui.JBColor.namedColor("Flow.UserMessage.background", com.intellij.ui.JBColor(0xF0F2F5, 0x2B2D30))
                        border = JBUI.Borders.empty(10, 12, 4, 12)
                    }
                    add(body, BorderLayout.CENTER)
                    val attachments = if ((event.images.isNotEmpty() || event.attachments.isNotEmpty()) && event.turnId != null && sessionId != null && controller != null && scope != null) {
                        TranscriptAttachments(project, controller, sessionId, event.turnId, event.images, scope, event.attachments).also { pictures = pictures + it }
                    } else {
                        null
                    }
                    val actions = FlowUi.toolbar(
                        FlowUi.iconButton(JButton("Copy message"), AllIcons.Actions.Copy, "Copy exact message").apply {
                            addActionListener {
                                val text = rendered.getOrNull(rowIndex)?.text ?: event.text
                                com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(text))
                            }
                        },
                        FlowUi.iconButton(JButton("Quote"), AllIcons.General.Balloon, "Quote message").apply {
                            isVisible = quote != null
                            addActionListener { quote?.invoke(rendered.getOrNull(rowIndex)?.text ?: event.text) }
                        },
                        FlowUi.iconButton(JButton("Rewind"), AllIcons.Actions.Undo, "Rewind files to before this message").apply {
                            isVisible = rewind != null && event.kind == "turn.started" && event.turnId != null
                            addActionListener { sessionId?.let { rewind?.invoke(it, rendered.getOrNull(rowIndex) ?: event) } }
                        },
                    )
                    actions.isVisible = event.kind != "tool.call"
                    actions.components.forEach {
                        it.preferredSize = JBUI.size(24, 24)
                        it.minimumSize = it.preferredSize
                    }
                    add(
                        JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                            isOpaque = false
                            if (attachments != null) add(attachments, BorderLayout.NORTH)
                            add(actions, if (event.kind == "turn.started") BorderLayout.EAST else BorderLayout.WEST)
                        },
                        BorderLayout.SOUTH,
                    )
                }
                if (event.kind == "turn.started") {
                    JPanel(BorderLayout()).apply {
                        isOpaque = false
                        border = object : javax.swing.border.AbstractBorder() {
                            override fun getBorderInsets(component: java.awt.Component) = java.awt.Insets(0, (component.width * 0.15).toInt(), 0, 0)
                        }
                        add(card, BorderLayout.CENTER)
                    }
                } else {
                    card
                }
            }
            if (cards.isEmpty()) {
                showWelcome()
            } else {
                messages.add(FlowUi.column(*(cards + listOfNotNull(changes)).toTypedArray()), BorderLayout.NORTH)
            }
        }
        messages.revalidate()
        if (searchBar.isVisible) searchStatus.text = "${matches().size} matching messages"
        SwingUtilities.invokeLater {
            if (version != revision) return@invokeLater
            if (stick && follow) scrollToEnd() else scroll.viewport.viewPosition = oldPosition
            rendering = false
        }
    }

    fun showFind() {
        searchBar.isVisible = true
        search.requestFocusInWindow()
        search.selectAll()
        revalidate()
    }

    private fun matches() = if (search.text.isBlank()) {
        emptyList()
    } else {
        rendered.indices.filter { index ->
            val row = rendered[index]
            listOfNotNull(row.text, row.tool?.input, row.tool?.output).any { it.contains(search.text, ignoreCase = true) }
        }
    }

    private fun find(direction: Int) {
        val found = matches()
        if (found.isEmpty()) {
            searchStatus.text = "0 matching messages"
            return
        }
        searchIndex = if (searchIndex < 0) {
            if (direction > 0) 0 else found.lastIndex
        } else {
            Math.floorMod(searchIndex + direction, found.size)
        }
        follow = false
        cards[found[searchIndex]].let { it.scrollRectToVisible(java.awt.Rectangle(0, 0, it.width, minOf(it.height, scroll.viewport.height))) }
        searchStatus.text = "${searchIndex + 1} of ${found.size} matching messages"
    }

    private fun scrollToEnd() {
        scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
        latest.isVisible = false
        latest.parent.isVisible = false
    }

    fun dispose() {
        pictures.forEach { it.dispose() }
        pictures = emptyList()
        revision++
        rows.map { it.second }.filterIsInstance<MarkdownMessage>().forEach { it.dispose() }
    }

    companion object {
        fun messages(allEvents: List<AgentEvent>): List<AgentEvent> = ConversationProjection.messages(allEvents, includeDelegations = true)
        fun isMessage(event: AgentEvent): Boolean = event.kind in setOf("turn.started", "turn.prompt.delta", "message.delta")

        // Connection observations remain separate from message and tool content.
        fun isConnectionDetail(event: AgentEvent): Boolean = event.kind in setOf("adapter.compatibility", "context.attachment", "session.ready", "turn.completed", "workspace.recorded", "permission.requested", "permission.resolved") ||
            (event.kind == "agent.update" && event.text == "available_commands_update")
    }
}
