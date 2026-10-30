package dev.flow.ui

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import dev.flow.bridge.AgentView
import dev.flow.bridge.EngineController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/** The list is a projection of the Rust queue. It never dispatches on UI polling. */
internal class PromptQueuePanel(private val controller: EngineController) :
    JPanel(BorderLayout()),
    Disposable {
    private val scope = CoroutineScope(controller.scope.coroutineContext + SupervisorJob(controller.scope.coroutineContext[Job]) + Dispatchers.EDT)
    private val title = JBLabel()
    private val rows = JBList<String>().apply {
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        cellRenderer = javax.swing.DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
        visibleRowCount = 2
        accessibleContext.accessibleName = "Queued prompts"
    }
    private val pause = JButton("Pause queue", AllIcons.Actions.Pause)
    private val remove = FlowUi.iconButton(JButton("Remove queued prompt"), com.intellij.icons.AllIcons.Actions.Close, "Remove selected queued prompt")
    private val up = FlowUi.iconButton(JButton("Move queued prompt up"), com.intellij.icons.AllIcons.Actions.MoveUp, "Move selected prompt up")
    private val down = FlowUi.iconButton(JButton("Move queued prompt down"), com.intellij.icons.AllIcons.Actions.MoveDown, "Move selected prompt down")
    private val clear = JButton("Clear", AllIcons.General.Delete)
    private var view = AgentView()
    private var connected = false
    private var busy = false

    init {
        isOpaque = false
        isVisible = false
        getAccessibleContext().accessibleName = "Prompt queue"
        FlowUi.iconButton(pause, AllIcons.Actions.Pause, "Pause queued prompts")
        val menu = javax.swing.JPopupMenu().apply {
            listOf("Move up" to up, "Move down" to down, "Remove" to remove, "Clear queue" to clear).forEach { (name, button) ->
                add(
                    javax.swing.JMenuItem(name, button.icon).apply {
                        addActionListener { button.doClick(0) }
                    },
                )
            }
        }
        val more = FlowUi.iconButton(JButton("Queue actions"), AllIcons.Actions.More, "Reorder or remove queued prompts").apply {
            addActionListener {
                menu.components.filterIsInstance<javax.swing.JMenuItem>().zip(listOf(up, down, remove, clear)).forEach { (item, button) -> item.isEnabled = button.isEnabled }
                menu.show(this, 0, height)
            }
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(title, BorderLayout.CENTER)
                add(FlowUi.toolbar(pause, more), BorderLayout.EAST)
            },
            BorderLayout.NORTH,
        )
        add(FlowUi.scroll(rows, 40), BorderLayout.CENTER)
        pause.addActionListener { action(if (view.queuePaused) "resume" else "pause") }
        clear.addActionListener { action("clear") }
        remove.addActionListener { selected()?.let { action("remove", it.id) } }
        up.addActionListener { selected()?.let { action("move", it.id, rows.selectedIndex - 1) } }
        down.addActionListener { selected()?.let { action("move", it.id, rows.selectedIndex + 1) } }
        rows.addListSelectionListener { buttons() }
    }

    private fun selected() = view.queue.getOrNull(rows.selectedIndex)
    private fun action(action: String, id: String? = null, position: Int? = null) {
        val session = view.id ?: return
        if (!connected || busy) return
        busy = true
        buttons()
        scope.launch {
            try {
                controller.submitAgent(
                    "sessions.queue",
                    JsonObject().apply {
                        addProperty("sessionId", session)
                        addProperty("action", action)
                        id?.let { addProperty("id", it) }
                        position?.let { addProperty("position", it) }
                    },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controller.showActionError("Queue action was not confirmed. Inspect the queue before retrying; unknown-outcome prompts must be reviewed before resuming.")
            } finally {
                busy = false
                buttons()
            }
        }
    }

    fun update(agent: AgentView, connected: Boolean) {
        val selected = selected()?.id
        if (view.queue != agent.queue) {
            rows.setListData(agent.queue.map { "${if (it.status == "uncertain") "Outcome unknown · " else ""}${it.preview.lineSequence().firstOrNull().orEmpty()}${if (it.imageCount > 0) " · ${it.imageCount} images" else ""}${if (it.attachmentCount > 0) " · ${it.attachmentCount} files" else ""}" }.toTypedArray())
            rows.selectedIndex = agent.queue.indexOfFirst { it.id == selected }
        }
        view = agent
        this.connected = connected
        isVisible = agent.queue.isNotEmpty()
        title.text = "${agent.queue.size} queued${if (agent.queuePaused) " · paused" else ""}"
        title.toolTipText = "Stop, disconnect or failure pauses the queue. Pending prompts remain recoverable from History."
        pause.accessibleContext.accessibleName = if (agent.queuePaused) "Resume queue" else "Pause queue"
        pause.toolTipText = if (agent.queuePaused) "Resume queued prompts" else "Pause queued prompts"
        pause.icon = if (agent.queuePaused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
        buttons()
    }
    private fun buttons() {
        val enabled = connected && !busy
        pause.isEnabled = enabled && view.status in setOf("running", "ready") && view.queue.none { it.status == "uncertain" }
        remove.isEnabled = enabled && selected()?.status in setOf("pending", "uncertain")
        up.isEnabled = remove.isEnabled && rows.selectedIndex > 0
        down.isEnabled = remove.isEnabled && rows.selectedIndex < view.queue.lastIndex
        clear.isEnabled = enabled && view.queue.any { it.status != "dispatching" }
    }
    override fun dispose() {
        scope.cancel()
    }
}
