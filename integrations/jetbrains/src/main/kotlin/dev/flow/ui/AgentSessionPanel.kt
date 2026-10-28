package dev.flow.ui

import com.intellij.icons.AllIcons
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

/** Shared session workflows with React chat and a native fallback; authoritative state stays in Rust. */
internal class AgentSessionPanel(
    private val controller: EngineController,
    private val project: com.intellij.openapi.project.Project? = null,
    private val openSettings: (() -> Unit)? = null,
    private val openHistory: (() -> Unit)? = null,
    private val openChanges: (() -> Unit)? = null,
) : JBPanel<AgentSessionPanel>(BorderLayout()),
    com.intellij.openapi.Disposable {
    private val gson = com.google.gson.Gson()
    private val settings = ApplicationManager.getApplication().getService(CodexSettings::class.java)
    private val selection = project?.service<AgentSelection>()
    private val executable = JBTextField(settings.state.executable)
    private val modelField = JBTextField(settings.state.model)
    private var connectedProvider: String? = null
    private val profile = JComboBox(arrayOf("automatic", "codex", "claude", "cursor")).apply {
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
                    "cursor" -> "Cursor"
                    else -> "Auto-detect"
                }
                return this
            }
        }
    }
    private var overlay: com.google.gson.JsonObject? = null
    private var provenance: Pair<Long, String>? = null
    private val authMethods = JComboBox<String>()
    private val authenticate = JButton("Authenticate and retry", AllIcons.Actions.Refresh)
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
    private val images = PromptAttachments(project, controller)
    private val promptQueue = PromptQueuePanel(controller)
    private var rewindTurnId: String? = null
    private var pendingImageSend: Pair<Long, Long>? = null
    private var pendingRewind: Pair<String, dev.flow.bridge.AgentEvent>? = null
    private var queuedAttempt: Pair<Triple<Long, Long, Long>, com.google.gson.JsonObject>? = null
    private lateinit var promptContext: ComposerContext
    private var submitting: kotlinx.coroutines.Job? = null
    private val chatChanges = ChatChangesPanel(project, controller, openChanges)
    private val completions = ComposerCompletions(project)
    private val promptHistory = project?.service<PromptHistory>() ?: PromptHistory()
    private var progressKey = emptyList<Any?>()
    private var webProgress = com.google.gson.JsonObject()
    private val conversation = ConversationView(if (ApplicationManager.getApplication().isUnitTestMode) null else controller.scope, quote = { text ->
        if (prompt.text.isBlank() || com.intellij.openapi.ui.Messages.showYesNoDialog(project, "Replace the current draft with this quote?", "Quote message", null) == com.intellij.openapi.ui.Messages.YES) {
            prompt.text = text.lineSequence().joinToString("\n") { "> $it" } + "\n\n"
            prompt.caretPosition = prompt.text.length
            prompt.requestFocusInWindow()
        }
    }, project = project, controller = controller, rewind = { source, event -> requestRewind(source, event) }, changes = chatChanges, draftTask = { text ->
        if (prompt.text.isBlank()) {
            prompt.text = text
            prompt.requestFocusInWindow()
        }
    })
    private val commandsMenu = javax.swing.JMenu("Agent commands")
    internal val configuration: JPanel
    private val configurationFeedback = FlowUi.note("").apply { isVisible = false }
    private var pendingNewConversation = false
    private var newDraft = true
    private var displayedSession: String? = null
    internal var conversationTitle = "New conversation"
        private set
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
    private val enqueue = JButton("Queue prompt")
    private val attachFiles = JButton("Attach files")
    private val composerStatus = FlowUi.note("").apply {
        accessibleContext.accessibleName = "Agent progress"
        isVisible = false
    }
    private val close = javax.swing.JMenuItem("Close session", AllIcons.Actions.Exit)
    internal lateinit var composerMenu: javax.swing.JPopupMenu
        private set
    private val baseline = JButton("Use current commit", AllIcons.Actions.Commit)
    private val selectOverlay = JButton("Select local baseline overlay…", AllIcons.Actions.ListChanges)
    private val sessionDiagnostics = FlowUi.note("")
    private val sourceLabel = JBLabel("New conversation from a clean commit")
    private val modelChoice = JButton()
    private val modeChoice = JButton()
    private val connect = JButton("Connect", AllIcons.Actions.Execute)
    private val detection = FlowUi.note("Detect an installed agent or choose an ACP executable. Native credentials stay with the agent.")
    private var localAgents = emptyList<dev.flow.bridge.LocalAgent>()
    private var resolvingAdapter = false
    private var composingText = false
    private var detectionJob: kotlinx.coroutines.Job? = null
    private var disposed = false
    private data class StartIntent(val connectOnly: Boolean, val prompt: Long, val attachments: Long, val context: Long, val profile: String)
    private var pendingStart: StartIntent? = null
    private var pendingDetectionStart: StartIntent? = null
    private var openConfigurationWhenReady = false
    private val contextDetails = FlowUi.note("")
    private val stopped = JCheckBox("I verified previous writers have stopped")
    private val clean = JButton("Use a clean commit instead", AllIcons.Actions.Commit)
    private var sourceSessionId: String? = null
    private var current = EngineView()
    private var lastBaselineRevision = 0L
    private var renderedPermission: AgentPermission? = null
    private var renderedAuthentication = false
    private var activityTurn: Pair<String?, String?>? = null
    private var activityStartedAt: Long? = null
    private var enhancementState = ""
    private val enhancementReview = project?.let {
        PromptEnhancementReview(it, controller.scope, { profile.selectedItem.toString() }, { current.agent }, changed = { value ->
            if (!disposed) {
                enhancementState = when (value?.get("status")?.asString) {
                    null -> ""
                    "preparing", "generating" -> value["status"].asString
                    else -> "reviewing"
                }
                react?.update()
            }
        })
    }
    private var react: ReactChatPanel? = null
    private var webDraftVersion = 0L
    private var webMessageEvents = emptyList<dev.flow.bridge.AgentEvent>()
    private var webMessages: com.google.gson.JsonElement = com.google.gson.JsonArray()
    private var webConfigOptions = emptyList<dev.flow.bridge.AgentConfigOption>()
    private var webConfiguration: com.google.gson.JsonElement = com.google.gson.JsonArray()
    private var nativeFooter: JPanel? = null
    private val webRefresh = javax.swing.Timer(150) { if (isShowing) react?.update() }
    internal val usesReact get() = react != null

    init {
        listOf(
            executable to "ACP adapter executable",
            modelField to "Model override",
            arguments to "Advanced adapter arguments",
            auth to "Authentication method",
            commit to "Clean baseline commit",
            prompt to "Task prompt",
        ).forEach { (field, name) -> field.accessibleContext.accessibleName = name }
        background = FlowUi.background
        border = com.intellij.util.ui.JBUI.Borders.empty(4, 6, 6, 6)
        val detect = JButton("Detect installed agents", AllIcons.Actions.Search).apply { addActionListener { detectAgents(force = true) } }
        val browse = JButton("Browse…", AllIcons.Actions.MenuOpen).apply {
            addActionListener { FileChooser.chooseFile(FileChooserDescriptor(true, false, false, false, false, false), project, null)?.let { executable.text = it.path } }
        }
        val save = JButton("Save adapter and model", AllIcons.Actions.MenuSaveall).apply {
            addActionListener {
                val path = runCatching { java.nio.file.Path.of(executable.text.trim()) }.getOrNull()
                if (path == null || !path.isAbsolute || executable.text.length > 4096) {
                    controller.showActionError("Choose an absolute adapter executable path using Browse.")
                } else if (modelField.text.length > 256 || modelField.text.any { it.isISOControl() }) {
                    controller.showActionError("Enter a model name of at most 256 characters without control characters.")
                } else {
                    settings.save(if (localAgents.any { it.executable == path.toString() && gson.toJson(it.arguments) == arguments.text }) "" else path.toString(), modelField.text.trim(), profile.selectedItem as String)
                    controller.showActionError("Adapter and model saved on this machine.")
                }
            }
        }
        configuration = FlowUi.column(
            FlowUi.heading("Your coding agent", "Use your existing Claude, Codex or Cursor login."),
            detection,
            FlowUi.actions(detect, connect),
            configurationFeedback,
            Disclosure(
                "Advanced connection settings",
                FlowUi.column(
                    FlowUi.field("Adapter executable", executable, "Choose an absolute path to the executable."),
                    FlowUi.actions(browse),
                    FlowUi.field("Legacy model override", modelField, "For older Codex adapters. Current adapters supply the model list."),
                    FlowUi.field("Adapter arguments", arguments, "JSON array, for example []. The model override takes precedence."),
                    FlowUi.field("Authentication method", auth, "Leave empty to use your existing login."),
                    FlowUi.actions(save),
                ),
            ),
            Disclosure("Connection details", FlowUi.column(sessionDiagnostics, FlowUi.scroll(compatibility, 110))),
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
        val clearOverlay = JButton("Use clean baseline", AllIcons.Actions.Commit).apply {
            addActionListener {
                overlay = null
                sourceLabel.text = "New conversation from a clean commit"
                updateWorkspaceTitle()
            }
        }
        val enhance = JButton("Enhance…").apply {
            isEnabled = project != null
            toolTipText = "Review a context-aware prompt draft before using it"
            addActionListener { enhancePrompt() }
        }
        FlowUi.iconButton(enhance, AllIcons.Toolwindows.ToolWindowAskAI, "Enhance prompt with reviewed project context")
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
        promptContext.compactControls()
        commit.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) = updateWorkspaceTitle()
        })
        add(conversation, BorderLayout.CENTER)
        prompt.font = javax.swing.UIManager.getFont("Label.font")
        prompt.emptyText.text = "Ask a question or describe a change…"
        prompt.toolTipText = "Enter to send · Shift+Enter for a new line · @path to reference a file"
        val newline = prompt.inputMap.get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0))
        prompt.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK), newline)
        prompt.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "flow.send")
        prompt.addInputMethodListener(object : java.awt.event.InputMethodListener {
            override fun inputMethodTextChanged(event: java.awt.event.InputMethodEvent) {
                composingText = event.text?.let { it.endIndex - it.beginIndex > event.committedCharacterCount } == true
            }
            override fun caretPositionChanged(event: java.awt.event.InputMethodEvent) = Unit
        })
        prompt.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "flow.send")
        prompt.actionMap.put(
            "flow.send",
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) {
                    if (composingText) return
                    if (current.agent.status == "running" && enqueue.isEnabled) {
                        enqueue.doClick(0)
                    } else if (send.isEnabled) {
                        send.doClick(0)
                    } else if (start.isEnabled) {
                        start.doClick(0)
                    }
                }
            },
        )
        val shortcut = if (com.intellij.openapi.util.SystemInfo.isMac) "⌘ Enter" else "Ctrl Enter"
        listOf(start, send, enqueue, cancel).forEach { button ->
            button.accessibleContext.accessibleName = button.text
            button.preferredSize = com.intellij.util.ui.JBUI.size(76, 30)
            button.minimumSize = button.preferredSize
        }
        start.text = "Send"
        start.icon = AllIcons.General.ArrowUp
        start.toolTipText = "Send and connect automatically (Enter or $shortcut)"
        FlowUi.primary(start)
        enqueue.text = "Queue"
        enqueue.toolTipText = "Send after the current turn (Enter or $shortcut)"
        FlowUi.iconButton(attachFiles, com.intellij.icons.AllIcons.Actions.Attach, "Attach files, folders, images or documents")
        attachFiles.addActionListener { images.choose() }
        val originalTransfer = prompt.transferHandler
        prompt.transferHandler = object : javax.swing.TransferHandler() {
            override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor) || support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor) || originalTransfer?.canImport(support) == true
            override fun importData(support: TransferSupport): Boolean {
                if (support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                    val files = runCatching { (support.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<java.io.File>() }.getOrNull().orEmpty()
                    if (files.isNotEmpty()) {
                        images.importFiles(files.map { it.absolutePath })
                        return true
                    }
                }
                if (support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                    val image = runCatching { support.transferable.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as? java.awt.Image }.getOrNull()
                    if (image != null) {
                        images.attachImage(image)
                        return true
                    }
                }
                return originalTransfer?.importData(support) == true
            }
            override fun getSourceActions(component: javax.swing.JComponent) = originalTransfer?.getSourceActions(component) ?: NONE
            override fun exportToClipboard(component: javax.swing.JComponent, clipboard: java.awt.datatransfer.Clipboard, action: Int) {
                originalTransfer?.exportToClipboard(component, clipboard, action)
            }
            override fun exportAsDrag(component: javax.swing.JComponent, event: java.awt.event.InputEvent, action: Int) {
                originalTransfer?.exportAsDrag(component, event, action)
            }
        }
        val pasteText = prompt.actionMap.get(javax.swing.text.DefaultEditorKit.pasteAction)
        prompt.actionMap.put(
            javax.swing.text.DefaultEditorKit.pasteAction,
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) {
                    if (!images.pasteImage()) pasteText?.actionPerformed(event)
                }
            },
        )
        send.icon = AllIcons.General.ArrowUp
        send.toolTipText = "Send (Enter or $shortcut)"
        FlowUi.primary(send)
        cancel.toolTipText = "Stop the current turn"
        modelChoice.apply {
            horizontalTextPosition = javax.swing.SwingConstants.LEFT
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
            it.isContentAreaFilled = false
            it.isBorderPainted = false
            it.margin = com.intellij.util.ui.JBUI.insets(4)
        }
        connect.addActionListener { startSession(connectOnly = true) }
        modelField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) = updateModelChoice()
        })
        updateModelChoice()
        // One toolbar row: labels yield width to the trailing Send/Stop action.
        composerMenu = javax.swing.JPopupMenu().apply {
            add(javax.swing.JMenuItem("Project context…", AllIcons.Actions.ProjectDirectory).apply { addActionListener { promptContext.showControls(prompt) } })
            add(javax.swing.JMenuItem("Agent settings", AllIcons.General.Settings).apply { addActionListener { openSettings?.invoke() } })
            add(javax.swing.JMenuItem("Find in conversation…", AllIcons.Actions.Search).apply { addActionListener { conversation.showFind() } })
            add(commandsMenu)
            add(javax.swing.JMenuItem("Paste attachment", AllIcons.Actions.MenuPaste).apply { addActionListener { if (!images.pasteImage()) controller.showActionError("The clipboard does not contain files or an image.") } })
            add(javax.swing.JMenuItem("Queue this prompt", AllIcons.Actions.AddList).apply { addActionListener { enqueuePrompt() } })
            add(javax.swing.JMenuItem("Rewind files…", AllIcons.Actions.Undo).apply { addActionListener { chooseRewind() } })
            add(
                javax.swing.JMenu("Conversation font").apply {
                    listOf("Increase" to 1, "Decrease" to -1, "Use IDE default" to 0).forEach { (label, change) ->
                        add(
                            javax.swing.JMenuItem(label).apply {
                                addActionListener {
                                    settings.setConversationFontOffset(if (change == 0) 0 else settings.state.conversationFontOffset + change)
                                    conversation.setFontOffset(settings.state.conversationFontOffset)
                                }
                            },
                        )
                    }
                },
            )
            add(javax.swing.JMenuItem("Enhance prompt…", AllIcons.Toolwindows.ToolWindowAskAI).apply { addActionListener { enhance.doClick(0) } })
            addSeparator()
            add(close)
        }
        val options = JButton("More composer actions").apply {
            FlowUi.iconButton(this, com.intellij.icons.AllIcons.Actions.More, "More actions")
            addActionListener { composerMenu.show(this, 0, height) }
        }
        // Agent/model choices have their own row, so the names never compete with Send.
        profile.preferredSize = com.intellij.util.ui.JBUI.size(120, 30)
        profile.minimumSize = com.intellij.util.ui.JBUI.size(105, 30)
        profile.putClientProperty("JComboBox.isBorderless", true)
        val agentControls = JPanel(BorderLayout(com.intellij.util.ui.JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = com.intellij.util.ui.JBUI.Borders.emptyBottom(6)
            add(profile, BorderLayout.WEST)
            add(modelChoice, BorderLayout.CENTER)
        }
        val controls = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(FlowUi.toolbar(attachFiles, options), BorderLayout.WEST)
            add(modeChoice, BorderLayout.CENTER)
            add(FlowUi.toolbar(start, send, enqueue, cancel), BorderLayout.EAST)
        }
        val composer = JPanel(BorderLayout(0, com.intellij.util.ui.JBUI.scale(4))).apply {
            accessibleContext.accessibleName = "Prompt composer"
            background = FlowUi.inputBackground
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.border.LineBorder(com.intellij.ui.JBColor.border(), com.intellij.util.ui.JBUI.scale(1), true),
                com.intellij.util.ui.JBUI.Borders.empty(6),
            )
            val attachmentStrip = FlowUi.scroll(images, 88).apply { isVisible = images.isVisible }
            images.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentShown(event: java.awt.event.ComponentEvent) {
                    attachmentStrip.isVisible = true
                    revalidate()
                }
                override fun componentHidden(event: java.awt.event.ComponentEvent) {
                    attachmentStrip.isVisible = false
                    revalidate()
                }
            })
            add(FlowUi.column(attachmentStrip, promptContext), BorderLayout.NORTH)
            val input = object : JBScrollPane(prompt) {
                override fun getPreferredSize(): java.awt.Dimension {
                    val line = prompt.getFontMetrics(prompt.font).height
                    val text = prompt.ui.getRootView(prompt).getPreferredSpan(javax.swing.text.View.Y_AXIS).toInt()
                    return java.awt.Dimension(0, text.coerceIn(line * 2 + 8, line * 8 + 8))
                }
            }.apply {
                border = com.intellij.util.ui.JBUI.Borders.empty(4, 4)
                isOpaque = false
                viewport.isOpaque = false
                horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }
            prompt.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(event: javax.swing.event.DocumentEvent) {
                    input.revalidate()
                    composerStatus.revalidate()
                    updateSubmitControls()
                }
            })
            add(input, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        val composerArea = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(agentControls, BorderLayout.NORTH)
            add(composer, BorderLayout.CENTER)
        }
        prompt.isOpaque = false
        error.font = javax.swing.UIManager.getFont("Label.font")
        error.rows = 0
        val connectionHelp = JButton("Connection settings", AllIcons.General.Settings).apply {
            isVisible = false
            addActionListener { openSettings?.invoke() }
        }
        error.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentShown(event: java.awt.event.ComponentEvent) {
                connectionHelp.isVisible = openSettings != null
            }
            override fun componentHidden(event: java.awt.event.ComponentEvent) {
                connectionHelp.isVisible = false
            }
        })
        val footer = JPanel(BorderLayout(0, com.intellij.util.ui.JBUI.scale(6))).apply {
            isOpaque = false
            add(FlowUi.column(permissionScroll, error, connectionHelp, composerStatus, promptQueue), BorderLayout.NORTH)
            add(composerArea, BorderLayout.CENTER)
        }
        if (openSettings == null) footer.add(Disclosure("Agent settings", FlowUi.scroll(configuration, 180)), BorderLayout.SOUTH)
        nativeFooter = footer
        add(footer, BorderLayout.SOUTH)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(event: java.awt.event.ComponentEvent) = updateModelChoice()
        })
        profile.addActionListener {
            val selected = profile.selectedItem as String
            val changed = (selection?.profile ?: settings.state.adapterProfile) != selected
            if (selection != null) selection.select(selected) else settings.selectProfile(selected)
            if (changed) {
                executable.text = ""
                arguments.text = "[]"
                modelField.text = ""
            }
            modelField.isEnabled = profile.selectedItem !in setOf("claude", "cursor")
            applyDetected(force = true)
            updateModelChoice()
            compatibility.text = if (profile.selectedItem == "claude") "Choose a released claude-agent-acp executable (or Node executable with the adapter entry point in arguments). Authentication methods are discovered on connection. Model CLI override is unavailable; optional upstream extensions and resume are disabled." else "Choose the Codex ACP executable. Authentication stays with Codex."
        }
        modelField.isEnabled = profile.selectedItem !in setOf("claude", "cursor")
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
        enqueue.addActionListener { enqueuePrompt() }
        cancel.addActionListener { current.agent.id?.let { controller.agentRequest("sessions.cancel", AgentProtocol.command(it)) } }
        close.addActionListener { closeSession() }
        update(current)
        if (project != null && !ApplicationManager.getApplication().isUnitTestMode) detectAgents(force = false)
        if (project != null && !ApplicationManager.getApplication().isUnitTestMode && com.intellij.ui.jcef.JBCefApp.isSupported()) {
            try {
                react = ReactChatPanel(controller.scope, ::webState, ::webAction, ::restoreNativeChat)
                remove(conversation)
                remove(footer)
                add(react, BorderLayout.CENTER)
                webRefresh.start()
            } catch (_: Exception) {
                restoreNativeChat()
            }
        }
    }

    private fun restoreNativeChat() {
        webRefresh.stop()
        react?.let {
            remove(it)
            com.intellij.openapi.util.Disposer.dispose(it)
        }
        react = null
        add(conversation, BorderLayout.CENTER)
        conversation.update(if (newDraft) emptyList() else current.agent.events, current.agent.id)
        firePropertyChange("reactAvailable", true, false)
        nativeFooter?.let { add(it, BorderLayout.SOUTH) }
        revalidate()
        repaint()
    }

    internal fun webState(): com.google.gson.JsonObject {
        val agent = current.agent
        val events = if (newDraft) emptyList() else agent.events.filter(ConversationProjection::relevant)
        if (events != webMessageEvents) {
            webMessageEvents = events
            webMessages = gson.toJsonTree(ConversationProjection.messages(events))
        }
        if (agent.configOptions != webConfigOptions) {
            webConfigOptions = agent.configOptions
            webConfiguration = gson.toJsonTree(agent.configOptions)
        }
        val progressAgent = if (newDraft) dev.flow.bridge.AgentView() else agent
        val nextProgressKey = listOf(newDraft, agent.id, agent.status, agent.plan, agent.subagents, agent.events.filter { it.tool?.parentToolCallId != null || it.tool?.name?.lowercase() in setOf("task", "agent", "spawn_agent") })
        if (progressKey != nextProgressKey) {
            progressKey = nextProgressKey
            webProgress = AgentProgress.state(progressAgent)
        }
        return com.google.gson.JsonObject().apply {
            add("progressPanels", webProgress)
            addProperty("sessionId", agent.id)
            addProperty("title", conversationTitle)
            addProperty("connected", current.connected)
            addProperty("draft", prompt.text)
            addProperty("draftVersion", webDraftVersion)
            addProperty("promptHistoryRevision", promptHistory.revision)
            addProperty("provider", AgentSelection.provider(provider()) ?: provider())
            addProperty("profile", profile.selectedItem.toString())
            addProperty("providerEnabled", profile.isEnabled)
            addProperty("model", modelChoice.text)
            addProperty("modelEnabled", modelChoice.isEnabled)
            addProperty("configReady", current.connected && agent.status == "ready")
            add("configOptions", webConfiguration)
            addProperty("mode", modeChoice.text)
            addProperty("modeEnabled", modeChoice.isEnabled)
            addProperty("canSend", start.isEnabled || send.isEnabled || enqueue.isEnabled)
            addProperty("running", agent.status == "running")
            addProperty("canStop", cancel.isEnabled && cancel.isVisible)
            addProperty("progress", if (composerStatus.isVisible) composerStatus.text else "")
            add(
                "activity",
                com.google.gson.JsonObject().apply {
                    addProperty("phase", activityPhase())
                    addProperty("turnId", activityTurn?.second)
                    addProperty("startedAt", activityStartedAt)
                },
            )
            addProperty("enhancementState", enhancementState)
            add("enhancement", enhancementReview?.state)
            addProperty("canEnhance", project != null && current.connected && enhancementState.isEmpty() && prompt.text.isNotBlank() && prompt.text.toByteArray().size <= 8192)
            addProperty("error", error.text)
            addProperty("dark", !com.intellij.ui.JBColor.isBright())
            addProperty("fontSize", com.intellij.util.ui.UIUtil.getLabelFont().size + settings.state.conversationFontOffset)
            add("messages", webMessages)
            add("attachments", images.webState())
            add("context", promptContext.webState())
            add("changes", chatChanges.webState())
            add("permission", gson.toJsonTree(agent.permissions.firstOrNull()))
            add("authMethods", gson.toJsonTree(if (authenticate.isEnabled) agent.authMethods else emptyList<String>()))
            add("queue", gson.toJsonTree(agent.queue))
            addProperty("queuePaused", agent.queuePaused)
            add("commands", gson.toJsonTree(agent.availableCommands))
        }
    }

    internal suspend fun webAction(value: com.google.gson.JsonObject): com.google.gson.JsonElement {
        check(!disposed)
        val action = value["action"].asString
        require(action == "upload" || value.toString().length <= 256 * 1024) { "This request is too large." }
        fun text(key: String) = value[key]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        fun session(): String {
            check(current.connected && current.agent.id != null && text("sessionId") == current.agent.id) { "The session changed. Review the conversation and try again." }
            return requireNotNull(current.agent.id)
        }
        fun draft() {
            val version = value["version"].asLong
            require(version in webDraftVersion..(webDraftVersion + 100000)) { "Your draft changed. Try again." }
            val content = text("text")
            require(content.length <= 100000) { "The prompt is too long." }
            webDraftVersion = version
            if (prompt.text != content) prompt.text = content
            updateSubmitControls()
        }
        when (action) {
            "draft" -> draft()
            "promptHistory" -> {
                val operation = text("operation")
                if (operation != "list") {
                    val enabled = value["enabled"]?.takeUnless { it.isJsonNull }
                    require(enabled == null || enabled.isJsonPrimitive && enabled.asJsonPrimitive.isBoolean) { "Invalid autofill setting." }
                    require(operation != "enable" || enabled != null) { "Choose whether to enable prompt autofill." }
                    require(text("text").length <= 4096) { "Invalid prompt history entry." }
                    promptHistory.change(operation, value["revision"].asLong, text("text"), enabled?.asBoolean ?: promptHistory.state.enabled)
                }
                return gson.toJsonTree(promptHistory.snapshot())
            }
            "send" -> {
                check(text("sessionId") == current.agent.id.orEmpty()) { "The session changed. Review your draft." }
                draft()
                check(start.isEnabled || send.isEnabled || enqueue.isEnabled) { "Wait for the agent connection, then try again." }
                when {
                    current.agent.status == "running" -> enqueuePrompt()
                    current.agent.status == "ready" -> sendPrompt()
                    else -> startSession(false)
                }
            }
            "stop" -> {
                session()
                check(cancel.isEnabled)
                cancel.doClick(0)
            }
            "new" -> requestNewConversation()
            "settings" -> openSettings?.invoke()
            "history" -> openHistory?.invoke()
            "review" -> {
                session()
                openChanges?.invoke()
            }
            "provider" -> {
                check(profile.isEnabled) { "Start a new conversation to change agents." }
                check(text("sessionId") == current.agent.id.orEmpty() && text("currentProfile") == profile.selectedItem) { "The agent selection changed. Open the list again." }
                require(text("value") in setOf("automatic", "codex", "claude", "cursor"))
                if (text("value") != profile.selectedItem) profile.selectedItem = text("value")
            }
            "model" -> {
                check(modelChoice.isEnabled)
                check(text("sessionId") == current.agent.id.orEmpty()) { "The session changed. Open the model list again." }
                if (current.agent.status != "ready") startSession(connectOnly = true)
            }
            "configure", "mode" -> {
                val id = session()
                check(current.agent.status == "ready") { "Wait until the agent is ready to change models or settings." }
                val option = requireNotNull(current.agent.configOptions.firstOrNull { it.id == text("configId") && it.isMode == (action == "mode") }) { "This setting is no longer available. Open its list again." }
                val model = current.agent.configOptions.firstOrNull { it.category == "model" || it.id == "model" }
                check((model?.currentValue ?: com.google.gson.JsonNull.INSTANCE) == value["modelValue"]) { "The model changed. Review its available settings and try again." }
                check(option.currentValue == value["currentValue"]) { "This setting changed. Review its current value and try again." }
                val selected = requireNotNull(value["value"])
                val params = AgentProtocol.configure(id, option, selected)
                if (selected != option.currentValue) controller.submitAgent("sessions.configure", params)
            }
            "attach" -> images.choose()
            "paste" -> check(images.pasteImage()) { "The clipboard does not contain files or an image." }
            "upload" -> images.upload(text("name"), text("data"))
            "removeAttachment", "openAttachment" -> images.webAction(value["index"].asInt, value["revision"].asLong, action == "removeAttachment")
            "removeContext" -> promptContext.webRemove(value["index"].asInt, value["revision"].asLong)
            "context" -> promptContext.showControls(react ?: this)
            "enhance" -> {
                check(current.connected && text("sessionId") == current.agent.id.orEmpty()) { "The session changed. Review your draft before enhancing it." }
                draft()
                enhancePrompt()
            }
            "enhancement" -> {
                check(text("sessionId") == current.agent.id.orEmpty()) { "The session changed. Close this review and enhance the current draft." }
                if (text("operation") == "accept") draft()
                requireNotNull(enhancementReview).action(value)
            }
            "permission" -> {
                val id = session()
                val request = requireNotNull(current.agent.permissions.firstOrNull { it.id == text("requestId") }) { "This approval has already been answered." }
                val option = text("option").takeIf { it.isNotEmpty() }
                require(option == null || request.options.any { it.first == option })
                controller.agentRequest("permissions.respond", AgentProtocol.permission(id, request.id, option))
            }
            "authenticate" -> {
                val id = session()
                check(authenticate.isEnabled && text("method") in current.agent.authMethods)
                controller.agentRequest("sessions.authenticate", AgentProtocol.command(id).apply { addProperty("methodId", text("method")) })
            }
            "queue" -> {
                val id = session()
                val operation = text("operation")
                require(operation in setOf("pause", "resume", "remove", "move", "clear"))
                if (operation == "resume") check(current.agent.queue.none { it.status == "uncertain" }) { "Review prompts with unknown outcomes in History first." }
                val params = com.google.gson.JsonObject().apply {
                    addProperty("sessionId", id)
                    addProperty("action", operation)
                }
                if (operation in setOf("remove", "move")) {
                    check(current.agent.queue.any { it.id == text("queueId") && it.status in setOf("pending", "uncertain") })
                    params.addProperty("id", text("queueId"))
                }
                if (operation == "move") {
                    val position = value["position"].asInt
                    require(position in current.agent.queue.indices)
                    params.addProperty("position", position)
                }
                controller.submitAgent("sessions.queue", params)
            }
            "rewind", "quote", "copy", "recordedAttachment" -> {
                val id = session()
                val messages = ConversationProjection.messages(current.agent.events)
                val event = requireNotNull(messages.getOrNull(value["index"].asInt))
                check(event.turnId.orEmpty() == text("turnId")) { "The conversation changed. Select the message again." }
                when (action) {
                    "rewind" -> {
                        require(event.kind == "turn.started")
                        requestRewind(id, event)
                    }
                    "copy" -> com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(event.text))
                    "quote" -> if (prompt.text.isBlank() || com.intellij.openapi.ui.Messages.showYesNoDialog(project, "Replace the current draft with this quote?", "Quote message", null) == com.intellij.openapi.ui.Messages.YES) {
                        prompt.text = event.text.lineSequence().joinToString("\n") { "> $it" } + "\n\n"
                    }
                    else -> {
                        val attachments = TranscriptAttachments(project, controller, id, requireNotNull(event.turnId), event.images, controller.scope, event.attachments)
                        try {
                            object : com.intellij.openapi.ui.DialogWrapper(project) {
                                init {
                                    title = "Recorded attachments"
                                    init()
                                    setOKButtonText("Close")
                                }
                                override fun createActions() = arrayOf(okAction)
                                override fun createCenterPanel(): javax.swing.JComponent = attachments
                            }.show()
                        } finally {
                            attachments.dispose()
                        }
                    }
                }
            }
            "nativeDiff" -> {
                session()
                chatChanges.webDiff(value["index"].asInt, text("snapshot"), native = true)
            }
            "moreChanges" -> {
                session()
                chatChanges.webMore()
            }
            "changesStats" -> {
                session()
                return chatChanges.webStats(text("snapshot"))
            }
            "refreshChanges" -> {
                session()
                chatChanges.webRefresh()
            }
            "complete" -> {
                check(text("sessionId") == current.agent.id.orEmpty() && text("profile") == profile.selectedItem.toString()) { "The agent changed. Open suggestions again." }
                return completions.complete(text("trigger"), text("query"), AgentSelection.provider(provider()) ?: provider(), if (newDraft) null else current.agent.workspace, current.agent.availableCommands)
            }
            "openReference" -> {
                val source = session()
                val workspace = current.agent.workspace
                completions.open(text("reference"), workspace) { current.connected && current.agent.id == source && current.agent.workspace == workspace }
            }
            "knowledge", "knowledgeOpen" -> {
                val expected = current.agent.id.orEmpty()
                val workspace = current.agent.workspace
                check(current.connected && text("sessionId") == expected) { "The conversation changed. Reopen the code map." }
                val selected = text("workspace")
                require(selected in setOf("project", "session"))
                if (selected == "session") session()
                val params = value["params"]?.asJsonObject?.deepCopy() ?: com.google.gson.JsonObject()
                require(params.entrySet().all { it.key in setOf("query", "path", "line", "contentHash", "scope", "symbol", "direction", "depth", "limit", "pattern", "fixed", "ignoreCase", "full", "paths", "title", "body", "author", "touches", "constraints", "replaces", "private", "alwaysFollow") })
                if (selected == "session") params.addProperty("sessionId", expected)
                fun valid() = current.connected && current.agent.id.orEmpty() == expected && (selected == "project" || current.agent.workspace == workspace)
                if (action == "knowledgeOpen") {
                    val path = requireNotNull(params["path"]?.asString)
                    val line = params["line"]?.asInt ?: 1
                    require(line in 1..10000000 && !params["contentHash"]?.asString.isNullOrEmpty())
                    val freshnessParams = com.google.gson.JsonObject().apply {
                        addProperty("path", path)
                        addProperty("contentHash", params["contentHash"].asString)
                        if (selected == "session") addProperty("sessionId", expected)
                    }
                    val freshness = controller.productivityRequest("context.freshness", freshnessParams)
                    check(valid() && freshness["freshness"].asString == "verifiedAtRead") { "Source changed. Refresh the code map before opening this result." }
                    val root = requireNotNull(if (selected == "session") workspace else project?.basePath)
                    completions.open(java.nio.file.Path.of(root).resolve(path).toString() + "#L$line", if (selected == "session") workspace else null) { valid() }
                } else {
                    val methods = mapOf("find" to "context.find", "findAll" to "context.findAll", "file" to "context.file", "fileApi" to "context.fileApi", "trace" to "context.trace", "map" to "context.map", "graph" to "context.graph", "check" to "context.check", "learnings" to "context.learnings", "review" to "context.review", "saveLearning" to "context.saveLearning", "branches" to "context.branches")
                    val method = requireNotNull(methods[text("operation")]) { "Unsupported code-map action." }
                    if (method != "context.saveLearning") {
                        params.addProperty("byteBudget", 32768)
                        params.addProperty("tokenBudget", 32768)
                    }
                    val result = controller.productivityRequest(method, params)
                    check(valid()) { "The conversation changed. Reopen the code map." }
                    return result
                }
            }
            "command" -> {
                session()
                val command = requireNotNull(current.agent.availableCommands.firstOrNull { it.name == text("name") })
                prompt.text = prompt.text + "/${command.name} "
            }
            else -> error("Unsupported chat action")
        }
        return com.google.gson.JsonNull.INSTANCE
    }

    private fun startSession(connectOnly: Boolean, resolved: Boolean = false, original: StartIntent? = null) {
        try {
            if (submitting?.isActive == true || pendingStart != null || pendingDetectionStart != null || resolvingAdapter) return
            val intent = original ?: StartIntent(connectOnly, promptRevision.version, images.revision, promptContext.revision, profile.selectedItem as String)
            require(intent.profile == profile.selectedItem && (connectOnly || (intent.prompt == promptRevision.version && intent.attachments == images.revision && intent.context == promptContext.revision))) {
                "Your draft changed while connecting. Review it and press Send when ready."
            }
            require(connectOnly || prompt.text.isNotBlank()) { "Describe a task before sending references." }
            if (detectionJob?.isActive == true && executable.text.isBlank()) {
                pendingDetectionStart = intent
                composerStatus.text = "Finding your agent…"
                composerStatus.isVisible = true
                return
            }
            require(executable.text.isNotBlank()) { "No agent found. Open Settings to detect an installed Codex, Claude or Cursor adapter, or choose its executable." }
            if (commit.text.isBlank() && sourceSessionId == null) {
                require(connectOnly || prompt.text.isNotBlank()) { "Enter a task before sending." }
                pendingStart = intent
                controller.agentRequest("sessions.baseline")
                return
            }
            if (!connectOnly && images.snapshot().items.isNotEmpty()) {
                pendingImageSend = promptRevision.version to images.revision
                startSession(connectOnly = true)
                return
            }
            val managed = localAgents.firstOrNull { it.executable == executable.text.trim() && gson.toJson(it.arguments) == arguments.text && it.arguments.any { arg -> arg.startsWith("--package=@agentclientprotocol/") } }
            if (!resolved && managed != null) {
                val original = executable.text to arguments.text
                val selected = profile.selectedItem
                resolvingAdapter = true
                controller.scope.launch(kotlinx.coroutines.Dispatchers.EDT) {
                    try {
                        val found = withContext(kotlinx.coroutines.Dispatchers.IO) { dev.flow.bridge.LocalAgents.discover() }
                        if (!disposed && original == (executable.text to arguments.text) && selected == profile.selectedItem) {
                            found.firstOrNull { it.profile == managed.profile }?.let { cached ->
                                executable.text = cached.executable
                                arguments.text = gson.toJson(cached.arguments)
                            }
                            localAgents = found
                            resolvingAdapter = false
                            startSession(connectOnly, resolved = true, original = intent)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        controller.showActionError("Could not refresh the installed adapter. Retry detection in Settings; your draft is retained.")
                    } finally {
                        resolvingAdapter = false
                    }
                }
                return
            }
            val selectedOverlay = overlay
            val prepared = if (connectOnly) null else promptContext.prepare()
            if (selectedOverlay != null && project != null) {
                val paths = selectedOverlay.getAsJsonArray("files").map { it.asJsonObject["path"].asString }.toSet()
                val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                require(documents.unsavedDocuments.none { document -> documents.getFile(document)?.path?.removePrefix("${project.basePath}/") in paths }) { "A selected overlay file has unsaved changes. Review the overlay again and save or exclude it." }
            }
            submit(
                "sessions.start",
                AgentProtocol.start(executable.text.trim(), arguments.text, commit.text.trim(), prepared?.text.orEmpty(), auth.text.trim(), sourceSessionId, stopped.isSelected, if (provider() !in setOf("claude", "cursor")) modelField.text.trim() else "", connectOnly = connectOnly).apply {
                    addProperty("includeContext", prepared?.includeContext ?: true)
                    addProperty("adapterProfile", provider().takeUnless { it == "automatic" }.orEmpty())
                    rewindTurnId?.let { addProperty("rewindTurnId", it) }
                    if (selectedOverlay != null) add("overlay", selectedOverlay)
                    provenance?.takeIf { it.first == promptRevision.version }?.let { addProperty("promptProvenance", it.second) }
                },
                draft = prepared,
            )
        } catch (invalid: IllegalArgumentException) {
            controller.showActionError(invalid.message ?: "Invalid session settings.")
        } catch (invalid: IllegalStateException) {
            controller.showActionError(invalid.message ?: "Context changed. Review it before sending.")
        }
    }

    private fun sendPrompt() {
        if (submitting?.isActive == true) return
        send.isEnabled = false
        submitting = controller.scope.launch(kotlinx.coroutines.Dispatchers.EDT) {
            try {
                val session = current.agent.id ?: return@launch
                require(prompt.text.isNotBlank()) { "Describe a task before sending references." }
                val attached = images.snapshot()
                require(!attached.hasInlineImages || current.agent.supportsImages) { "This agent does not support image prompts. Your draft is retained." }
                val prepared = promptContext.prepare()
                val version = promptRevision.version
                promptContext.validate(prepared)
                check(current.agent.id == session && current.agent.status == "ready" && version == promptRevision.version) { "The session or prompt changed. Send again when ready." }
                controller.submitAgent(
                    "sessions.prompt",
                    AgentProtocol.command(session, prepared.text).apply {
                        add("images", attached.json())
                        add("attachments", attached.files())
                        addProperty("includeContext", prepared.includeContext)
                        provenance?.takeIf { it.first == version }?.let { addProperty("promptProvenance", it.second) }
                    },
                )
                acknowledgeDraft(prepared, attached)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.showActionError(error.message ?: "Could not prepare prompt context.")
            }
        }
    }

    private fun acknowledgeDraft(prepared: ComposerContext.Prepared, attached: PromptAttachments.Frozen) {
        promptHistory.record(prepared.original)
        if (attached.revision != images.revision || prompt.text != prepared.original) return
        promptContext.acknowledge(prepared)
        if (prompt.text.isEmpty()) images.acknowledge(attached)
    }

    private fun enqueuePrompt() {
        if (submitting?.isActive == true) return
        submitting = controller.scope.launch(kotlinx.coroutines.Dispatchers.EDT) {
            try {
                val session = requireNotNull(current.agent.id) { "Connect before queuing a prompt." }
                check(current.connected && current.agent.status in setOf("running", "ready")) { "Connect to an active session before queuing." }
                require(prompt.text.isNotBlank()) { "Enter a prompt before queuing." }
                val attached = images.snapshot()
                require(!attached.hasInlineImages || current.agent.supportsImages) { "This agent does not support image prompts." }
                val prepared = promptContext.prepare()
                promptContext.validate(prepared)
                val key = Triple(promptRevision.version, prepared.revision, attached.revision)
                val params = queuedAttempt?.takeIf { it.first == key && it.second["sessionId"].asString == session }?.second ?: com.google.gson.JsonObject().apply {
                    addProperty("sessionId", session)
                    addProperty("action", "enqueue")
                    addProperty("id", "queue-" + java.util.UUID.randomUUID())
                    add(
                        "command",
                        AgentProtocol.command(session, prepared.text).apply {
                            addProperty("includeContext", prepared.includeContext)
                            add("images", attached.json())
                            add("attachments", attached.files())
                            provenance?.takeIf { it.first == promptRevision.version }?.let { addProperty("promptProvenance", it.second) }
                        },
                    )
                }.also { queuedAttempt = key to it }
                check(current.agent.id == session && current.agent.status in setOf("running", "ready")) { "Session changed; the draft is retained." }
                controller.submitAgent("sessions.queue", params)
                acknowledgeDraft(prepared, attached)
                queuedAttempt = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                controller.showActionError(failure.message ?: "Queue acknowledgement was lost. The draft is retained; an unchanged retry uses the same queue ID.")
            }
        }
    }

    private fun chooseRewind() {
        val source = current.agent.id ?: return
        val turns = current.agent.events.filter { it.kind == "turn.started" && it.turnId != null }
        if (turns.isEmpty()) {
            controller.showActionError("No recorded turn checkpoints are available. Open an earlier conversation in History.")
            return
        }
        val selected = com.intellij.openapi.ui.Messages.showChooseDialog(project, "Restore files to before which message?", "Rewind files", null, turns.map { it.text.lineSequence().firstOrNull().orEmpty().take(100) }.toTypedArray(), null)
        turns.getOrNull(selected)?.let { requestRewind(source, it) }
    }

    internal fun requestRewind(source: String, event: dev.flow.bridge.AgentEvent) {
        val turn = event.turnId ?: return
        controller.scope.launch(kotlinx.coroutines.Dispatchers.EDT) {
            try {
                check(current.connected) { "Connect the engine before rewinding." }
                val result = controller.historyRequest(
                    "history.rewindPreview",
                    com.google.gson.JsonObject().apply {
                        addProperty("sessionId", source)
                        addProperty("turnId", turn)
                    },
                ).asJsonObject
                check(result["complete"].asBoolean && result["regularFiles"].asBoolean) { "This checkpoint contains unsupported files or incomplete capture. It cannot be rewound safely." }
                val accepted = com.intellij.openapi.ui.Messages.showYesNoDialog(
                    project,
                    """Restore ${result["files"].asInt} files to before this message?

${event.text.take(180)}

Flow will close the current agent, pause its queue, and create a new isolated workspace and conversation. The original workspace, history and main project remain available. The selected prompt is restored as a draft; it is not sent automatically.""",
                    "Rewind files",
                    "Create rewound workspace",
                    "Cancel",
                    null,
                )
                if (accepted != com.intellij.openapi.ui.Messages.YES) return@launch
                val active = current.agent.id != null && current.agent.status !in setOf("", "closed", "failed", "interrupted", "No active session")
                if (active) {
                    pendingRewind = source to event
                    closeSession(confirm = false)
                } else {
                    beginRewind(source, event)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                controller.showActionError(failure.message ?: "The selected checkpoint cannot be rewound.")
            }
        }
    }

    private fun beginRewind(source: String, event: dev.flow.bridge.AgentEvent) {
        newConversation(source)
        rewindTurnId = event.turnId
        stopped.isSelected = true
        prompt.text = event.text
        if (event.images.isNotEmpty() || event.attachments.isNotEmpty()) {
            controller.scope.launch(kotlinx.coroutines.Dispatchers.EDT) {
                val version = promptRevision.version
                try {
                    val values = com.google.gson.JsonArray()
                    event.images.forEach { image ->
                        val bytes = controller.historyRequest(
                            "history.image",
                            com.google.gson.JsonObject().apply {
                                addProperty("sessionId", source)
                                addProperty("turnId", event.turnId)
                                addProperty("imageId", image.id)
                            },
                        ).asJsonObject
                        values.add(
                            bytes.apply {
                                addProperty("name", image.name)
                                addProperty("mimeType", image.mimeType)
                            },
                        )
                    }
                    if (!disposed && version == promptRevision.version) images.restore(values, com.google.gson.JsonArray().apply { event.attachments.forEach { add(it.json()) } })
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    controller.showActionError("Files were rewound, but image attachments could not be restored. Review the draft before sending.")
                }
            }
        }
        startSession(connectOnly = true)
    }

    internal fun restoreQueuedDraft(command: com.google.gson.JsonObject) {
        if (prompt.text.isNotBlank() && com.intellij.openapi.ui.Messages.showYesNoDialog(project, "Replace the current draft with the recovered queued prompt? Nothing will be sent automatically.", "Recover queued draft", null) != com.intellij.openapi.ui.Messages.YES) return
        prompt.text = command["prompt"].asString
        promptContext.clear()
        images.restore(command.getAsJsonArray("images") ?: com.google.gson.JsonArray(), command.getAsJsonArray("attachments") ?: com.google.gson.JsonArray())
        prompt.requestFocusInWindow()
    }

    internal fun findConversation() {
        react?.find() ?: conversation.showFind()
    }

    private fun enhancePrompt() {
        if (enhancementState.isNotEmpty()) {
            controller.showActionError("A prompt enhancement is already open. Review or close it first.")
            return
        }
        project?.let {
            if (react != null) {
                enhancementReview?.open(prompt, promptRevision) { hash -> provenance = promptRevision.version to hash }
                return
            }
            PromptEnhancement.open(it, prompt, promptRevision, changed = { state ->
                if (!disposed) {
                    enhancementState = state
                    react?.update()
                }
            }) { hash -> provenance = promptRevision.version to hash }
        }
    }

    private fun activityPhase(): String = when {
        !current.connected -> "idle"
        current.agent.status in setOf("cancelling", "closing") -> "stopping"
        current.agent.permissions.isNotEmpty() -> "approval"
        current.agent.status == "authenticationRequired" -> "authentication"
        pendingStart != null || pendingDetectionStart != null || current.agent.status in setOf("starting", "authenticating", "configuring") -> "connecting"
        current.agent.status != "running" -> "idle"
        else -> when (current.agent.events.lastOrNull { it.kind in setOf("turn.started", "message.delta", "thinking.delta", "operation.started", "operation.updated", "operation.finished", "agent.plan", "agent.plan.delta") }?.kind) {
            "message.delta" -> "responding"
            "thinking.delta" -> "thinking"
            "operation.started", "operation.updated" -> "tools"
            else -> "working"
        }
    }

    private fun submit(method: String, params: com.google.gson.JsonObject, draft: ComposerContext.Prepared?) {
        if (submitting?.isActive == true) return
        start.isEnabled = false
        send.isEnabled = false
        submitting = controller.scope.launch(kotlinx.coroutines.Dispatchers.EDT) {
            try {
                controller.submitAgent(method, params)
                draft?.let {
                    promptHistory.record(it.original)
                    promptContext.acknowledge(it)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controller.showActionError("The agent did not acknowledge the request. Your draft is retained; check the connection before retrying.")
            }
        }
    }

    internal fun requestNewConversation() {
        val active = current.agent.id != null && current.agent.status !in listOf("closed", "failed", "interrupted", "No active session", "")
        if (active) {
            if (com.intellij.openapi.ui.Messages.showYesNoDialog(project, "Close this agent session and start a new conversation? Recorded changes remain available in History.", "New conversation", "Close and start new", "Keep current session", null) != com.intellij.openapi.ui.Messages.YES) return
            pendingNewConversation = true
            closeSession(confirm = false)
        } else if (prompt.text.isBlank() || com.intellij.openapi.ui.Messages.showYesNoDialog(project, "Discard the current draft?", "New conversation", null) == com.intellij.openapi.ui.Messages.YES) {
            newConversation(null)
        }
    }

    private fun closeSession(confirm: Boolean = true) {
        val id = current.agent.id ?: return
        if (confirm && com.intellij.openapi.ui.Messages.showYesNoDialog(project, "Close the agent session? Partial work and recorded history are retained.", "Close session", null) != com.intellij.openapi.ui.Messages.YES) return
        controller.agentRequest("sessions.close", AgentProtocol.command(id))
    }

    private fun canStart(): Boolean = current.connected && current.agentSupported && !current.baselineLoading &&
        current.agent.status !in setOf("starting", "authenticating", "authenticationRequired", "running", "ready", "configuring", "cancelling", "closing") &&
        submitting?.isActive != true && pendingStart == null && pendingDetectionStart == null && !resolvingAdapter

    private fun updateSubmitControls() {
        val draft = prompt.text.isNotBlank()
        start.isEnabled = draft && canStart()
        send.isEnabled = draft && current.connected && current.agent.status == "ready" && submitting?.isActive != true
        enqueue.isVisible = current.agent.status == "running" && draft
        enqueue.isEnabled = draft && current.connected && current.agent.status in setOf("running", "ready") && submitting?.isActive != true
    }

    private fun updateModelChoice() {
        modelChoice.icon = com.intellij.icons.AllIcons.General.ArrowDown
        val option = current.agent.configOptions.firstOrNull { it.category == "model" || it.id == "model" }
        val unavailable = current.agent.status == "ready" && option?.options.isNullOrEmpty()
        val model = if (unavailable) "Model settings" else option?.currentLabel ?: "Choose model"
        modelChoice.text = model
        modelChoice.toolTipText = if (unavailable) "This adapter has not supplied a model catalogue. Open for connection guidance." else "Model: $model · Model, speed, effort and agent settings"
        modelChoice.isEnabled = current.connected && current.agentSupported && (current.agent.status == "ready" || canStart())
        val modes = current.agent.configOptions.filter { it.isMode }
        modeChoice.text = modes.joinToString(" · ") { it.currentLabel }
        modeChoice.icon = AllIcons.General.ArrowDown
        modeChoice.isVisible = modes.isNotEmpty()
        modeChoice.isEnabled = current.connected && current.agent.status == "ready"
        modeChoice.toolTipText = modes.joinToString(" · ") { "${it.name}: ${it.currentLabel}" }
        modeChoice.accessibleContext.accessibleDescription = modeChoice.toolTipText
    }

    private fun openAgentConfiguration(modeOnly: Boolean = false) {
        if (current.agent.status != "ready") {
            openConfigurationWhenReady = true
            startSession(connectOnly = true)
            return
        }
        val sessionId = current.agent.id
        AgentConfigurationPopup.show(react ?: if (modeOnly) modeChoice else modelChoice, current.agent.configOptions, provider(), modeOnly, openSettings) { option, value ->
            if (current.connected && current.agent.id == sessionId && current.agent.status == "ready" && option in current.agent.configOptions && value != option.currentValue) {
                sessionId?.let { controller.agentRequest("sessions.configure", AgentProtocol.configure(it, option, value)) }
            }
        }
    }

    private fun provider(): String {
        val chosen = current.agent.agentName.takeUnless { profile.isEnabled }.orEmpty().ifBlank { profile.selectedItem?.toString().orEmpty() }
        return if (chosen == "automatic") localAgents.firstOrNull { it.executable == executable.text.trim() && gson.toJson(it.arguments) == arguments.text }?.profile ?: chosen else chosen
    }

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
                    detectionJob = null
                    val waiting = pendingDetectionStart
                    pendingDetectionStart = null
                    update(current)
                    if (waiting != null) {
                        javax.swing.SwingUtilities.invokeLater {
                            if (!disposed && current.connected) startSession(waiting.connectOnly, original = waiting)
                        }
                    }
                }
            }
        }
    }

    private fun applyDetected(force: Boolean) {
        val found = localAgents.firstOrNull { profile.selectedItem == "automatic" || it.profile == profile.selectedItem }
        detection.text = found?.description ?: "Install Cursor CLI, or Codex/Claude Code with Node.js 22+, then detect again. You can also choose an ACP adapter executable manually."
        connect.toolTipText = detection.text
        if (found != null && (force || executable.text.isBlank())) {
            executable.text = found.executable
            arguments.text = gson.toJson(found.arguments)
        }
    }

    override fun dispose() {
        disposed = true
        webRefresh.stop()
        enhancementReview?.dispose()
        react?.let { com.intellij.openapi.util.Disposer.dispose(it) }
        react = null
        detectionJob?.cancel()
        composerMenu.isVisible = false
        submitting?.cancel()
        promptContext.dispose()
        conversation.dispose()
        chatChanges.dispose()
        images.dispose()
        promptQueue.dispose()
    }

    private fun updateWorkspaceTitle() {
        promptContext.setWorkspaceTitle(
            when {
                sourceSessionId != null -> "Workspace · retained"
                overlay != null -> "Workspace · local baseline"
                commit.text.isNotBlank() -> "Workspace · ${commit.text.take(8)}"
                else -> "Context · current commit"
            },
        )
    }

    fun newConversation(source: String?) {
        pendingStart = null
        pendingDetectionStart = null
        pendingImageSend = null
        queuedAttempt = null
        rewindTurnId = null
        images.clear()
        openConfigurationWhenReady = false
        newDraft = true
        conversationTitle = "New conversation"
        conversation.update(emptyList(), null)
        overlay = null
        provenance = null
        sourceSessionId = source
        prompt.text = ""
        promptContext.clear()
        stopped.isSelected = false
        stopped.isVisible = source != null
        clean.isVisible = source != null
        commit.isEnabled = source == null
        commit.text = ""
        sourceLabel.text = source?.let { "Retained workspace: $it (new conversation)" } ?: "New conversation from a clean commit"
        sourceLabel.toolTipText = sourceLabel.text
        update(current)
    }

    fun update(view: EngineView) {
        current = view
        val turn = view.agent.id to (view.agent.events.lastOrNull { it.kind == "turn.started" } ?: view.agent.events.lastOrNull { it.turnId != null })?.turnId
        if (view.connected && view.agent.status in setOf("running", "cancelling")) {
            if (activityTurn != turn || activityStartedAt == null) activityStartedAt = System.currentTimeMillis()
            activityTurn = turn
        } else {
            activityTurn = null
            activityStartedAt = null
        }
        promptContext.update(view)
        if (!view.connected) {
            stopped.isSelected = false
            pendingImageSend = null
            pendingStart = null
            pendingDetectionStart = null
            openConfigurationWhenReady = false
        }
        val agent = view.agent
        promptQueue.update(agent, view.connected)
        pendingRewind?.takeIf { agent.status in setOf("closed", "interrupted") }?.let { (source, event) ->
            pendingRewind = null
            beginRewind(source, event)
            return
        }
        if (agent.status == "failed") pendingRewind = null
        if (agent.status == "ready") {
            pendingImageSend?.let { frozen ->
                pendingImageSend = null
                if (frozen == (promptRevision.version to images.revision)) {
                    if (agent.supportsImages || !images.hasInlineImages) {
                        javax.swing.SwingUtilities.invokeLater { if (!disposed && current.agent.id == agent.id && current.agent.status == "ready") sendPrompt() }
                    } else {
                        controller.showActionError("This adapter does not support image prompts. Your draft and images are retained.")
                    }
                }
            }
        }
        conversation.setFontOffset(settings.state.conversationFontOffset)
        commandsMenu.isEnabled = view.connected && agent.id != null && agent.status == "ready" && agent.availableCommands.isNotEmpty()
        val definitions = agent.id to agent.availableCommands
        if (commandsMenu.getClientProperty("flow.commands") != definitions) {
            commandsMenu.putClientProperty("flow.commands", definitions)
            commandsMenu.removeAll()
            agent.availableCommands.forEach { command ->
                commandsMenu.add(
                    javax.swing.JMenuItem("/${command.name}").apply {
                        putClientProperty("html.disable", true)
                        // A plain prefix prevents provider text from enabling Swing HTML tooltips.
                        toolTipText = "Agent command · " + command.description + if (command.inputHint.isBlank()) "" else " · ${command.inputHint}"
                        addActionListener {
                            if (current.connected && current.agent.id == agent.id && current.agent.status == "ready" && command in current.agent.availableCommands) {
                                prompt.replaceSelection("/${command.name} ")
                                prompt.requestFocusInWindow()
                            }
                        }
                    },
                )
            }
        }
        if (agent.status == "failed" || view.actionError.isNotBlank()) pendingNewConversation = false
        if (pendingNewConversation && agent.status in listOf("closed", "interrupted")) {
            pendingNewConversation = false
            newConversation(null)
            return
        }
        if (agent.id != displayedSession && agent.id != null && agent.status !in listOf("closed", "failed", "interrupted")) {
            displayedSession = agent.id
            newDraft = false
            conversationTitle = "New conversation"
        }
        if (!newDraft) {
            agent.events.firstOrNull { it.kind == "turn.started" }?.text?.lineSequence()?.firstOrNull()?.trim()?.takeIf(String::isNotBlank)?.let {
                if (conversationTitle == "New conversation") conversationTitle = it.take(72)
            }
        }
        connectedProvider = if (agent.id != null && agent.status !in listOf("closed", "failed")) AgentSelection.provider(agent.agentName) else null
        conversation.setProvider(connectedProvider ?: provider())
        profile.repaint()
        val sessionStatus = if (!view.connected) {
            "Engine offline"
        } else {
            when (agent.status) {
                "starting" -> when (agent.connectionStage) {
                    "workspace" -> "Preparing isolated workspace…"
                    "history" -> "Verifying workspace history…"
                    "adapter" -> "Starting agent adapter…"
                    "handshake" -> "Agent connected; configuring tools…"
                    "session" -> "Opening agent session…"
                    else -> "Connecting…"
                } + (agent.startupElapsedMs?.let { " · ${it / 1000}s" } ?: "")
                "running" -> when (activityPhase()) {
                    "responding" -> "Responding…"
                    "thinking" -> "Thinking…"
                    "tools" -> "Using tools…"
                    "approval" -> "Waiting for approval"
                    else -> "Working…"
                }
                "ready" -> "Ready for your next message"
                "configuring" -> "Updating…"
                "failed" -> "Session failed"
                "authenticationRequired" -> "Sign-in required"
                "cancelling" -> "Stopping…"
                "closed" -> "Session closed"
                "No active session", "" -> "Ready for a new task"
                else -> agent.status.replaceFirstChar { it.uppercase() }
            }
        }
        sessionDiagnostics.text = listOf(sessionStatus, agent.workspace, agent.usage?.let { "Context: ${it.used} / ${it.size} tokens" }).filterNotNull().filter(String::isNotBlank).joinToString("\n")
        composerStatus.text = when {
            pendingDetectionStart != null -> "Finding your agent…"
            pendingStart != null -> "Preparing your workspace…"
            else -> when (agent.status) {
                "starting", "authenticating" -> "Connecting…"
                "running" -> sessionStatus
                "cancelling", "closing" -> "Stopping…"
                else -> "Updating…"
            }
        }
        composerStatus.isVisible = pendingDetectionStart != null || pendingStart != null || agent.status in setOf("starting", "authenticating", "configuring", "running", "cancelling", "closing")
        profile.toolTipText = "$sessionStatus · ${agent.recordedOperations} recorded operations${agent.workspace?.let { " · $it" }.orEmpty()}"
        contextDetails.text = view.contextStatus
        compatibility.text = (listOf(agent.compatibility, agent.startupTimings.entries.joinToString(" · ") { "${it.key}: ${it.value} ms" }) + agent.events.filterNot { ConversationView.isMessage(it) }.map { it.text }).filter(String::isNotBlank).distinct().joinToString("\n\n").ifBlank { "Native login stays with the selected adapter. Read-only MCP context is attached automatically; files use ACP resources; inline images and audio follow adapter capabilities. Rewind creates a new workspace and conversation." }
        val methods = (0 until authMethods.itemCount).map { authMethods.getItemAt(it) }
        if (methods != agent.authMethods) {
            authMethods.removeAllItems()
            agent.authMethods.forEach { authMethods.addItem(it) }
        }
        authenticate.isEnabled = view.connected && agent.status == "authenticationRequired" && agent.authMethods.isNotEmpty()
        authMethods.isEnabled = authenticate.isEnabled
        chatChanges.update(if (newDraft) dev.flow.bridge.AgentView() else agent, view.connected)
        if (react == null) conversation.update(if (newDraft) emptyList() else agent.events, agent.id)
        error.text = listOf(
            view.actionError,
            agent.configurationError,
            if (agent.status == "failed") "Agent session failed. Open Settings for details." else "",
        ).filter(String::isNotBlank).joinToString("\n")
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
                startSession(pending.connectOnly, original = pending)
            }
        }
        if (view.actionError.isNotBlank()) pendingStart = null
        val connected = view.connected && view.agentSupported
        val active = agent.status in listOf("starting", "authenticating", "authenticationRequired", "running", "ready", "configuring", "cancelling", "closing")
        updateSubmitControls()
        attachFiles.isEnabled = connected
        cancel.isEnabled = connected && agent.status == "running"
        close.isEnabled = connected && active
        profile.isEnabled = !active
        modelField.isEnabled = !active && provider() !in setOf("claude", "cursor")
        updateModelChoice()
        baseline.isEnabled = connected && !view.baselineLoading && sourceSessionId == null
        selectOverlay.isEnabled = project != null && connected && sourceSessionId == null
        baseline.text = if (view.baselineLoading) "Reading current commit…" else "Use current commit"
        connect.isVisible = connected && !active
        connect.isEnabled = canStart()
        start.isVisible = !active
        cancel.isVisible = agent.status in listOf("running", "cancelling")
        send.isVisible = active && !cancel.isVisible
        close.isVisible = active
        updateWorkspaceTitle()
        permissionScroll.isVisible = agent.permissions.isNotEmpty() || authenticate.isEnabled
        if (openConfigurationWhenReady && agent.status == "ready") {
            openConfigurationWhenReady = false
            javax.swing.SwingUtilities.invokeLater { if (!disposed && (modelChoice.isShowing || react?.isShowing == true)) openAgentConfiguration() }
        }
        if (agent.status in listOf("failed", "closed", "interrupted")) openConfigurationWhenReady = false
        if (view.actionError.isNotBlank()) renderedPermission = null
        react?.update()
        if (renderedPermission == agent.permissions.firstOrNull() && renderedAuthentication == authenticate.isEnabled) return
        renderedAuthentication = authenticate.isEnabled
        renderedPermission = agent.permissions.firstOrNull()
        permission.removeAll()
        agent.permissions.firstOrNull()?.let { request ->
            val headline = request.title.lineSequence().firstOrNull().orEmpty()
            val raw = request.title.substringAfter('\n', "")
            val input = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull()
            val summary = listOf("command", "file_path", "path", "description").mapNotNull { key -> input?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.take(512) }.distinct().joinToString("\n")
            val description = JBTextArea(listOf(headline, summary).filter(String::isNotBlank).joinToString("\n"), 2, 0).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            permission.border = com.intellij.util.ui.JBUI.Borders.compound(com.intellij.util.ui.JBUI.Borders.customLine(com.intellij.ui.JBColor.border()), com.intellij.util.ui.JBUI.Borders.empty(8))
            permission.add(JBLabel("Approval required").apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.NORTH)
            permission.add(FlowUi.scroll(FlowUi.column(description, Disclosure("Request details", FlowUi.note(request.title + "\nTool: " + request.tool))), 72), BorderLayout.CENTER)
            val choices = FlowUi.actions()
            fun choose(option: String?) {
                if (current.agent.id != agent.id || current.agent.permissions.none { it.id == request.id }) return
                choices.components.filterIsInstance<javax.swing.AbstractButton>().forEach { it.isEnabled = false }
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
