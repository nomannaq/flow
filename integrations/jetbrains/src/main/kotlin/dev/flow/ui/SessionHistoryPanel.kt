package dev.flow.ui

import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import dev.flow.bridge.AgentProtocol
import dev.flow.bridge.EngineController
import dev.flow.bridge.EngineView
import dev.flow.bridge.HistorySelection
import dev.flow.bridge.optionalText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.GridLayout
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
import javax.swing.JPanel
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
    private val presentDiff: (SimpleDiffRequest) -> Unit = { request -> DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT) },
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
    }
    private val message = note(3)
    private val coverage = note(2)
    private val refresh = JButton("Refresh sessions")
    private val moreSessions = JButton("Next sessions")
    private val firstTurns = JButton("First turns")
    private val moreTurns = JButton("Next turns")
    private val allOperations = JButton("All operations")
    private val firstOperations = JButton("First operations")
    private val moreOperations = JButton("Next operations")
    private val firstFiles = JButton("First files")
    private val moreFiles = JButton("Next files")
    private val archive = JButton("Archive")
    private val continueSession = JButton("New conversation…")
    private val diff = JButton("Open historical diff")
    private val currentFile = JButton("Open CURRENT workspace file")
    private var sessionCursor: String? = null
    private var turnCursor: String? = null
    private var operationCursor: String? = null
    private var fileCursor: String? = null
    private var connected = false
    private var rendering = false
    private var task: Job? = null

    init {
        fun section(title: String, component: JComponent, vararg controls: JButton) = JPanel(BorderLayout()).apply {
            add(JBLabel(title).apply { labelFor = component }, BorderLayout.NORTH)
            add(JBScrollPane(component), BorderLayout.CENTER)
            add(JPanel(GridLayout(0, 2, 4, 4)).apply { controls.forEach { add(it) } }, BorderLayout.SOUTH)
        }
        add(
            JPanel(GridLayout(4, 1, 0, 6)).apply {
                add(section("Sessions", sessions, refresh, moreSessions, archive, continueSession))
                add(section("Turns (creation order)", turns, firstTurns, moreTurns, allOperations))
                add(section("Operations (observation order)", operations, firstOperations, moreOperations))
                add(section("Recorded files — folders group this page", files, firstFiles, moreFiles, diff, currentFile))
            },
            BorderLayout.CENTER,
        )
        add(
            JPanel(BorderLayout()).apply {
                add(JBScrollPane(coverage), BorderLayout.NORTH)
                add(JBScrollPane(message), BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
        refresh.addActionListener { loadSessions(null) }
        moreSessions.addActionListener { sessionCursor?.let(::loadSessions) }
        firstTurns.addActionListener { loadTurns(null) }
        moreTurns.addActionListener { turnCursor?.let(::loadTurns) }
        allOperations.addActionListener {
            rendering = true
            turns.clearSelection()
            rendering = false
            loadOperations(null)
        }
        firstOperations.addActionListener { loadOperations(null) }
        moreOperations.addActionListener { operationCursor?.let(::loadOperations) }
        firstFiles.addActionListener { loadFiles(null) }
        moreFiles.addActionListener { fileCursor?.let(::loadFiles) }
        sessions.addListSelectionListener { if (!rendering && !it.valueIsAdjusting) loadTurns(null) }
        turns.addListSelectionListener { if (!rendering && !it.valueIsAdjusting) loadOperations(null) }
        operations.addListSelectionListener { if (!rendering && !it.valueIsAdjusting) loadFiles(null) }
        files.addTreeSelectionListener {
            if (!rendering) {
                task?.cancel()
                buttons()
            }
        }
        archive.addActionListener {
            val selected = sessions.selectedValue ?: return@addActionListener
            runRequest {
                controller.historyRequest("sessions.archive", AgentProtocol.command(selected.value["id"].asString))
                loadSessions(null)
            }
        }
        continueSession.addActionListener { sessions.selectedValue?.let { newConversation(it.value["id"].asString) } }
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
    private fun runRequest(block: suspend () -> Unit) {
        task?.cancel()
        task = scope.launch {
            try {
                message.text = "Loading…"
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.text = "History unavailable, content missing/corrupt, or selection changed. No live content was substituted. Refresh and retry; close/recover sessions before archiving."
            }
        }
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
    private fun time(row: JsonObject, field: String) = runCatching { Instant.ofEpochMilli(row[field].asString.toLong()).toString() }.getOrDefault("Unknown time")
    private fun loadSessions(after: String?) {
        if (!connected) return
        replaceRows(sessions, emptyArray())
        replaceRows(turns, emptyArray())
        clearOperations()
        sessionCursor = null
        turnCursor = null
        coverage.text = ""
        buttons()
        runRequest {
            val page = controller.historyRequest("sessions.list", JsonObject().apply { after?.let { addProperty("after", it) } }).asJsonObject
            sessionCursor = page.optionalText("next")
            replaceRows(sessions, rows(page) { "${it["status"].asString} — ${time(it, "createdAtMs")} — ${it["id"].asString}" })
            message.text = "Select a session. History never resumes an agent. Next replaces this page; Refresh returns to the first page."
            buttons()
        }
    }
    private fun loadTurns(after: String?) {
        task?.cancel()
        replaceRows(turns, emptyArray())
        clearOperations()
        turnCursor = null
        coverage.text = ""
        buttons()
        if (!connected || sessions.selectedValue == null) return
        val query = params(after)
        runRequest {
            val page = controller.historyRequest("history.turns", query).asJsonObject
            turnCursor = page.optionalText("next")
            gap(page)
            replaceRows(turns, rows(page) { "${it["status"].asString} — ${time(it, "createdAtMs")} — ${it["id"].asString}" })
            message.text = "Select a turn, or All operations to review the session's complete observation sequence."
            buttons()
        }
    }
    private fun loadOperations(after: String?) {
        task?.cancel()
        clearOperations()
        buttons()
        if (!connected || sessions.selectedValue == null) return
        val query = params(after)
        runRequest {
            val page = controller.historyRequest("history.operations", query).asJsonObject
            operationCursor = page.optionalText("next")
            gap(page)
            replaceRows(
                operations,
                rows(page) {
                    "${if (it["complete"].asBoolean) "" else "INCOMPLETE — "}${it["status"].asString} / ${it["attribution"].asString} — ${time(it, "completedAtMs")} — ${it["id"].asString}"
                },
            )
            message.text = "Select an observation to inspect its files. Status and attribution describe what was recorded, not whether it can be applied independently."
            buttons()
        }
    }
    private fun loadFiles(after: String?) {
        task?.cancel()
        replaceFiles(emptyArray())
        fileCursor = null
        buttons()
        val operation = operations.selectedValue ?: return
        if (!connected) return
        val query = params(after)
        runRequest {
            val page = controller.historyRequest("history.files", query).asJsonObject
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
        runRequest {
            val result = controller.historyRequest("history.diff", selected.query()).asJsonObject
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
        runRequest {
            val result = controller.historyRequest("history.location", selected.query()).asJsonObject
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
        moreSessions.isEnabled = connected && sessionCursor != null
        firstTurns.isEnabled = session
        moreTurns.isEnabled = session && turnCursor != null
        allOperations.isEnabled = session
        firstOperations.isEnabled = session
        moreOperations.isEnabled = session && operationCursor != null
        firstFiles.isEnabled = operation
        moreFiles.isEnabled = operation && fileCursor != null
        archive.isEnabled = session && sessions.selectedValue?.value?.get("status")?.asString == "closed"
        continueSession.isEnabled = session && sessions.selectedValue?.value?.get("status")?.asString in listOf("closed", "archived")
        diff.isEnabled = operation && selectedFile() != null
        currentFile.isEnabled = diff.isEnabled
    }
    fun update(view: EngineView) {
        val wasConnected = connected
        connected = view.connected
        if (!connected && wasConnected) {
            task?.cancel()
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
