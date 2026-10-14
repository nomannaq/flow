package dev.flow.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import dev.flow.bridge.AgentPermission
import dev.flow.bridge.AgentProtocol
import dev.flow.bridge.EngineController
import dev.flow.bridge.EngineView
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel

/** Native controls only; session history, approvals and lifecycle stay in Rust. */
internal class AgentSessionPanel(private val controller: EngineController) : JBPanel<AgentSessionPanel>(BorderLayout()) {
    private val executable = JBTextField()
    private val arguments = JBTextField("[]")
    private val auth = JBTextField()
    private val commit = JBTextField()
    private val prompt = JBTextArea(4, 30).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val activity = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val status = JBLabel("No active session")
    private val error = JBTextArea(3, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
    }
    private val permission = JPanel(BorderLayout())
    private val start = JButton("Start Codex session")
    private val send = JButton("Send follow-up")
    private val cancel = JButton("Cancel turn")
    private val close = JButton("Close session")
    private val baseline = JButton("Use current commit")
    private var current = EngineView()
    private var lastBaselineRevision = 0L
    private var renderedPermission: AgentPermission? = null

    init {
        val fields = JPanel(GridLayout(0, 1)).apply {
            add(JBLabel("ACP adapter executable (absolute path)"))
            add(executable)
            add(JBLabel("Adapter arguments (JSON array)"))
            add(arguments)
            add(JBLabel("Authentication method (optional; existing login by default)"))
            add(auth)
            add(JBLabel("Clean baseline commit — local edits and unsaved buffers excluded"))
            add(commit)
            add(baseline)
        }
        val top = JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(fields, BorderLayout.CENTER)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(activity), BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(start)
            add(send)
            add(cancel)
            add(close)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(permission, BorderLayout.NORTH)
            add(JBScrollPane(prompt), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(buttons, BorderLayout.CENTER)
                    add(JBScrollPane(error), BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH,
            )
        }
        add(bottom, BorderLayout.SOUTH)
        baseline.addActionListener { controller.agentRequest("sessions.baseline") }
        start.addActionListener {
            try {
                controller.agentRequest("sessions.start", AgentProtocol.start(executable.text.trim(), arguments.text, commit.text.trim(), prompt.text, auth.text.trim()))
            } catch (invalid: IllegalArgumentException) {
                controller.showActionError(invalid.message ?: "Invalid session settings.")
            }
        }
        send.addActionListener { current.agent.id?.let { controller.agentRequest("sessions.prompt", AgentProtocol.command(it, prompt.text)) } }
        cancel.addActionListener { current.agent.id?.let { controller.agentRequest("sessions.cancel", AgentProtocol.command(it)) } }
        close.addActionListener { current.agent.id?.let { controller.agentRequest("sessions.close", AgentProtocol.command(it)) } }
        update(current)
    }

    fun update(view: EngineView) {
        current = view
        val agent = view.agent
        status.text = "${agent.status.ifBlank { "No active session" }}${agent.workspace?.let { " — $it" }.orEmpty()}"
        val text = agent.activity.joinToString("\n")
        if (activity.text != text) {
            activity.text = text
            activity.caretPosition = activity.document.length
        }
        error.text = view.actionError
        if (view.baseline.isNotBlank() && view.baselineRevision != lastBaselineRevision) {
            commit.text = view.baseline
        }
        lastBaselineRevision = view.baselineRevision
        val connected = view.connected && view.agentSupported
        start.isEnabled = connected && !view.baselineLoading && agent.status !in listOf("starting", "running", "ready", "cancelling")
        send.isEnabled = connected && agent.status == "ready"
        cancel.isEnabled = connected && agent.status == "running"
        close.isEnabled = connected && agent.status in listOf("starting", "running", "ready", "cancelling")
        baseline.isEnabled = connected && !view.baselineLoading
        baseline.text = if (view.baselineLoading) "Reading current commit…" else "Use current commit"
        if (renderedPermission == agent.permissions.firstOrNull()) return
        renderedPermission = agent.permissions.firstOrNull()
        permission.removeAll()
        agent.permissions.firstOrNull()?.let { request ->
            val description = JBTextArea("${request.title}\nTool: ${request.tool}").apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            permission.add(description, BorderLayout.CENTER)
            val choices = JPanel(FlowLayout(FlowLayout.LEFT))
            fun choose(option: String?) {
                agent.id?.let { controller.agentRequest("permissions.respond", AgentProtocol.permission(it, request.id, option)) }
            }
            request.options.forEach { (id, name) ->
                choices.add(
                    JButton(name).apply {
                        putClientProperty("html.disable", true)
                        addActionListener { choose(id) }
                    },
                )
            }
            choices.add(JButton("Deny").apply { addActionListener { choose(null) } })
            permission.add(choices, BorderLayout.SOUTH)
        }
        permission.revalidate()
        permission.repaint()
    }
}
