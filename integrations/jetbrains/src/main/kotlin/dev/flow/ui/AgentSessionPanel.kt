package dev.flow.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JPanel

/** Native controls only; session history, approvals and lifecycle stay in Rust. */
internal class AgentSessionPanel(
    private val controller: EngineController,
    private val project: com.intellij.openapi.project.Project? = null,
    private val openSettings: (() -> Unit)? = null,
    private val openHistory: (() -> Unit)? = null,
    private val openChanges: (() -> Unit)? = null,
) : JBPanel<AgentSessionPanel>(BorderLayout()),
    com.intellij.openapi.Disposable {
    private val settings = ApplicationManager.getApplication().getService(CodexSettings::class.java)
    private val selection = project?.service<AgentSelection>()
    private val executable = JBTextField(settings.state.executable)
    private val modelField = JBTextField(settings.state.model)
    private var connectedProvider: String? = null
    private val profile = JComboBox(arrayOf("automatic", "codex", "claude")).apply {
        selectedItem = selection?.profile ?: settings.state.adapterProfile
        accessibleContext.accessibleName = "Agent profile"
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, selected, focus)
                val displayed = if (index < 0) connectedProvider ?: value else value
                icon = ProviderIcons.forAgent(displayed?.toString().orEmpty())
                text = when (displayed) {
                    "codex" -> "Codex"
                    "claude" -> "Claude"
                    else -> "Auto-detect"
                }
                return this
            }
        }
    }
    private var overlay: com.google.gson.JsonObject? = null
    private var provenance: Pair<Long, String>? = null
    private val authMethods = JComboBox<String>()
    private val authenticate = JButton("Authenticate and retry")
    private val compatibility = JBTextArea(3, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
    }
    private val arguments = JBTextField("[]")
    private val auth = JBTextField()
    private val commit = JBTextField()
    private val prompt = JBTextArea(4, 30).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val promptRevision = TextRevision(prompt)
    private lateinit var promptContext: ComposerContext
    private var submitting: kotlinx.coroutines.Job? = null
    private val conversation = ConversationView()
    internal val configuration: JPanel
    private val configurationFeedback = FlowUi.note("").apply { isVisible = false }
    private val status = JBLabel("No active session")
    private val error = JBTextArea(3, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
    }
    private val permission = JPanel(BorderLayout())
    private val permissionScroll = FlowUi.scroll(permission, 150)
    private val start = JButton("Start session")
    private val send = JButton("Send")
    private val cancel = JButton("Stop")
    private val close = JButton("Close session")
    private val baseline = JButton("Use current commit")
    private val selectOverlay = JButton("Select local baseline overlay…")
    private val sourceLabel = JBLabel("New conversation from a clean commit")
    private val modelChoice = JButton()
    private val modeChoice = JButton()
    private val connect = JButton("Connect")
    private val detection = FlowUi.note("Detect an installed agent or choose an ACP executable. Native credentials stay with the agent.")
    private var localAgents = emptyList<dev.flow.bridge.LocalAgent>()
    private var detectionJob: kotlinx.coroutines.Job? = null
    private var disposed = false
    private var pendingStart: Boolean? = null
    private var openConfigurationWhenReady = false
    private val contextDetails = FlowUi.note("")
    private val stopped = JCheckBox("I verified previous writers have stopped")
    private val clean = JButton("Use a clean commit instead")
    private var sourceSessionId: String? = null
    private var current = EngineView()
    private var lastBaselineRevision = 0L
    private var renderedPermission: AgentPermission? = null
    private var renderedAuthentication = false

    init {
        listOf(
            executable to "ACP adapter executable",
            modelField to "Model override",
            arguments to "Advanced adapter arguments",
            auth to "Authentication method",
            commit to "Clean baseline commit",
            prompt to "Task prompt",
        ).forEach { (field, name) -> field.accessibleContext.accessibleName = name }
        border = com.intellij.util.ui.JBUI.Borders.empty(8)
        val detect = JButton("Detect installed agents").apply { addActionListener { detectAgents(force = true) } }
        val browse = JButton("Browse…").apply {
            addActionListener { FileChooser.chooseFile(FileChooserDescriptor(true, false, false, false, false, false), project, null)?.let { executable.text = it.path } }
        }
        val save = JButton("Save adapter and model").apply {
            addActionListener {
                val path = runCatching { java.nio.file.Path.of(executable.text.trim()) }.getOrNull()
                if (path == null || !path.isAbsolute || executable.text.length > 4096) {
                    controller.showActionError("Choose an absolute adapter executable path using Browse.")
                } else if (modelField.text.length > 256 || modelField.text.any { it.isISOControl() }) {
                    controller.showActionError("Enter a model name of at most 256 characters without control characters.")
                } else {
                    settings.save(if (localAgents.any { it.executable == path.toString() && com.google.gson.Gson().toJson(it.arguments) == arguments.text }) "" else path.toString(), modelField.text.trim(), profile.selectedItem as String)
                    controller.showActionError("Adapter and model saved on this machine.")
                }
            }
        }
        configuration = FlowUi.column(
            FlowUi.heading("Agent connection", "Connect using your existing Codex or Claude Code login. Flow never copies credentials."),
            detection,
            FlowUi.actions(detect),
            FlowUi.field("Adapter executable", executable, "Choose an absolute path to the executable."),
            FlowUi.actions(browse, save),
            configurationFeedback,
            FlowUi.field("Legacy model override", modelField, "Optional Codex CLI override for older adapters. Use the model picker after Connect for current adapters."),
            Disclosure(
                "Advanced connection options",
                FlowUi.column(
                    FlowUi.field("Adapter arguments", arguments, "JSON array, for example []. The model override takes precedence."),
                    FlowUi.field("Authentication method", auth, "Leave empty to use your existing login."),
                    FlowUi.scroll(compatibility, 110),
                ),
            ),
        )
        selectOverlay.addActionListener {
            project?.let {
                OverlayImport.choose(it) { selected, head ->
                    commit.text = head
                    overlay = selected
                    sourceLabel.text = "Local baseline · ${selected.getAsJsonArray("files").size()} selected files"
                    updateWorkspaceTitle()
                }
            }
        }
        val clearOverlay = JButton("Use clean baseline").apply {
            addActionListener {
                overlay = null
                sourceLabel.text = "New conversation from a clean commit"
                updateWorkspaceTitle()
            }
        }
        val enhance = JButton("Enhance…").apply {
            isEnabled = project != null
            toolTipText = "Review a context-aware prompt draft before using it"
            addActionListener { project?.let { PromptEnhancement.open(it, prompt, promptRevision) { hash -> provenance = promptRevision.version to hash } } }
        }
        val configure = FlowUi.iconButton(JButton("Agent settings"), com.intellij.icons.AllIcons.General.Settings, "Agent settings").apply {
            isVisible = openSettings != null
            addActionListener { openSettings?.invoke() }
        }
        FlowUi.iconButton(enhance, com.intellij.icons.AllIcons.Actions.Edit, "Enhance prompt with reviewed project context")
        promptContext = ComposerContext(
            project,
            prompt,
            controller,
            controller.scope,
            workspace = FlowUi.scroll(
                FlowUi.column(
                    sourceLabel,
                    contextDetails,
                    FlowUi.field("Starting commit", commit, "Local edits are excluded unless you select a local baseline."),
                    FlowUi.actions(baseline),
                    FlowUi.actions(selectOverlay, clearOverlay),
                    stopped,
                    clean,
                ),
                180,
            ),
        )
        commit.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) = updateWorkspaceTitle()
        })
        val navigation = listOfNotNull(
            openHistory?.let { action -> FlowUi.iconButton(JButton("History"), com.intellij.icons.AllIcons.Vcs.History, "Browse session history").apply { addActionListener { action() } } },
            openChanges?.let { action -> FlowUi.iconButton(JButton("Review changes"), com.intellij.icons.AllIcons.Actions.Diff, "Review recorded changes").apply { addActionListener { action() } } },
            FlowUi.iconButton(close, com.intellij.icons.AllIcons.Actions.Close, "Close the agent session; recorded history is retained"),
        )
        val header = JPanel(BorderLayout()).apply {
            border = com.intellij.util.ui.JBUI.Borders.emptyBottom(6)
            add(FlowUi.toolbar(profile, connect, status), BorderLayout.CENTER)
            add(FlowUi.toolbar(*navigation.toTypedArray()), BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(conversation, BorderLayout.CENTER)
        prompt.font = javax.swing.UIManager.getFont("Label.font")
        prompt.emptyText.text = "Ask anything, @reference files…"
        prompt.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "flow.send")
        prompt.actionMap.put(
            "flow.send",
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) {
                    if (send.isEnabled) {
                        send.doClick(0)
                    } else if (start.isEnabled) {
                        start.doClick(0)
                    }
                }
            },
        )
        val shortcut = if (com.intellij.openapi.util.SystemInfo.isMac) "⌘ Enter" else "Ctrl Enter"
        FlowUi.iconButton(start, com.intellij.icons.AllIcons.Actions.Execute, "Start session ($shortcut)")
        FlowUi.iconButton(send, com.intellij.icons.AllIcons.Actions.Execute, "Send ($shortcut)")
        FlowUi.iconButton(cancel, com.intellij.icons.AllIcons.Actions.Suspend, "Stop the current turn")
        modelChoice.apply {
            horizontalTextPosition = javax.swing.SwingConstants.RIGHT
            putClientProperty("html.disable", true)
            accessibleContext.accessibleName = "Agent model and configuration"
            addActionListener { openAgentConfiguration() }
        }
        modeChoice.apply {
            icon = com.intellij.icons.AllIcons.General.ArrowDown
            horizontalTextPosition = javax.swing.SwingConstants.LEFT
            putClientProperty("html.disable", true)
            accessibleContext.accessibleName = "Agent permission mode"
            addActionListener { openAgentConfiguration(modeOnly = true) }
        }
        listOf(modeChoice, modelChoice).forEach {
            it.putClientProperty("JButton.buttonType", "toolBarButton")
            it.margin = com.intellij.util.ui.JBUI.insets(4)
        }
        connect.addActionListener { startSession(connectOnly = true) }
        modelField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) = updateModelChoice()
        })
        updateModelChoice()
        val composer = JPanel(BorderLayout(0, com.intellij.util.ui.JBUI.scale(6))).apply {
            accessibleContext.accessibleName = "Prompt composer"
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.border.LineBorder(com.intellij.ui.JBColor.border(), com.intellij.util.ui.JBUI.scale(1), true),
                com.intellij.util.ui.JBUI.Borders.empty(8),
            )
            add(promptContext, BorderLayout.NORTH)
            add(FlowUi.scroll(prompt, 76), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(FlowUi.toolbar(configure, enhance), BorderLayout.WEST)
                    add(FlowUi.actions(modeChoice, modelChoice), BorderLayout.CENTER)
                    add(FlowUi.toolbar(FlowUi.primary(start), FlowUi.primary(send), cancel), BorderLayout.EAST)
                },
                BorderLayout.SOUTH,
            )
        }
        error.font = javax.swing.UIManager.getFont("Label.font")
        error.rows = 2
        val footer = JPanel(BorderLayout(0, com.intellij.util.ui.JBUI.scale(6))).apply {
            add(FlowUi.column(permissionScroll, error), BorderLayout.NORTH)
            add(composer, BorderLayout.CENTER)
        }
        if (openSettings == null) footer.add(Disclosure("Agent settings", FlowUi.scroll(configuration, 180)), BorderLayout.SOUTH)
        add(footer, BorderLayout.SOUTH)
        profile.addActionListener {
            val selected = profile.selectedItem as String
            val changed = (selection?.profile ?: settings.state.adapterProfile) != selected
            if (selection != null) selection.select(selected) else settings.selectProfile(selected)
            if (changed) {
                executable.text = ""
                arguments.text = "[]"
                modelField.text = ""
            }
            modelField.isEnabled = profile.selectedItem != "claude"
            applyDetected(force = true)
            updateModelChoice()
            compatibility.text = if (profile.selectedItem == "claude") "Choose a released claude-agent-acp executable (or Node executable with the adapter entry point in arguments). Authentication methods are discovered on connection. Model CLI override is unavailable; optional upstream extensions and resume are disabled." else "Choose the Codex ACP executable. Authentication stays with Codex."
        }
        modelField.isEnabled = profile.selectedItem != "claude"
        clean.addActionListener { newConversation(null) }
        newConversation(null)
        authenticate.addActionListener {
            val method = authMethods.selectedItem as? String
            current.agent.id?.let { id ->
                if (method != null) controller.agentRequest("sessions.authenticate", AgentProtocol.command(id).apply { addProperty("methodId", method) })
            }
        }
        baseline.addActionListener { controller.agentRequest("sessions.baseline") }
        start.addActionListener { startSession(connectOnly = false) }
        send.addActionListener { sendPrompt() }
        cancel.addActionListener { current.agent.id?.let { controller.agentRequest("sessions.cancel", AgentProtocol.command(it)) } }
        close.addActionListener { current.agent.id?.let { controller.agentRequest("sessions.close", AgentProtocol.command(it)) } }
        update(current)
        if (project != null && !ApplicationManager.getApplication().isUnitTestMode) detectAgents(force = false)
    }

    private fun startSession(connectOnly: Boolean) {
        try {
            require(connectOnly || prompt.text.isNotBlank()) { "Describe a task before sending references." }
            require(executable.text.isNotBlank()) { "Install Node.js 22+ and Codex or Claude Code, then use Detect installed agents in Settings. You can also choose an ACP executable manually." }
            if (commit.text.isBlank() && sourceSessionId == null) {
                require(connectOnly || prompt.text.isNotBlank()) { "Enter a task before sending." }
                pendingStart = connectOnly
                controller.agentRequest("sessions.baseline")
                return
            }
            val selectedOverlay = overlay
            val prepared = if (connectOnly) null else promptContext.prepare()
            if (selectedOverlay != null && project != null) {
                val paths = selectedOverlay.getAsJsonArray("files").map { it.asJsonObject["path"].asString }.toSet()
                val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                require(documents.unsavedDocuments.none { document -> documents.getFile(document)?.path?.removePrefix("${project.basePath}/") in paths }) { "A selected overlay file has unsaved changes. Review the overlay again and save or exclude it." }
            }
            controller.agentRequest(
                "sessions.start",
                AgentProtocol.start(executable.text.trim(), arguments.text, commit.text.trim(), prepared?.text.orEmpty(), auth.text.trim(), sourceSessionId, stopped.isSelected, if (profile.selectedItem != "claude") modelField.text.trim() else "", connectOnly = connectOnly).apply {
                    addProperty("includeContext", prepared?.includeContext ?: true)
                    addProperty("adapterProfile", (profile.selectedItem as String).takeUnless { it == "automatic" }.orEmpty())
                    if (selectedOverlay != null) add("overlay", selectedOverlay)
                    provenance?.takeIf { it.first == promptRevision.version }?.let { addProperty("promptProvenance", it.second) }
                },
            )
        } catch (invalid: IllegalArgumentException) {
            controller.showActionError(invalid.message ?: "Invalid session settings.")
        } catch (invalid: IllegalStateException) {
            controller.showActionError(invalid.message ?: "Context changed. Review it before sending.")
        }
    }

    private fun sendPrompt() {
        if (submitting?.isActive == true) return
        submitting = controller.scope.launch(kotlinx.coroutines.Dispatchers.EDT) {
            try {
                val session = current.agent.id ?: return@launch
                require(prompt.text.isNotBlank()) { "Describe a task before sending references." }
                val prepared = promptContext.prepare()
                val version = promptRevision.version
                promptContext.validate(prepared)
                check(current.agent.id == session && current.agent.status == "ready" && version == promptRevision.version) { "The session or prompt changed. Send again when ready." }
                controller.agentRequest(
                    "sessions.prompt",
                    AgentProtocol.command(session, prepared.text).apply {
                        addProperty("includeContext", prepared.includeContext)
                        provenance?.takeIf { it.first == version }?.let { addProperty("promptProvenance", it.second) }
                    },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.showActionError(error.message ?: "Could not prepare prompt context.")
            }
        }
    }

    private fun updateModelChoice() {
        modelChoice.icon = ProviderIcons.forAgent(provider()) ?: com.intellij.icons.AllIcons.General.ArrowDown
        val option = current.agent.configOptions.firstOrNull { it.category == "model" || it.id == "model" }
        val unavailable = current.agent.status == "ready" && option?.options.isNullOrEmpty()
        val model = if (unavailable) "Models unavailable" else option?.currentLabel ?: "Choose model"
        modelChoice.text = model.take(20) + if (model.length > 20) "…" else ""
        modelChoice.toolTipText = if (unavailable) "This adapter has not supplied a model catalogue. Open for connection guidance." else "Model: $model · Model, speed, effort and agent settings"
        modelChoice.isEnabled = current.connected && current.agentSupported && (current.agent.status == "ready" || start.isEnabled)
        val mode = current.agent.configOptions.firstOrNull { it.category == "mode" || it.id == "mode" }
        modeChoice.text = mode?.currentLabel?.let { it.take(18) + if (it.length > 18) "…" else "" }.orEmpty()
        modeChoice.isVisible = mode != null
        modeChoice.isEnabled = current.connected && current.agent.status == "ready"
        modeChoice.toolTipText = "Agent permissions: ${mode?.description.orEmpty()}"
    }

    private fun openAgentConfiguration(modeOnly: Boolean = false) {
        if (current.agent.status != "ready") {
            openConfigurationWhenReady = true
            startSession(connectOnly = true)
            return
        }
        AgentConfigurationPopup.show(if (modeOnly) modeChoice else modelChoice, current.agent.configOptions, provider(), modeOnly, openSettings) { option, value ->
            current.agent.id?.let { controller.agentRequest("sessions.configure", AgentProtocol.configure(it, option, value)) }
        }
    }

    private fun provider(): String = current.agent.agentName.takeUnless { profile.isEnabled }.orEmpty().ifBlank { profile.selectedItem?.toString().orEmpty() }

    private fun detectAgents(force: Boolean) {
        val scope = project?.service<dev.flow.bridge.EngineService>()?.scope ?: return
        detection.text = "Detecting installed agents…"
        detectionJob?.cancel()
        detectionJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = runCatching { dev.flow.bridge.LocalAgents.discover() }.getOrDefault(emptyList())
            withContext(kotlinx.coroutines.Dispatchers.EDT) {
                if (!disposed) {
                    localAgents = found
                    applyDetected(force)
                    update(current)
                }
            }
        }
    }

    private fun applyDetected(force: Boolean) {
        val found = localAgents.firstOrNull { profile.selectedItem == "automatic" || it.profile == profile.selectedItem }
        detection.text = found?.description ?: "Install Codex or Claude Code and Node.js 22+, then detect again. You can also choose an ACP adapter executable manually."
        connect.toolTipText = detection.text
        if (found != null && (force || executable.text.isBlank())) {
            executable.text = found.executable
            arguments.text = com.google.gson.Gson().toJson(found.arguments)
        }
    }

    override fun dispose() {
        disposed = true
        detectionJob?.cancel()
        submitting?.cancel()
        promptContext.dispose()
    }

    private fun updateWorkspaceTitle() {
        promptContext.setWorkspaceTitle(
            when {
                sourceSessionId != null -> "Workspace · retained"
                overlay != null -> "Workspace · local baseline"
                commit.text.isNotBlank() -> "Workspace · ${commit.text.take(8)}"
                else -> "Workspace · choose baseline"
            },
        )
    }

    fun newConversation(source: String?) {
        pendingStart = null
        openConfigurationWhenReady = false
        overlay = null
        provenance = null
        sourceSessionId = source
        prompt.text = ""
        promptContext.clear()
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
        promptContext.update(view)
        if (!view.connected) {
            stopped.isSelected = false
            pendingStart = null
            openConfigurationWhenReady = false
        }
        val agent = view.agent
        connectedProvider = if (agent.id != null && agent.status !in listOf("closed", "failed")) AgentSelection.provider(agent.agentName) else null
        profile.repaint()
        status.text = when (agent.status) {
            "starting" -> "Connecting…"
            "running" -> "Working…"
            "ready" -> "Ready for your next message"
            "configuring" -> "Updating…"
            "failed" -> "Session failed"
            "authenticationRequired" -> "Sign-in required"
            "cancelling" -> "Stopping…"
            "closed" -> "Session closed"
            "No active session", "" -> "Ready for a new task"
            else -> agent.status.replaceFirstChar { it.uppercase() }
        }
        status.toolTipText = agent.workspace
        status.isVisible = !view.connected || agent.status !in listOf("ready", "No active session", "", "closed")
        if (!view.connected) status.text = "Engine offline"
        profile.toolTipText = "${status.text} · ${agent.recordedOperations} recorded operations${agent.workspace?.let { " · $it" }.orEmpty()}"
        contextDetails.text = view.contextStatus
        compatibility.text = (listOf(agent.compatibility) + agent.events.filter { ConversationView.isConnectionDetail(it) }.map { it.text }).filter(String::isNotBlank).distinct().joinToString("\n\n").ifBlank { "Native login stays with the selected adapter. Read-only MCP context is attached automatically; upstream resume and attachments are unavailable." }
        val methods = (0 until authMethods.itemCount).map { authMethods.getItemAt(it) }
        if (methods != agent.authMethods) {
            authMethods.removeAllItems()
            agent.authMethods.forEach { authMethods.addItem(it) }
        }
        authenticate.isEnabled = view.connected && agent.status == "authenticationRequired" && agent.authMethods.isNotEmpty()
        authMethods.isEnabled = authenticate.isEnabled
        conversation.update(agent.events.ifEmpty { agent.activity.map { dev.flow.bridge.AgentEvent("message.delta", it) } })
        error.text = listOf(view.actionError, agent.configurationError).filter(String::isNotBlank).joinToString("\n")
        error.isVisible = error.text.isNotBlank()
        configurationFeedback.text = view.actionError
        configurationFeedback.isVisible = view.actionError.isNotBlank()
        if (view.baseline.isNotBlank() && view.baselineRevision != lastBaselineRevision) {
            commit.text = view.baseline
        }
        val baselineArrived = view.baselineRevision != lastBaselineRevision
        lastBaselineRevision = view.baselineRevision
        if (baselineArrived) {
            pendingStart?.let { pending ->
                pendingStart = null
                startSession(pending)
            }
        }
        if (view.actionError.isNotBlank()) pendingStart = null
        val connected = view.connected && view.agentSupported
        val active = agent.status in listOf("starting", "authenticating", "authenticationRequired", "running", "ready", "configuring", "cancelling", "closing")
        start.isEnabled = connected && !view.baselineLoading && !active
        send.isEnabled = connected && agent.status == "ready"
        cancel.isEnabled = connected && agent.status == "running"
        close.isEnabled = connected && active
        profile.isEnabled = !active
        modelField.isEnabled = !active && profile.selectedItem != "claude"
        updateModelChoice()
        baseline.isEnabled = connected && !view.baselineLoading && sourceSessionId == null
        selectOverlay.isEnabled = project != null && connected && sourceSessionId == null
        baseline.text = if (view.baselineLoading) "Reading current commit…" else "Use current commit"
        connect.isVisible = connected && !active
        connect.isEnabled = start.isEnabled
        start.isVisible = !active
        cancel.isVisible = agent.status in listOf("running", "cancelling")
        send.isVisible = active && !cancel.isVisible
        close.isVisible = active
        updateWorkspaceTitle()
        permissionScroll.isVisible = agent.permissions.isNotEmpty() || authenticate.isEnabled
        if (openConfigurationWhenReady && agent.status == "ready") {
            openConfigurationWhenReady = false
            javax.swing.SwingUtilities.invokeLater { if (!disposed && modelChoice.isShowing) openAgentConfiguration() }
        }
        if (agent.status in listOf("failed", "closed", "interrupted")) openConfigurationWhenReady = false
        if (renderedPermission == agent.permissions.firstOrNull() && renderedAuthentication == authenticate.isEnabled) return
        renderedAuthentication = authenticate.isEnabled
        renderedPermission = agent.permissions.firstOrNull()
        permission.removeAll()
        agent.permissions.firstOrNull()?.let { request ->
            val description = JBTextArea("${request.title}\nTool: ${request.tool}", 3, 0).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            permission.border = com.intellij.util.ui.JBUI.Borders.compound(com.intellij.util.ui.JBUI.Borders.customLine(com.intellij.ui.JBColor.border()), com.intellij.util.ui.JBUI.Borders.empty(8))
            permission.add(JBLabel("Approval required").apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.NORTH)
            permission.add(FlowUi.scroll(description, 64), BorderLayout.CENTER)
            val choices = FlowUi.actions()
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
        if (agent.permissions.isEmpty() && authenticate.isEnabled) {
            permission.add(FlowUi.column(FlowUi.note("Sign in with your adapter to continue."), FlowUi.actions(authMethods, authenticate)), BorderLayout.CENTER)
        }
        permission.revalidate()
        permission.repaint()
    }
}
