package dev.flow.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
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
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JPanel

/** Native controls only; session history, approvals and lifecycle stay in Rust. */
internal class AgentSessionPanel(private val controller: EngineController) : JBPanel<AgentSessionPanel>(BorderLayout()) {
    private val settings = ApplicationManager.getApplication().getService(CodexSettings::class.java)
    private val executable = JBTextField(settings.state.executable)
    private val modelField = JBTextField(settings.state.model)
    private val authMethods = JComboBox<String>()
    private val authenticate = JButton("Authenticate and retry")
    private val compatibility = JBTextArea(3, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
    }
    private val count = JBLabel("0 recorded operations")
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
    private val sourceLabel = JBLabel("New conversation from a clean commit")
    private val stopped = JCheckBox("I verified all previous session writers have stopped")
    private val clean = JButton("Use a clean commit instead")
    private var sourceSessionId: String? = null
    private var current = EngineView()
    private var lastBaselineRevision = 0L
    private var renderedPermission: AgentPermission? = null

    init {
        listOf(
            executable to "ACP adapter executable",
            modelField to "Model override",
            arguments to "Advanced adapter arguments",
            auth to "Authentication method",
            commit to "Clean baseline commit",
            prompt to "Task prompt",
        ).forEach { (field, name) -> field.accessibleContext.accessibleName = name }
        val fields = JPanel(GridLayout(0, 1)).apply {
            add(JBLabel("ACP adapter executable (absolute path)"))
            add(executable)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(
                        JButton("Browse…").apply {
                            addActionListener {
                                FileChooser.chooseFile(FileChooserDescriptor(true, false, false, false, false, false), null, null)?.let { executable.text = it.path }
                            }
                        },
                    )
                    add(
                        JButton("Save adapter and model").apply {
                            addActionListener {
                                val path = runCatching { java.nio.file.Path.of(executable.text.trim()) }.getOrNull()
                                if (path == null || !path.isAbsolute || executable.text.length > 4096) {
                                    controller.showActionError("Choose an absolute adapter executable path using Browse.")
                                } else if (modelField.text.length > 256 || modelField.text.any { it.isISOControl() }) {
                                    controller.showActionError("Enter a model name of at most 256 characters without control characters.")
                                } else {
                                    settings.save(path.toString(), modelField.text.trim())
                                    controller.showActionError("Adapter and model saved on this machine. Advanced arguments and authentication are not saved.")
                                }
                            }
                        },
                    )
                },
            )
            add(JBLabel("Model override (optional; must be supported by your login)"))
            add(modelField)
            add(JBLabel("Advanced adapter arguments (JSON array; model field takes precedence)"))
            add(arguments)
            add(JBLabel("Authentication method (optional; existing login by default)"))
            add(auth)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    add(authMethods)
                    add(authenticate)
                },
            )
            add(JBLabel("Clean baseline commit — local edits and unsaved buffers excluded"))
            add(commit)
            add(baseline)
            add(sourceLabel)
            add(stopped)
            add(clean)
        }
        val top = JPanel(BorderLayout()).apply {
            add(
                JPanel(GridLayout(0, 1)).apply {
                    add(status)
                    add(count)
                },
                BorderLayout.NORTH,
            )
            add(JBScrollPane(fields).apply { preferredSize = java.awt.Dimension(300, 320) }, BorderLayout.CENTER)
            add(JBScrollPane(compatibility), BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(activity), BorderLayout.CENTER)
        val buttons = JPanel(GridLayout(2, 2, 4, 4)).apply {
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
        clean.addActionListener { newConversation(null) }
        newConversation(null)
        authenticate.addActionListener {
            val method = authMethods.selectedItem as? String
            current.agent.id?.let { id ->
                if (method != null) controller.agentRequest("sessions.authenticate", AgentProtocol.command(id).apply { addProperty("methodId", method) })
            }
        }
        baseline.addActionListener { controller.agentRequest("sessions.baseline") }
        start.addActionListener {
            try {
                controller.agentRequest("sessions.start", AgentProtocol.start(executable.text.trim(), arguments.text, commit.text.trim(), prompt.text, auth.text.trim(), sourceSessionId, stopped.isSelected, modelField.text.trim()))
            } catch (invalid: IllegalArgumentException) {
                controller.showActionError(invalid.message ?: "Invalid session settings.")
            }
        }
        send.addActionListener { current.agent.id?.let { controller.agentRequest("sessions.prompt", AgentProtocol.command(it, prompt.text)) } }
        cancel.addActionListener { current.agent.id?.let { controller.agentRequest("sessions.cancel", AgentProtocol.command(it)) } }
        close.addActionListener { current.agent.id?.let { controller.agentRequest("sessions.close", AgentProtocol.command(it)) } }
        update(current)
    }

    fun newConversation(source: String?) {
        sourceSessionId = source
        prompt.text = ""
        stopped.isSelected = false
        stopped.isVisible = source != null
        clean.isVisible = source != null
        commit.isEnabled = source == null
        sourceLabel.text = source?.let { "Retained workspace: $it (new conversation)" } ?: "New conversation from a clean commit"
        sourceLabel.toolTipText = sourceLabel.text
        update(current)
    }

    fun update(view: EngineView) {
        current = view
        if (!view.connected) stopped.isSelected = false
        val agent = view.agent
        status.text = "${agent.status.ifBlank { "No active session" }}${agent.workspace?.let { " — $it" }.orEmpty()}"
        count.text = "${agent.recordedOperations} recorded operations in this conversation"
        compatibility.text = agent.compatibility.ifBlank { "Text tasks and one-time permissions. Flow checks the adapter's reported version when connecting. Native login stays with Codex; upstream resume, attachments and MCP context are not available yet." }
        val methods = (0 until authMethods.itemCount).map { authMethods.getItemAt(it) }
        if (methods != agent.authMethods) {
            authMethods.removeAllItems()
            agent.authMethods.forEach { authMethods.addItem(it) }
        }
        authenticate.isEnabled = view.connected && agent.status == "authenticationRequired" && agent.authMethods.isNotEmpty()
        authMethods.isEnabled = authenticate.isEnabled
        val text = agent.activity.joinToString("\n\n")
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
        start.isEnabled = connected && !view.baselineLoading && agent.status !in listOf("starting", "authenticating", "authenticationRequired", "running", "ready", "cancelling", "closing")
        send.isEnabled = connected && agent.status == "ready"
        cancel.isEnabled = connected && agent.status == "running"
        close.isEnabled = connected && agent.status in listOf("starting", "authenticating", "authenticationRequired", "running", "ready", "cancelling", "closing")
        baseline.isEnabled = connected && !view.baselineLoading && sourceSessionId == null
        baseline.text = if (view.baselineLoading) "Reading current commit…" else "Use current commit"
        if (renderedPermission == agent.permissions.firstOrNull()) return
        renderedPermission = agent.permissions.firstOrNull()
        permission.removeAll()
        agent.permissions.firstOrNull()?.let { request ->
            val description = JBTextArea("${request.title}\nTool: ${request.tool}", 6, 30).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            permission.add(JBScrollPane(description), BorderLayout.CENTER)
            val choices = JPanel(GridLayout(0, 2, 4, 4))
            fun choose(option: String?) {
                agent.id?.let { controller.agentRequest("permissions.respond", AgentProtocol.permission(it, request.id, option)) }
            }
            request.options.forEach { (id, name) ->
                choices.add(
                    JButton(name).apply {
                        putClientProperty("html.disable", true)
                        putClientProperty("flow.permission.requestId", request.id)
                        putClientProperty("flow.permission.optionId", id)
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
