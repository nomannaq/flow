package dev.flow.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import dev.flow.bridge.EngineController
import dev.flow.bridge.EngineView
import dev.flow.bridge.optionalText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.GridLayout
import java.util.UUID
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel

/** Whole-result decisions reference immutable Rust-owned candidates. */
internal class CandidateReviewPanel(
    private val project: Project,
    private val controller: EngineController,
    parentScope: CoroutineScope,
    private val presentDiff: (SimpleDiffRequest) -> Unit = { DiffManager.getInstance().showDiff(project, it) },
) : JBPanel<CandidateReviewPanel>(BorderLayout()),
    Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.EDT)
    private val session = JBTextField().apply { accessibleContext.accessibleName = "Candidate source session" }
    private val stopped = JCheckBox("I verified all session writers have stopped")
    private val prepare = JButton("Prepare complete result")
    private val refresh = JButton("Refresh candidate")
    private val more = JButton("Next candidate files")
    private val open = JButton("Open complete-result diff")
    private val accept = JButton("Accept complete result")
    private val reject = JButton("Reject complete result")
    private val command = JBTextField("[]").apply { accessibleContext.accessibleName = "Explicit candidate check command" }
    private val runCheck = JButton("Run explicit check (15s)")
    private val checkedVersion = JButton("Review checked version")
    private val summary = JBTextArea(5, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "Candidate review status"
    }
    private data class Row(val value: JsonObject) {
        fun side(before: Boolean): String {
            val prefix = if (before) "before" else "after"
            val path = value.optionalText(if (before) "oldPath" else "newPath") ?: return "absent"
            return "$path (mode ${value["${prefix}Mode"].asInt.toString(8)})"
        }
        override fun toString(): String {
            val old = value.optionalText("oldPath")
            val new = value.optionalText("newPath")
            val path = if (old != null && new != null && old != new) "$old → $new" else new ?: old
            return "$path — ${value["beforeBytes"]} → ${value["afterBytes"]} bytes; ${side(true)} → ${side(false)}"
        }
    }
    private val files = JBList<Row>().apply {
        accessibleContext.accessibleName = "Complete-result files"
        cellRenderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
    }
    private var candidate: JsonObject? = null
    private var next: String? = null
    private var connected = false
    private var busy = false
    private var job: Job? = null
    private var pendingCheck: Pair<String, String>? = null
    private var revision = 0L

    init {
        add(
            JPanel(GridLayout(0, 1, 4, 4)).apply {
                add(session)
                add(stopped)
                add(
                    JPanel(GridLayout(1, 2, 4, 4)).apply {
                        add(prepare)
                        add(refresh)
                    },
                )
                add(JBScrollPane(summary))
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(files), BorderLayout.CENTER)
        add(
            JPanel(GridLayout(0, 1, 4, 4)).apply {
                add(
                    JPanel(GridLayout(1, 2, 4, 4)).apply {
                        add(open)
                        add(more)
                    },
                )
                add(
                    JPanel(GridLayout(1, 2, 4, 4)).apply {
                        add(accept)
                        add(reject)
                    },
                )
                add(
                    JBTextArea("Optional check: JSON array of executable and arguments. Runs as your account in a temporary copy; commands and exit status are retained. No commands are read from repository instructions.").apply {
                        isEditable = false
                        lineWrap = true
                        wrapStyleWord = true
                    },
                )
                add(command)
                add(
                    JPanel(GridLayout(1, 2, 4, 4)).apply {
                        add(runCheck)
                        add(checkedVersion)
                    },
                )
            },
            BorderLayout.SOUTH,
        )
        stopped.addActionListener { buttons() }
        files.addListSelectionListener { buttons() }
        prepare.addActionListener {
            request {
                val value = controller.historyRequest(
                    "candidates.prepare",
                    JsonObject().apply {
                        addProperty("sessionId", session.text.trim())
                        addProperty("writersStopped", stopped.isSelected)
                    },
                ).asJsonObject
                load(value["id"].asString)
            }
        }
        refresh.addActionListener { candidate?.let { value -> request { load(value["id"].asString) } } }
        more.addActionListener { next?.let { cursor -> request { loadFiles(cursor) } } }
        open.addActionListener { openDiff() }
        for ((button, decision) in listOf(accept to true, reject to false)) {
            button.addActionListener {
                val id = candidate?.get("id")?.asString ?: return@addActionListener
                request {
                    controller.historyRequest(
                        "candidates.decide",
                        query(id).apply {
                            addProperty("accept", decision)
                            addProperty("writersStopped", stopped.isSelected)
                        },
                    )
                    load(id)
                }
            }
        }
        runCheck.addActionListener {
            val id = candidate?.get("id")?.asString ?: return@addActionListener
            request {
                val args = JsonParser.parseString(command.text).asJsonArray
                require(args.size() in 1..32 && args.all { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length <= 4096 })
                val identity = "$id/$args"
                val check = pendingCheck?.takeIf { it.first == identity }?.second ?: UUID.randomUUID().toString().also { pendingCheck = identity to it }
                val result = controller.historyRequest(
                    "candidates.check",
                    query(id).apply {
                        addProperty("checkId", check)
                        add("command", args)
                        addProperty("timeoutSeconds", 15)
                        addProperty("writersStopped", stopped.isSelected)
                    },
                ).asJsonObject
                if (result["status"].asString != "running") pendingCheck = null
                load(result.optionalText("resultCandidateId") ?: id)
            }
        }
        checkedVersion.addActionListener {
            val id = candidate?.getAsJsonArray("checks")?.map { it.asJsonObject }?.firstNotNullOfOrNull { it.optionalText("resultCandidateId") } ?: return@addActionListener
            request { load(id) }
        }
        summary.text = "Select a closed session in History, then review its complete result. Acceptance records a review decision; applying files is a separate step."
        buttons()
    }

    fun selectSession(id: String) {
        revision++
        job?.cancel()
        busy = false
        next = null
        pendingCheck = null
        session.text = id
        candidate = null
        files.setListData(emptyArray())
        stopped.isSelected = false
        summary.text = "Confirm all writers have stopped, then prepare the complete result."
        buttons()
    }
    fun update(view: EngineView) {
        connected = view.connected
        if (!connected || view.agent.status in listOf("running", "starting", "ready", "closing", "cancelling", "authenticating")) stopped.isSelected = false
        buttons()
    }
    private fun query(id: String = requireNotNull(candidate)["id"].asString) = JsonObject().apply { addProperty("candidateId", id) }
    private fun request(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        val current = ++revision
        buttons()
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                summary.text = "Candidate request rejected or outcome unknown. Close/recover writers and refresh. A changed source or failed check requires renewed review. Retrying an unfinished check uses the same intent ID."
            } finally {
                if (current == revision) {
                    busy = false
                    buttons()
                }
            }
        }
        job!!.start()
    }
    private suspend fun load(id: String) {
        require(Regex("[a-f0-9]{64}").matches(id))
        val result = controller.historyRequest("candidates.get", query(id)).asJsonObject
        require(result["id"].asString == id)
        candidate = result
        val checks = result.getAsJsonArray("checks")
        summary.text = "Candidate $id\nBaseline commit: ${result["baseCommit"].asString}\nDecision: ${result["decision"].asString}; source generation current: ${result["sourceGenerationCurrent"].asBoolean}\n" +
            if (checks.isEmpty) "No validation commands configured. Review covers the complete frozen result; it does not apply files." else checks.joinToString("\n") { "${it.asJsonObject["status"].asString}: ${it.asJsonObject["command"]}" }
        loadFiles(null)
    }
    private suspend fun loadFiles(after: String?) {
        val result = controller.historyRequest("candidates.files", query().apply { after?.let { addProperty("after", it) } }).asJsonObject
        require(result["candidateId"].asString == requireNotNull(candidate)["id"].asString)
        val rows = result.getAsJsonArray("items")
        require(rows.size() <= 20)
        files.setListData(rows.map { Row(it.asJsonObject) }.toTypedArray())
        next = result.optionalText("next")
        if (result["total"].asInt == 0) summary.append("\nEmpty delta: baseline and complete result have identical files.")
    }
    private fun openDiff() {
        val selected = files.selectedValue?.value ?: return
        val id = candidate?.get("id")?.asString ?: return
        request {
            val value = controller.historyRequest("candidates.diff", query(id).apply { addProperty("fileIndex", selected["index"].asInt) }).asJsonObject
            require(value["candidateId"].asString == id && value["fileIndex"] == selected["index"] && value["contentIds"] == selected["contentIds"])
            val before = value.optionalText("before")
            val after = value.optionalText("after")
            if (before == null || after == null) {
                summary.append("\nBinary, large or unsupported text preview; immutable content IDs and byte counts are retained.")
            } else {
                val factory = DiffContentFactory.getInstance()
                val path = selected.optionalText("newPath") ?: selected.optionalText("oldPath")
                val row = Row(selected)
                val diff = SimpleDiffRequest("Flow complete result: $path — ${id.take(12)}", factory.create(project, before), factory.create(project, after), "Baseline: ${row.side(true)}", "Candidate ${id.take(12)}: ${row.side(false)}")
                diff.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
                diff.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, true))
                diff.putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true)
                presentDiff(diff)
            }
        }
    }
    private fun buttons() {
        val available = connected && !busy
        prepare.isEnabled = available && stopped.isSelected
        refresh.isEnabled = available && candidate != null
        more.isEnabled = available && next != null
        open.isEnabled = available && files.selectedValue != null
        accept.isEnabled = available && candidate != null && stopped.isSelected && candidate?.get("decision")?.asString != "rejected" && candidate?.get("sourceGenerationCurrent")?.asBoolean == true
        reject.isEnabled = available && candidate != null
        runCheck.isEnabled = available && candidate != null && stopped.isSelected
        checkedVersion.isEnabled = available && candidate?.getAsJsonArray("checks")?.any { it.asJsonObject.optionalText("resultCandidateId")?.let { id -> id != candidate?.get("id")?.asString } == true } == true
    }
    override fun dispose() {
        scope.cancel()
    }
}
