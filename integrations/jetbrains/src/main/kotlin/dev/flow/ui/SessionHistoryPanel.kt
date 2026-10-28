package dev.flow.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import dev.flow.bridge.AgentProtocol
import dev.flow.bridge.EngineController
import dev.flow.bridge.EngineView
import dev.flow.bridge.HistorySelection
import dev.flow.bridge.optionalText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import javax.swing.AbstractAction
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/** Native review of Rust-owned history. Only the explicit current-file action reads live files. */
internal class SessionHistoryPanel(
    private val project: Project,
    private val controller: EngineController,
    parentScope: CoroutineScope,
    private val historyRequest: suspend (String, JsonObject) -> JsonElement = controller::historyRequest,
    private val presentDiff: (SimpleDiffRequest) -> Unit = { request -> DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT) },
    private val reviewSession: (String) -> Unit = {},
    private val openConversation: (String) -> Unit = {},
    private val newConversation: (String) -> Unit,
) : JBPanel<SessionHistoryPanel>(BorderLayout()),
    Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.EDT)
    private data class Row(val value: JsonObject, val label: String) {
        override fun toString() = label
    }
    private fun historyList(name: String) = JBList<Row>().apply {
        cellRenderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = name
        emptyText.text = name
    }
    private val sessions = historyList("Sessions")
    private val turns = historyList("Turns in the selected session")
    private val operations = historyList("Recorded operations")
    private val files = Tree(DefaultMutableTreeNode("Files")).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = DefaultTreeCellRenderer().apply { putClientProperty("html.disable", true) }
        accessibleContext.accessibleName = "Files in the selected operation"
    }
    private fun note(rows: Int) = JBTextArea(rows, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = javax.swing.UIManager.getFont("Label.font")
        (caret as javax.swing.text.DefaultCaret).updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
    }
    private val message = note(3)
    private val coverage = note(2)
    private val refresh = JButton("Refresh sessions", AllIcons.Actions.Refresh)
    private val moreSessions = JButton("Next sessions", AllIcons.Actions.Forward)
    private val firstTurns = JButton("First turns", AllIcons.Actions.Back)
    private val moreTurns = JButton("Next turns", AllIcons.Actions.Forward)
    private val allOperations = JButton("All operations", AllIcons.Actions.ListChanges)
    private val firstOperations = JButton("First operations", AllIcons.Actions.Back)
    private val moreOperations = JButton("Next operations", AllIcons.Actions.Forward)
    private val firstFiles = JButton("First files", AllIcons.Actions.Back)
    private val moreFiles = JButton("Next files", AllIcons.Actions.Forward)
    private val archive = JButton("Archive", AllIcons.Nodes.Folder)
    private val continueSession = JButton("New conversation…", AllIcons.General.Add)
    private val reviewResult = JButton("Review result", AllIcons.Actions.ListChanges)
    private val readConversation = JButton("Read conversation", AllIcons.General.Balloon)
    private val search = com.intellij.ui.components.JBTextField().apply {
        emptyText.text = "Search titles and conversation text"
        accessibleContext.accessibleName = "Search session history"
    }
    private val favoritesOnly = javax.swing.JCheckBox("Favorites only")
    private val favorite = JButton("Favorite", AllIcons.Nodes.Favorite)
    private val rename = JButton("Rename…", AllIcons.Actions.Edit)
    private val diff = JButton("Open historical diff", AllIcons.Actions.Diff)
    private val currentFile = JButton("Open current file", AllIcons.Actions.EditSource)
    private var sessionCursor: String? = null
    private var submittedSearch: Pair<String, Boolean>? = null
    private lateinit var historyScroll: com.intellij.ui.components.JBScrollPane
    private var turnCursor: String? = null
    private var operationCursor: String? = null
    private var fileCursor: String? = null
    private var connected = false
    private var rendering = false

    // A descendant selection must not cancel its still-loading ancestor page.
    private val tasks = arrayOfNulls<Job>(5)
    private var selectedSessionId: String? = null
    private var selectedTurnId: String? = null
    private var selectedOperationId: String? = null

    init {
        border = com.intellij.util.ui.JBUI.Borders.empty(8)
        fun section(title: String, component: JComponent, height: Int, vararg controls: JButton) = FlowUi.column(
            JBLabel(title).apply {
                labelFor = component
                font = font.deriveFont(java.awt.Font.BOLD)
            },
            FlowUi.scroll(component, height),
            FlowUi.actions(*controls),
        )
        sessions.visibleRowCount = 4
        turns.visibleRowCount = 3
        operations.visibleRowCount = 4
        add(
            FlowUi.scroll(
                FlowUi.column(
                    FlowUi.heading("Session history", "Browse recorded work and compare exact file versions."),
                    search,
                    FlowUi.actions(JButton("Search", AllIcons.Actions.Search).apply { addActionListener { loadSessions(null) } }, favoritesOnly),
                    section("Sessions", sessions, 125, refresh, moreSessions),
                    FlowUi.actions(readConversation, favorite, rename),
                    FlowUi.actions(FlowUi.primary(reviewResult), continueSession, archive),
                    Disclosure("Filter by turn", section("Turns", turns, 90, firstTurns, moreTurns, allOperations)),
                    section("Operations", operations, 125, firstOperations, moreOperations),
                    section("Recorded files", files, 160, firstFiles, moreFiles),
                    FlowUi.actions(FlowUi.primary(diff), currentFile),
                    coverage, message,
                ),
            ).also { historyScroll = it },
            BorderLayout.CENTER,
        )
        refresh.addActionListener { loadSessions(null) }
        search.addActionListener { loadSessions(null) }
        favoritesOnly.addActionListener { loadSessions(null) }
        readConversation.addActionListener { sessions.selectedValue?.let { openConversation(it.value["id"].asString) } }
        favorite.addActionListener {
            val selected = sessions.selectedValue?.value ?: return@addActionListener
            runRequest(0) {
                historyRequest(
                    "sessions.label",
                    AgentProtocol.command(selected["id"].asString).apply {
                        remove("prompt")
                        addProperty("favorite", selected["favorite"]?.asBoolean != true)
                    },
                )
                loadSessions(null)
            }
        }
        rename.addActionListener {
            val selected = sessions.selectedValue?.value ?: return@addActionListener
            val title = com.intellij.openapi.ui.Messages.showInputDialog(project, "Conversation title (up to 120 characters; empty resets it)", "Rename conversation", null, selected.optionalText("title").orEmpty(), null) ?: return@addActionListener
            if (title.codePointCount(0, title.length) > 120 || title.any(Char::isISOControl)) {
                message.text = "Use at most 120 characters without control characters."
                return@addActionListener
            }
            runRequest(0) {
                historyRequest(
                    "sessions.label",
                    JsonObject().apply {
                        addProperty("sessionId", selected["id"].asString)
                        addProperty("title", title)
                    },
                )
                loadSessions(null)
            }
        }
        moreSessions.addActionListener { sessionCursor?.let(::loadSessions) }
        firstTurns.addActionListener { loadTurns(null) }
        moreTurns.addActionListener { turnCursor?.let(::loadTurns) }
        allOperations.addActionListener {
            rendering = true
            selectedTurnId = null
            turns.clearSelection()
            rendering = false
            loadOperations(null)
        }
        firstOperations.addActionListener { loadOperations(null) }
        moreOperations.addActionListener { operationCursor?.let(::loadOperations) }
        firstFiles.addActionListener { loadFiles(null) }
        moreFiles.addActionListener { fileCursor?.let(::loadFiles) }
        sessions.addListSelectionListener {
            val id = sessions.selectedValue?.value?.get("id")?.asString
            if (!rendering && !it.valueIsAdjusting && id != selectedSessionId) {
                selectedSessionId = id
                loadTurns(null)
            }
        }
        turns.addListSelectionListener {
            val id = turns.selectedValue?.value?.get("id")?.asString
            if (!rendering && !it.valueIsAdjusting && id != selectedTurnId) {
                selectedTurnId = id
                loadOperations(null)
            }
        }
        operations.addListSelectionListener {
            val id = operations.selectedValue?.value?.get("id")?.asString
            if (!rendering && !it.valueIsAdjusting && id != selectedOperationId) {
                selectedOperationId = id
                loadFiles(null)
            }
        }
        files.addTreeSelectionListener {
            if (!rendering) {
                // Model replacement can deliver selection clearing after the
                // rendering guard ends. It must not cancel a pending page load.
                cancelFrom(4)
                buttons()
            }
        }
        archive.addActionListener {
            val selected = sessions.selectedValue ?: return@addActionListener
            runRequest(0) {
                historyRequest("sessions.archive", AgentProtocol.command(selected.value["id"].asString))
                loadSessions(null)
            }
        }
        continueSession.addActionListener { sessions.selectedValue?.let { newConversation(it.value["id"].asString) } }
        reviewResult.addActionListener { sessions.selectedValue?.let { reviewSession(it.value["id"].asString) } }
        diff.addActionListener { openDiff() }
        currentFile.addActionListener { openCurrentFile() }
        files.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "flow.openHistory")
        files.actionMap.put(
            "flow.openHistory",
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    openDiff()
                }
            },
        )
        files.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && selectedFile() != null) openDiff()
            }
        })
        message.accessibleContext.accessibleName = "History action result"
        coverage.accessibleContext.accessibleName = "Capture coverage warnings"
        buttons()
    }
    private fun cancelFrom(level: Int) {
        for (index in level..4) {
            tasks[index]?.cancel()
            tasks[index] = null
        }
    }
    private fun runRequest(level: Int, block: suspend () -> Unit) {
        cancelFrom(level)
        tasks[level] = scope.launch(start = CoroutineStart.LAZY) {
            try {
                message.text = "Loading…"
                block()
            } catch (cancelled: CancellationException) {
                if (tasks[level] == coroutineContext[Job]) message.text = "Selection changed. Choose a recorded item to continue."
                throw cancelled
            } catch (_: Exception) {
                if (tasks[level] == coroutineContext[Job]) message.text = "History unavailable, content missing/corrupt, or selection changed. No live content was substituted. Refresh and retry; close/recover sessions before archiving."
                if (level == 0) resetScrollTop()
            }
        }
        tasks[level]!!.start()
    }
    private fun params(after: String? = null) = JsonObject().apply {
        sessions.selectedValue?.let { addProperty("sessionId", it.value["id"].asString) }
        turns.selectedValue?.let { addProperty("turnId", it.value["id"].asString) }
        operations.selectedValue?.let { addProperty("operationId", it.value["id"].asString) }
        after?.let { addProperty("after", it) }
    }
    private fun rows(page: JsonObject, label: (JsonObject) -> String): Array<Row> {
        val items = page.getAsJsonArray("items")
        require(items.size() <= 20)
        return items.map { Row(it.asJsonObject, label(it.asJsonObject)) }.toTypedArray()
    }
    private fun replaceRows(list: JBList<Row>, rows: Array<Row>) {
        rendering = true
        try {
            when (list) {
                sessions -> selectedSessionId = null
                turns -> selectedTurnId = null
                operations -> selectedOperationId = null
            }
            list.setListData(rows)
        } finally {
            rendering = false
        }
    }
    private fun replaceFiles(rows: Array<Row>) {
        rendering = true
        try {
            val root = DefaultMutableTreeNode("Files")
            val folders = linkedMapOf<String, DefaultMutableTreeNode>()
            rows.forEach { row ->
                val path = row.value.optionalText("newPath") ?: row.value.optionalText("oldPath") ?: "Unknown path"
                val folder = path.substringBeforeLast('/', "(root)")
                val node = folders.getOrPut(folder) { DefaultMutableTreeNode(folder).also { root.add(it) } }
                node.add(DefaultMutableTreeNode(row, false))
            }
            files.model = DefaultTreeModel(root)
            var row = 0
            while (row < files.rowCount) {
                files.expandRow(row)
                row++
            }
        } finally {
            rendering = false
        }
    }
    private fun clearOperations() {
        replaceRows(operations, emptyArray())
        operationCursor = null
        replaceFiles(emptyArray())
        fileCursor = null
    }
    private fun gap(page: JsonObject) {
        coverage.text = if (page["captureGap"]?.asBoolean == true) "Capture gaps were reported. Later checkpoints do not reconstruct unobserved writes; inspect recorded intervals before review." else "History contains observed checkpoints. Independent reversibility and writer shutdown are not implied."
    }
    private fun time(row: JsonObject, field: String) = runCatching { java.time.format.DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(row[field].asString.toLong())) }.getOrDefault("Unknown time")
    private fun loadSessions(after: String?) {
        if (!connected) return
        val filter = search.text.trim() to favoritesOnly.isSelected
        val cursor = after.takeIf { filter == submittedSearch }
        submittedSearch = filter
        cancelFrom(0)
        replaceRows(sessions, emptyArray())
        replaceRows(turns, emptyArray())
        clearOperations()
        sessionCursor = null
        turnCursor = null
        coverage.text = ""
        buttons()
        runRequest(0) {
            require(filter.first.codePointCount(0, filter.first.length) <= 256) { "Search is limited to 256 characters." }
            val page = historyRequest(
                "sessions.search",
                JsonObject().apply {
                    cursor?.let { addProperty("after", it) }
                    addProperty("query", filter.first)
                    addProperty("favoritesOnly", filter.second)
                },
            ).asJsonObject
            sessionCursor = page.optionalText("next")
            replaceRows(sessions, rows(page) { "${if (it["favorite"]?.asBoolean == true) "★ " else ""}${it.optionalText("title").orEmpty().ifBlank { it["id"].asString.takeLast(6) }} — ${it["status"].asString} — ${time(it, "createdAtMs")}" })
            message.text = "Select a session. History never resumes an agent. Next replaces this page; Refresh returns to the first page."
            buttons()
            resetScrollTop()
        }
    }
    private fun resetScrollTop() {
        // A replaced list/status document can ask its scroll parent to reveal
        // the caret/first row. Fresh session pages should open at their heading.
        javax.swing.SwingUtilities.invokeLater {
            if (scope.coroutineContext[Job]?.isActive == true && selectedSessionId == null) historyScroll.verticalScrollBar.value = 0
        }
    }
    fun refreshSessions() {
        loadSessions(null)
        resetScrollTop()
    }
    private fun loadTurns(after: String?) {
        cancelFrom(1)
        replaceRows(turns, emptyArray())
        clearOperations()
        turnCursor = null
        coverage.text = ""
        buttons()
        if (!connected || sessions.selectedValue == null) return
        val query = params(after)
        runRequest(1) {
            val page = historyRequest("history.turns", query).asJsonObject
            turnCursor = page.optionalText("next")
            gap(page)
            replaceRows(turns, rows(page) { "${it["status"].asString} — ${time(it, "createdAtMs")} — ${it["id"].asString.takeLast(6)}" })
            message.text = "Select a turn, or All operations to review the session's complete observation sequence."
            buttons()
        }
    }
    private fun loadOperations(after: String?) {
        cancelFrom(2)
        clearOperations()
        buttons()
        if (!connected || sessions.selectedValue == null) return
        val query = params(after)
        runRequest(2) {
            val page = historyRequest("history.operations", query).asJsonObject
            operationCursor = page.optionalText("next")
            gap(page)
            replaceRows(
                operations,
                rows(page) {
                    "${if (it["complete"].asBoolean) "" else "INCOMPLETE — "}${it["status"].asString} / ${it["attribution"].asString} — ${time(it, "completedAtMs")} — ${it["id"].asString.takeLast(6)}"
                },
            )
            message.text = "Select an observation to inspect its files. Status and attribution describe what was recorded, not whether it can be applied independently."
            buttons()
        }
    }
    private fun loadFiles(after: String?) {
        cancelFrom(3)
        replaceFiles(emptyArray())
        fileCursor = null
        buttons()
        val operation = operations.selectedValue ?: return
        if (!connected) return
        val query = params(after)
        runRequest(3) {
            val page = historyRequest("history.files", query).asJsonObject
            fileCursor = page.optionalText("next")
            replaceFiles(
                rows(page) {
                    val old = it.optionalText("oldPath")
                    val new = it.optionalText("newPath")
                    val path = if (old != null && new != null && old != new) "$old → $new" else (new ?: old ?: "Unknown").substringAfterLast('/')
                    "${it["kind"].asString}: $path"
                },
            )
            message.text = "${operation.value["evidence"].asString}\nExcluded entries: ${operation.value["excludedBefore"]} before / ${operation.value["excludedAfter"]} after. Enter or double-click a file for its historical diff."
            buttons()
        }
    }
    private fun selectedFile() = (files.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Row
    private fun selection(): HistorySelection? {
        val session = sessions.selectedValue ?: return null
        val operation = operations.selectedValue ?: return null
        val file = selectedFile() ?: return null
        return HistorySelection.from(session.value["id"].asString, operation.value["id"].asString, file.value)
    }
    private fun openDiff() {
        if (!connected) return
        val selected = selection() ?: return
        runRequest(4) {
            val result = historyRequest("history.diff", selected.query()).asJsonObject
            if (selection() != selected) return@runRequest
            val spec = selected.diff(result)
            message.text = "${result["beforeNote"].asString}; ${result["afterNote"].asString}. Modes: ${result.optionalText("beforeMode") ?: "absent"} → ${result.optionalText("afterMode") ?: "absent"}"
            if (spec != null) {
                val factory = DiffContentFactory.getInstance()
                val request = SimpleDiffRequest(spec.title, factory.create(project, spec.before), factory.create(project, spec.after), spec.beforeTitle, spec.afterTitle)
                request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
                request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, true))
                request.putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true)
                request.putUserData(HISTORY_IDENTITY, spec.identity)
                presentDiff(request)
            }
        }
    }
    private fun openCurrentFile() {
        if (!connected) return
        val selected = selection() ?: return
        runRequest(4) {
            val result = historyRequest("history.location", selected.query()).asJsonObject
            if (selection() != selected) return@runRequest
            message.text = result["note"].asString
            val path = result.optionalText("path") ?: return@runRequest
            val file = withContext(Dispatchers.IO) {
                val target = Path.of(path)
                val root = Path.of(result["workspace"].asString)
                require(target.isAbsolute && target.startsWith(root) && target != root)
                // Recheck after IPC; never follow a redirected location into another tree.
                require(target.toRealPath() == target && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            }
            if (file == null) {
                message.text = "Current file disappeared or moved. Historical content remains available."
            } else {
                OpenFileDescriptor(project, file).navigate(true)
            }
        }
    }
    private fun buttons() {
        val session = connected && sessions.selectedValue != null
        val operation = session && operations.selectedValue != null
        refresh.isEnabled = connected
        readConversation.isEnabled = session
        favorite.isEnabled = session
        favorite.text = if (sessions.selectedValue?.value?.get("favorite")?.asBoolean == true) "Unfavorite" else "Favorite"
        rename.isEnabled = session
        moreSessions.isEnabled = connected && sessionCursor != null
        firstTurns.isEnabled = session
        moreTurns.isEnabled = session && turnCursor != null
        allOperations.isEnabled = session
        firstOperations.isEnabled = session
        moreOperations.isEnabled = session && operationCursor != null
        firstFiles.isEnabled = operation
        moreFiles.isEnabled = operation && fileCursor != null
        archive.isEnabled = session && sessions.selectedValue?.value?.get("status")?.asString == "closed"
        reviewResult.isEnabled = session && sessions.selectedValue?.value?.get("status")?.asString == "closed"
        continueSession.isEnabled = session && sessions.selectedValue?.value?.get("status")?.asString in listOf("closed", "archived")
        diff.isEnabled = operation && selectedFile() != null
        currentFile.isEnabled = diff.isEnabled
    }
    fun update(view: EngineView) {
        val wasConnected = connected
        connected = view.connected
        if (!connected && wasConnected) {
            cancelFrom(0)
            replaceRows(sessions, emptyArray())
            replaceRows(turns, emptyArray())
            clearOperations()
            sessionCursor = null
            turnCursor = null
            coverage.text = ""
        }
        if (connected && !wasConnected) loadSessions(null)
        buttons()
    }
    override fun dispose() {
        scope.cancel()
    }
    private companion object {
        val HISTORY_IDENTITY = Key.create<String>("flow.history.identity")
    }
}
