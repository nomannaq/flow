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
import java.util.UUID
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox

/** Whole-result decisions reference immutable Rust-owned candidates. */
internal class CandidateReviewPanel(
    private val project: Project,
    private val controller: EngineController,
    parentScope: CoroutineScope,
    private val applyCandidate: (String) -> Unit = {},
    private val presentDiff: (SimpleDiffRequest) -> Unit = { DiffManager.getInstance().showDiff(project, it) },
) : JBPanel<CandidateReviewPanel>(BorderLayout()),
    Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.EDT)
    private val session = JBTextField().apply { accessibleContext.accessibleName = "Candidate source session" }
    private val stopped = JCheckBox("I verified all writers have stopped")
    private val prepare = JButton("Prepare result")
    private val refresh = JButton("Refresh result")
    private val more = JButton("More files")
    private val open = JButton("Open result diff")
    private val accept = JButton("Accept result")
    private val application = JButton("Continue to apply")
    private val reject = JButton("Reject result")
    private val command = JBTextField("[]").apply { accessibleContext.accessibleName = "Explicit candidate check command" }
    private val runCheck = JButton("Run check (15s)")
    private val checkedVersion = JButton("Review checked result")
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
        emptyText.text = "Select a closed session in History to review its files."
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
        border = com.intellij.util.ui.JBUI.Borders.empty(8)
        summary.font = javax.swing.UIManager.getFont("Label.font")
        summary.rows = 4
        files.visibleRowCount = 6
        session.emptyText.text = "Select a closed session from History"
        add(
            FlowUi.scroll(
                FlowUi.column(
                    FlowUi.heading("Review the complete result", "Inspect the agent's changes before accepting them."),
                    FlowUi.field("Source session", session),
                    stopped,
                    FlowUi.actions(FlowUi.primary(prepare), refresh),
                    FlowUi.scroll(summary, 100),
                    FlowUi.field("Changed files", FlowUi.scroll(files, 170)),
                    FlowUi.actions(open, more),
                    FlowUi.actions(FlowUi.primary(accept), reject),
                    application,
                    Disclosure(
                        "Run an optional check",
                        FlowUi.column(
                            FlowUi.note("Runs as your account in a temporary copy. Enter an executable and arguments as a JSON array; commands and results are retained."),
                            FlowUi.field("Check command", command),
                            FlowUi.actions(runCheck, checkedVersion),
                        ),
                    ),
                ),
            ),
            BorderLayout.CENTER,
        )
        application.addActionListener { candidate?.let { applyCandidate(it["id"].asString) } }
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

    fun selectCandidate(id: String) {
        request { load(id) }
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
        session.text = result.getAsJsonObject("candidate")["sessionId"].asString
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
        application.isEnabled = available && candidate?.get("decision")?.asString == "accepted"
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
