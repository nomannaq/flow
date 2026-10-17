package dev.flow.ui

import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.flow.bridge.EngineController
import dev.flow.bridge.EngineView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

internal class ApplicationPanel(
    private val project: Project,
    private val controller: EngineController,
    parentScope: CoroutineScope,
    private val reviewCandidate: (String) -> Unit,
    private val native: NativeApplication = NativeApplication(project),
    private val presentMerge: (MergeRequest) -> Unit = { DiffManager.getInstance().showMerge(project, it) },
) : JBPanel<ApplicationPanel>(BorderLayout()),
    Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.EDT)
    private val stopped = JCheckBox("I verified all writers have stopped")
    private val planButton = JButton("Plan application")
    private val refresh = JButton("Load application history")
    private val inspect = JButton("Inspect inputs")
    private val merge = JButton("Resolve conflict")
    private val apply = JButton("Apply result")
    private val cancel = JButton("Cancel application")
    private val reconcile = JButton("Inspect interrupted writes")
    private val rollback = JButton("Roll back Flow writes")
    private val review = JButton("Review candidate")
    private val summary = JBTextArea(6, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "Application status"
    }
    private data class Row(val value: JsonObject) {
        override fun toString() = "${value["status"].asString}: ${value["path"].asString}"
    }
    private data class Journal(val value: JsonObject) {
        override fun toString() = "${value["status"].asString}: ${value["id"].asString}"
    }
    private val files = JBList<Row>().apply {
        accessibleContext.accessibleName = "Application files"
        emptyText.text = "Plan an accepted result to inspect its files."
        cellRenderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
    }
    private val journals = JBList<Journal>().apply {
        accessibleContext.accessibleName = "Application journals"
        visibleRowCount = 3
        emptyText.text = "No application journals loaded."
        cellRenderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
    }
    private var candidateId: String? = null
    private var plan: JsonObject? = null
    private var planIntent: String? = null
    private var observedEpoch: String? = null
    private var busy = false
    private var cancelRequested = false
    private var job: Job? = null

    init {
        border = JBUI.Borders.empty(8)
        summary.font = javax.swing.UIManager.getFont("Label.font")
        summary.rows = 4
        files.visibleRowCount = 6
        add(
            FlowUi.scroll(
                FlowUi.column(
                    FlowUi.heading("Apply reviewed changes", "Resolve conflicts, then apply the accepted result to your project."),
                    stopped,
                    FlowUi.actions(FlowUi.primary(planButton)),
                    FlowUi.scroll(summary, 100),
                    FlowUi.field("Files to apply", FlowUi.scroll(files, 170)),
                    FlowUi.actions(inspect, merge),
                    FlowUi.actions(FlowUi.primary(apply), cancel),
                    review,
                    Disclosure(
                        "Application history & recovery",
                        FlowUi.column(
                            FlowUi.note("Inspect interrupted applications and recover only verified Flow writes."),
                            FlowUi.actions(refresh),
                            FlowUi.scroll(journals, 100),
                            FlowUi.actions(reconcile, rollback),
                        ),
                    ),
                ),
            ),
            BorderLayout.CENTER,
        )
        stopped.addActionListener { buttons() }
        files.addListSelectionListener { buttons() }
        journals.addListSelectionListener { if (!busy && !it.valueIsAdjusting) journals.selectedValue?.let { row -> request { show(controller.historyRequest("apply.get", query(row.value)).asJsonObject) } } }
        planButton.addActionListener {
            request {
                val id = requireNotNull(candidateId)
                val intent = planIntent ?: UUID.randomUUID().toString().also { planIntent = it }
                show(
                    controller.historyRequest(
                        "apply.plan",
                        JsonObject().apply {
                            addProperty("planId", intent)
                            addProperty("candidateId", id)
                            addProperty("writersStopped", stopped.isSelected)
                        },
                    ).asJsonObject,
                )
                planIntent = null
            }
        }
        refresh.addActionListener {
            request {
                val result = controller.historyRequest("apply.list").asJsonArray
                journals.setListData(result.map { Journal(it.asJsonObject) }.toTypedArray())
                summary.text = "Select a journal. Interrupted writes are inspected without replay. Recovery conflicts preserve newer content."
            }
        }
        inspect.addActionListener {
            val current = plan
            val index = files.selectedIndex
            if (current != null && index >= 0) {
                request {
                    val value = controller.historyRequest("apply.preview", query(current, index)).asJsonObject
                    val factory = DiffContentFactory.getInstance()
                    val diff = SimpleDiffRequest("Flow immutable apply inputs: ${value["path"].asString}", listOf("current", "baseline", "candidate").map { factory.create(project, value[it].asString) }, listOf("Saved target preimage", "Session baseline", "Frozen candidate"))
                    diff.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
                    diff.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, true, true))
                    diff.putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true)
                    DiffManager.getInstance().showDiff(project, diff)
                }
            }
        }
        merge.addActionListener { openMerge() }
        review.addActionListener { plan?.let { reviewCandidate(it["candidateId"].asString) } }
        apply.addActionListener { execute(false) }
        rollback.addActionListener { execute(true) }
        cancel.addActionListener {
            if (busy) {
                cancelRequested = true
                summary.append("\nCancellation requested; waiting for the current file receipt.")
            } else {
                plan?.let { j -> request { show(controller.historyRequest("apply.cancel", query(j)).asJsonObject) } }
            }
        }
        reconcile.addActionListener {
            plan?.let { j ->
                request {
                    val stamps = native.capture(j)
                    show(controller.historyRequest("apply.reconcile", action(j, stamps)).asJsonObject)
                }
            }
        }
        summary.text = "Accept a candidate in the Review step first. Application preserves staging; affected unsaved buffers must be saved explicitly before planning."
        buttons()
    }
    fun update(view: EngineView) {
        if (!view.connected || view.epoch != observedEpoch || view.agent.status in listOf("running", "starting", "ready", "closing", "cancelling", "authenticating")) stopped.isSelected = false
        observedEpoch = view.epoch
        buttons()
    }
    fun selectCandidate(id: String) {
        if (busy) return
        if (plan?.get("candidateId")?.asString == id && plan?.get("status")?.asString in listOf("planned", "conflicted")) {
            stopped.isSelected = false
            buttons()
            return
        }
        candidateId = id
        planIntent = null
        plan = null
        stopped.isSelected = false
        files.setListData(emptyArray())
        summary.text = "Candidate $id\nConfirm writers stopped, then plan against the current project."
        buttons()
    }
    private fun show(value: JsonObject) {
        plan = value
        candidateId = value["candidateId"].asString
        files.setListData(value.getAsJsonArray("files").map { Row(it.asJsonObject) }.toTypedArray())
        summary.text = "Application ${value["id"].asString}\nCandidate ${value["candidateId"].asString}\nStatus: ${value["status"].asString}\nNo index entries are staged or reset. Text resolutions require renewed candidate review."
        buttons()
    }
    private fun query(j: JsonObject, index: Int = 0, undo: Boolean = false) = JsonObject().apply {
        addProperty("planId", j["id"].asString)
        addProperty("fileIndex", index)
        addProperty("rollback", undo)
    }
    private fun action(j: JsonObject, stamps: JsonObject) = JsonObject().apply {
        addProperty("planId", j["id"].asString)
        addProperty("candidateId", j["candidateId"].asString)
        addProperty("writersStopped", stopped.isSelected)
        add("documentStamps", stamps)
    }
    private fun execute(undo: Boolean) {
        val original = plan ?: return
        request {
            cancelRequested = false
            val epoch = requireNotNull(controller.view.value.epoch)
            val stamps = native.capture(original)
            var current = controller.historyRequest(if (undo) "apply.rollback" else "apply.begin", action(original, stamps)).asJsonObject
            show(current)
            if (current["status"].asString == "recoveryConflict") return@request
            try {
                val indices = current.getAsJsonArray("files").mapIndexedNotNull { index, e -> if (e.asJsonObject["status"].asString == if (undo) "applied" else "todo") index else null }.let { if (undo) it.reversed() else it }
                for (index in indices) {
                    if (cancelRequested) {
                        show(controller.historyRequest("apply.cancel", query(current)).asJsonObject)
                        return@request
                    }
                    val ticket = controller.historyRequest("apply.authorize", query(current, index, undo)).asJsonObject
                    check(controller.applicationEpochIsLive(epoch)) { "Application authorization belongs to a closed engine epoch" }
                    native.write(ticket)
                    current = controller.historyRequest("apply.receipt", query(current, index, undo)).asJsonObject
                    show(current)
                }
                show(controller.historyRequest("apply.finish", query(current, undo = undo)).asJsonObject)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // An unknown reply or partial write is never retried automatically.
                runCatching { controller.historyRequest("apply.cancel", query(current)) }
                throw failure
            }
        }
    }
    private fun openMerge() {
        val current = plan ?: return
        val index = files.selectedIndex.takeIf { it >= 0 } ?: return
        request {
            val value = controller.historyRequest("apply.preview", query(current, index)).asJsonObject
            val output = EditorFactory.getInstance().createDocument(value["current"].asString)
            val completed = CompletableDeferred<String?>()
            val mergeRequest = DiffRequestFactory.getInstance().createMergeRequest(project, PlainTextFileType.INSTANCE, output, listOf(value["current"].asString, value["baseline"].asString, value["candidate"].asString), "Flow resolve ${value["path"].asString}", listOf("Current project", "Session baseline", "Reviewed candidate")) { result ->
                completed.complete(if (result == MergeResult.CANCEL) null else output.text)
            }
            presentMerge(mergeRequest)
            val resolved = completed.await() ?: return@request
            val next = controller.historyRequest(
                "apply.resolve",
                JsonObject().apply {
                    addProperty("planId", current["id"].asString)
                    addProperty("fileIndex", index)
                    addProperty("content", resolved)
                },
            ).asJsonObject
            show(next)
            summary.append("\nMerge output frozen. Review and accept the new candidate before applying.")
            reviewCandidate(next["candidateId"].asString)
        }
    }
    private fun request(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        buttons()
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                summary.append("\nApplication blocked or outcome unknown. Newer/unsaved files are preserved. Load journals and inspect the interrupted application before retrying. A recovery conflict requires manual reconciliation.")
            } finally {
                busy = false
                buttons()
            }
        }
        job!!.start()
    }
    private fun buttons() {
        planButton.isEnabled = !busy && candidateId != null && stopped.isSelected
        journals.isEnabled = !busy
        refresh.isEnabled = !busy
        inspect.isEnabled = !busy && plan != null && files.selectedIndex >= 0
        merge.isEnabled = !busy && plan?.get("status")?.asString == "conflicted" && files.selectedValue?.value?.get("status")?.asString == "conflict"
        apply.isEnabled = !busy && stopped.isSelected && plan?.get("status")?.asString == "planned"
        review.isEnabled = !busy && plan != null
        reconcile.isEnabled = !busy && stopped.isSelected && plan?.get("status")?.asString in listOf("applying", "rollingBack", "recoveryRequired", "recoveryConflict")
        rollback.isEnabled = !busy && stopped.isSelected && plan?.get("status")?.asString in listOf("complete", "applying", "rollingBack", "recoveryRequired", "recoveryConflict")
        cancel.isEnabled = plan != null && (busy || plan?.get("status")?.asString in listOf("planned", "conflicted", "applying", "rollingBack"))
    }
    override fun dispose() {
        scope.cancel()
    }
}
