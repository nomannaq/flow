package dev.flow.ui

import com.intellij.icons.AllIcons
import dev.flow.bridge.AgentEvent
import dev.flow.bridge.AgentToolCall
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/** Join patches at the call's original position, independently of live vs journal delivery. */
internal object ConversationProjection {
    fun relevant(event: AgentEvent) = ConversationView.isMessage(event) || event.kind in setOf("operation.started", "operation.updated", "operation.finished", "turn.cancelling", "turn.completed", "session.closed", "session.error")

    fun messages(allEvents: List<AgentEvent>, includeDelegations: Boolean = false): List<AgentEvent> {
        val rows = mutableListOf<AgentEvent>()
        val calls = mutableMapOf<Pair<String?, String>, Int>()
        val hidden = mutableSetOf<Pair<String?, String>>()
        val cancelling = mutableSetOf<String?>()
        var boundary = false
        fun finish(turn: String?, status: String) {
            rows.indices.forEach { index ->
                val row = rows[index]
                val tool = row.tool
                if (tool != null && (turn == null || row.turnId == turn) && tool.status !in setOf("completed", "failed", "interrupted", "unknown")) {
                    rows[index] = row.copy(tool = tool.copy(status = status))
                }
            }
        }
        for (event in allEvents) {
            when {
                event.kind == "turn.cancelling" -> cancelling.add(event.turnId)
                event.kind in setOf("turn.completed", "session.closed", "session.error") -> {
                    finish(event.turnId, if (event.turnId in cancelling || event.kind != "turn.completed") "interrupted" else "unknown")
                    boundary = true
                }
                event.kind in setOf("operation.started", "operation.updated", "operation.finished") -> {
                    val key = event.toolCallId?.let { event.turnId to it }
                    val excluded = event.tool?.name?.lowercase() in setOf("todowrite", "update_plan", "taskcreate", "taskupdate", "tasklist") ||
                        !includeDelegations && (event.tool?.name?.lowercase() in setOf("agent", "task", "spawn_agent") || event.tool?.parentToolCallId != null)
                    if (excluded) {
                        key?.let(hidden::add)
                        continue
                    }
                    if (key in hidden) continue
                    val index = key?.let(calls::get)
                    val previous = index?.let { rows[it] }
                    val legacyStatus = when {
                        event.text.endsWith("(failed)") -> "failed"
                        event.text.endsWith("(completed)") -> "completed"
                        event.kind == "operation.finished" -> "unknown"
                        else -> "pending"
                    }
                    val patch = event.tool ?: AgentToolCall(
                        title = event.text.takeUnless { it.startsWith("tool_call_update") },
                        status = legacyStatus,
                    )
                    val tool = (previous?.tool ?: AgentToolCall()).merge(patch)
                    val row = event.copy(kind = "tool.call", text = tool.title ?: previous?.text ?: "Tool call", tool = tool)
                    if (index == null) {
                        key?.let { calls[it] = rows.size }
                        rows.add(row)
                        boundary = true
                    } else {
                        rows[index] = row
                    }
                }
                ConversationView.isMessage(event) -> {
                    val last = rows.lastOrNull()
                    if (!boundary && last != null && last.turnId == event.turnId && (event.kind == "message.delta" && last.kind == event.kind || event.kind == "turn.prompt.delta" && last.kind == "turn.started")) {
                        rows[rows.lastIndex] = last.copy(text = last.text + event.text)
                    } else {
                        if (event.kind == "turn.started") finish(null, "unknown")
                        rows.add(if (event.kind == "turn.prompt.delta") event.copy(kind = "turn.started") else event)
                    }
                    boundary = false
                }
            }
        }
        return rows
    }
}

/** Native fallback for the same bounded tool snapshots used by the React chat. */
internal class ToolCallView(event: AgentEvent) : JPanel(BorderLayout(0, 4)) {
    private val details = FlowUi.note("").apply {
        isVisible = false
        accessibleContext.accessibleName = "Tool input and result"
    }
    private val toggle = JButton().apply {
        putClientProperty("html.disable", true)
        horizontalAlignment = JButton.LEFT
        isContentAreaFilled = false
        addActionListener {
            details.isVisible = !details.isVisible
            revalidate()
        }
    }
    init {
        isOpaque = false
        add(toggle, BorderLayout.NORTH)
        add(details, BorderLayout.CENTER)
        update(event)
    }
    fun update(event: AgentEvent) {
        val tool = requireNotNull(event.tool)
        val status = when (tool.status) {
            "completed" -> "Done"
            "failed" -> "Failed"
            "in_progress" -> "Running"
            "pending" -> "Waiting"
            "interrupted" -> "Interrupted"
            else -> "Result unavailable"
        }
        toggle.text = "${event.text} · $status"
        toggle.icon = if (tool.status == "failed") AllIcons.General.Error else AllIcons.Actions.Execute
        toggle.accessibleContext.accessibleName = "${event.text} · $status · expand tool details"
        details.text = buildString {
            tool.input?.let { append("Input\n$it\n\n") }
            tool.output?.takeIf(String::isNotBlank)?.let { append("Result\n$it\n\n") }
            tool.locations?.forEach { append("${it.path}${it.line?.let { line -> ":$line" }.orEmpty()}\n") }
            tool.diffs?.forEach { append("\nAgent-reported diff: ${it.path}\nBefore:\n${it.before}\nAfter:\n${it.after}\n") }
            if (tool.truncated) append("\nSome tool details were omitted or truncated.\n")
            if (isEmpty()) append("No additional tool details were recorded.")
        }
    }
}
