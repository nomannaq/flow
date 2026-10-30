package dev.flow.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.flow.bridge.EngineController
import dev.flow.bridge.EngineView
import dev.flow.bridge.optionalText
import kotlinx.coroutines.CoroutineScope
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTabbedPane

/** Secondary views keep the same chat instance, draft, context and reader position. */
internal class WorkbenchPanel(project: Project, controller: EngineController, scope: CoroutineScope) :
    JPanel(BorderLayout()),
    Disposable {
    private val pages = JPanel(CardLayout())
    internal var destination = "Chat"
        private set
    private val title = JBLabel("New conversation").apply {
        font = font.deriveFont(java.awt.Font.BOLD)
        putClientProperty("html.disable", true)
    }
    private val back = FlowUi.iconButton(JButton("Back to conversation"), AllIcons.Actions.Back, "Back to conversation").apply {
        addActionListener { showPage(if (destination == "Recorded conversation") "History" else "Chat") }
    }
    internal val sessions = AgentSessionPanel(controller, project, openSettings = { showPage("Settings") }, openHistory = { showPage("History") }, openChanges = ::reviewCurrentChanges)
    private val navigation = FlowUi.toolbar(
        FlowUi.iconButton(JButton("New conversation"), AllIcons.General.Add, "New conversation").apply { addActionListener { sessions.requestNewConversation() } },
        FlowUi.iconButton(JButton("History"), AllIcons.Vcs.History, "Browse session history").apply { addActionListener { showPage("History") } },
        FlowUi.iconButton(JButton("Find in conversation"), AllIcons.Actions.Search, "Find in conversation").apply { addActionListener { sessions.findConversation() } },
        FlowUi.iconButton(JButton("Settings"), AllIcons.General.Settings, "Agent and engine settings").apply { addActionListener { showPage("Settings") } },
    )
    private val header = JPanel(BorderLayout())
    private val details = FlowUi.note("")
    private val changes = JTabbedPane()
    private val application: ApplicationPanel
    private val candidate: CandidateReviewPanel
    private val history: SessionHistoryPanel
    private val recordedConversation = ConversationHistoryPanel(
        project,
        controller,
        scope,
        rewind = { source, event ->
            showPage("Chat")
            sessions.requestRewind(source, event)
        },
        recover = { command ->
            sessions.restoreQueuedDraft(command)
            showPage("Chat")
        },
    )
    private var conversationTitle = "New conversation"

    init {
        getAccessibleContext().accessibleName = "Flow workbench"
        background = FlowUi.background
        pages.isOpaque = false
        border = JBUI.Borders.empty(0, 4, 4, 4)
        header.apply {
            layout = BorderLayout(JBUI.scale(4), 0)
            isOpaque = false
            border = JBUI.Borders.empty(6, 4)
            add(back, BorderLayout.WEST)
            add(title, BorderLayout.CENTER)
            add(navigation, BorderLayout.EAST)
        }
        candidate = CandidateReviewPanel(project, controller, scope, applyCandidate = { id ->
            application.selectCandidate(id)
            showPage("Changes")
            changes.selectedIndex = 1
        })
        application = ApplicationPanel(project, controller, scope, reviewCandidate = { id ->
            candidate.selectCandidate(id)
            showPage("Changes")
            changes.selectedIndex = 0
        })
        history = SessionHistoryPanel(project, controller, scope, reviewSession = { id ->
            candidate.selectSession(id)
            showPage("Changes")
            changes.selectedIndex = 0
        }, openConversation = { id ->
            recordedConversation.open(id)
            showPage("Recorded conversation")
        }) { source ->
            sessions.newConversation(source)
            showPage("Chat")
        }
        changes.addTab("Review", candidate)
        changes.addTab("Apply", application)
        changes.accessibleContext.accessibleName = "Review and apply steps"
        val diagnostics = Disclosure(
            "Engine & context",
            FlowUi.column(
                details,
                FlowUi.actions(
                    JButton("Check connection", AllIcons.Actions.Refresh).apply { addActionListener { controller.health() } },
                    JButton("Restart engine", AllIcons.Actions.Restart).apply { addActionListener { controller.restart() } },
                ),
            ),
        )
        pages.add(sessions, "Chat")
        pages.add(history, "History")
        pages.add(recordedConversation, "Recorded conversation")
        pages.add(changes, "Changes")
        pages.add(FlowUi.scroll(FlowUi.column(sessions.configuration, diagnostics).apply { border = JBUI.Borders.empty(12) }), "Settings")
        add(header, BorderLayout.NORTH)
        add(pages, BorderLayout.CENTER)
        sessions.addPropertyChangeListener("reactAvailable") { header.isVisible = destination != "Chat" || !sessions.usesReact }
        showPage("Chat")
    }

    private fun reviewCurrentChanges() {
        sessions.webState().optionalText("sessionId")?.let(candidate::selectSession)
        changes.selectedIndex = 0
        showPage("Changes")
    }

    internal fun showPage(name: String) {
        require(name in setOf("Chat", "History", "Recorded conversation", "Changes", "Settings"))
        destination = name
        header.isVisible = name != "Chat" || !sessions.usesReact
        (pages.layout as CardLayout).show(pages, name)
        back.isVisible = name != "Chat"
        back.toolTipText = if (name == "Recorded conversation") "Back to session history" else "Back to conversation"
        navigation.isVisible = name == "Chat"
        title.text = if (name == "Chat") conversationTitle else name
        if (name == "History") history.refreshSessions()
        revalidate()
        repaint()
    }

    fun update(view: EngineView) {
        sessions.update(view)
        header.isVisible = destination != "Chat" || !sessions.usesReact
        // Update hidden projections too, without rebuilding the mounted conversation.
        history.update(view)
        recordedConversation.update(view)
        candidate.update(view)
        application.update(view)
        conversationTitle = sessions.conversationTitle
        if (destination == "Chat") title.text = conversationTitle
        title.toolTipText = title.text
        details.text = listOf(view.status, view.pid?.let { "Engine PID · $it" }.orEmpty(), view.historyStatus, view.health, view.contextStatus).filter(String::isNotBlank).joinToString("\n\n")
    }

    override fun dispose() {
        sessions.dispose()
        history.dispose()
        recordedConversation.dispose()
        candidate.dispose()
        application.dispose()
    }
}
