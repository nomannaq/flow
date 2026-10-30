package dev.flow.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBList
import dev.flow.bridge.AgentView
import dev.flow.bridge.EngineController
import dev.flow.bridge.optionalText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/** A read-only baseline-to-recorded-checkpoint preview. No live documents or agent patches. */
internal class ChatChangesPanel(
    private val project: Project?,
    controller: EngineController,
    private val review: (() -> Unit)? = null,
    private val request: suspend (String, JsonObject) -> JsonElement = controller::historyRequest,
    private val present: ((SimpleDiffRequest) -> Unit)? = null,
) : JPanel(BorderLayout(0, 4)),
    Disposable {
    private val scope = CoroutineScope(controller.scope.coroutineContext + SupervisorJob(controller.scope.coroutineContext[Job]) + Dispatchers.EDT)
    private val lifetime = Disposer.newDisposable("Flow chat diff")
    private val title = JButton("Changed files", AllIcons.General.ArrowRight).apply {
        accessibleContext.accessibleName = "Show changed files"
        horizontalAlignment = javax.swing.SwingConstants.LEFT
        isContentAreaFilled = false
        isBorderPainted = false
        putClientProperty("JButton.buttonType", "toolBarButton")
    }
    private val note = FlowUi.note("")
    private val files = JBList<String>().apply {
        accessibleContext.accessibleName = "Files changed by the agent"
        cellRenderer = javax.swing.DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 3
    }
    private val preview = JPanel(BorderLayout()).apply { isVisible = false }
    private val fileScroll = FlowUi.scroll(files, 48)
    private lateinit var details: JPanel
    private var expanded = false
    private val more = JButton("More files", AllIcons.General.ArrowDown)
    private val hide = JButton("Hide diff", AllIcons.Actions.Collapseall).apply { isVisible = false }
    private var viewer: DiffRequestPanel? = null
    private var session: String? = null
    private var snapshot: String? = null
    private var next: String? = null
    private var rows = emptyList<JsonObject>()
    private var count = -1L
    private var total = 0
    private var connected = false
    private var revision = 0L
    private var loading = false
    private var job: Job? = null
    private var selectionJob: Job? = null
    private val statistics = linkedMapOf<String, JsonObject>()
    private val statsLock = Mutex()

    init {
        isVisible = false
        isOpaque = false
        border = com.intellij.util.ui.JBUI.Borders.empty(4, 0)
        getAccessibleContext().accessibleName = "In-chat changed files"
        val open = JButton("View diff", AllIcons.Actions.Diff).apply { addActionListener { openSelected() } }
        val reviewButton = FlowUi.iconButton(JButton("Review all changes"), AllIcons.Actions.ListChanges, "Review and apply recorded changes").apply {
            isVisible = review != null
            addActionListener { review?.invoke() }
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.CENTER)
            add(reviewButton, BorderLayout.EAST)
        }
        details = FlowUi.column(fileScroll, FlowUi.actions(open, more, hide), note, preview).apply { isVisible = false }
        add(header, BorderLayout.NORTH)
        add(details, BorderLayout.CENTER)
        title.addActionListener {
            expanded = !expanded
            showDetails()
        }
        files.addListSelectionListener { if (!it.valueIsAdjusting && !loading) openSelected() }
        files.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "flow.openChatDiff")
        files.actionMap.put(
            "flow.openChatDiff",
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) = openSelected()
            },
        )
        more.addActionListener { load(next) }
        hide.addActionListener { clearPreview() }
    }

    fun update(agent: AgentView, online: Boolean) {
        val reconnected = online && !connected
        connected = online
        if (session != agent.id) {
            revision++
            job?.cancel()
            selectionJob?.cancel()
            session = agent.id
            snapshot = null
            rows = emptyList()
            statistics.clear()
            count = -1
            total = 0
            next = null
            files.setListData(emptyArray())
            clearPreview()
            expanded = false
            showDetails()
            isVisible = false
        }
        files.isEnabled = online
        more.isEnabled = online && next != null
        if (online && agent.id != null && (agent.recordedOperations != count || reconnected)) {
            count = agent.recordedOperations
            if (count > 0) load(null)
        }
    }

    private fun load(after: String?) {
        val source = session ?: return
        if (!connected) return
        val version = ++revision
        job?.cancel()
        selectionJob?.cancel()
        more.isEnabled = false
        job = scope.launch {
            try {
                val result = request(
                    "history.changes",
                    JsonObject().apply {
                        addProperty("sessionId", source)
                        if (after != null) {
                            addProperty("after", after)
                            addProperty("snapshotId", snapshot)
                        }
                    },
                ).asJsonObject
                if (version != revision || source != session) return@launch
                check(result["sessionId"].asString == source)
                val checkpoint = result["snapshotId"].asString.also { check(Regex("[a-f0-9]{64}").matches(it)) }
                check(after == null || checkpoint == snapshot)
                val values = result.getAsJsonArray("items").also { check(it.size() <= 20) }.map { it.asJsonObject }
                snapshot = checkpoint
                next = result.optionalText("next")
                rows = if (after == null) values else rows + values
                check(rows.size <= 1000) { "Open History to inspect more than 1,000 changed files." }
                clearPreview()
                loading = true
                files.setListData(
                    rows.map { file ->
                        val old = file.optionalText("oldPath")
                        val new = file.optionalText("newPath")
                        val path = if (old != null && new != null && old != new) "$old → $new" else new ?: old.orEmpty()
                        "${file["kind"].asString.substringBefore(" inferred").replaceFirstChar { it.uppercase() }} · $path"
                    }.toTypedArray(),
                )
                loading = false
                val line = files.getFontMetrics(files.font).height + com.intellij.util.ui.JBUI.scale(6)
                fileScroll.preferredSize = java.awt.Dimension(0, line * rows.size.coerceIn(1, 4))
                total = result["total"].asInt.also { check(it >= rows.size) }
                title.text = "$total changed ${if (total == 1) "file" else "files"}"
                note.text = if (!result["complete"].asBoolean || result["captureGap"].asBoolean) "Some changes could not be captured. This preview shows verified content only; inspect capture details in Settings." else "Changes so far · not applied to your project"
                isVisible = rows.isNotEmpty() || !result["complete"].asBoolean || result["captureGap"].asBoolean
                more.isEnabled = next != null && rows.size < 1000
                more.isVisible = next != null
                revalidate()
                repaint()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (source == session && version == revision) {
                    note.text = "Changed files could not be loaded. Open History to retry."
                    title.text = "Changed files unavailable"
                    title.toolTipText = note.text
                    isVisible = true
                }
            }
        }
    }

    internal fun webState() = JsonObject().apply {
        addProperty("snapshot", snapshot)
        addProperty("total", total)
        addProperty("note", note.text)
        addProperty("visible", isVisible)
        addProperty("more", next != null && rows.size < 1000)
        add("files", com.google.gson.JsonArray().apply { rows.forEach { add(it.deepCopy()) } })
    }

    internal fun webMore() {
        if (next != null && rows.size < 1000) load(next)
    }

    internal fun webRefresh() = load(null)

    /** Only small counts cross the browser bridge; comparisons run outside the UI thread. */
    internal suspend fun webStats(checkpoint: String): JsonObject = statsLock.withLock {
        val source = requireNotNull(session)
        check(connected && snapshot == checkpoint) { "Recorded changes changed. Open Edits again." }
        val selected = rows.toList()
        val values = com.google.gson.JsonArray()
        for (file in selected) {
            check(connected && session == source && snapshot == checkpoint) { "Recorded changes changed. Open Edits again." }
            val identity = file["contentIds"].toString()
            val count = statistics[identity] ?: run {
                val result = request("history.changeDiff", diffParams(source, checkpoint, file)).asJsonObject
                check(connected && session == source && snapshot == checkpoint) { "Recorded changes changed. Open Edits again." }
                verifyDiffIdentity(result, source, checkpoint, file)
                withContext(Dispatchers.Default) { lineCounts(result) }.also {
                    check(connected && session == source && snapshot == checkpoint) { "Recorded changes changed. Open Edits again." }
                    if (statistics.size >= 1000) statistics.remove(statistics.keys.first())
                    statistics[identity] = it
                }
            }
            values.add(count.deepCopy().apply { addProperty("index", file["index"].asInt) })
        }
        JsonObject().apply {
            addProperty("snapshot", checkpoint)
            add("files", values)
        }
    }

    private fun lineCounts(result: JsonObject) = JsonObject().apply {
        val before = result.optionalText("before")
        val after = result.optionalText("after")
        check(before == null || before.toByteArray().size <= 65536)
        check(after == null || after.toByteArray().size <= 65536)
        if (before == null || after == null) {
            addProperty("note", listOfNotNull(result.optionalText("beforeNote"), result.optionalText("afterNote")).distinct().joinToString("; "))
        } else {
            try {
                val fragments = ComparisonManager.getInstance().compareLines(before, after, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
                addProperty("additions", fragments.sumOf { it.endLine2 - it.startLine2 })
                addProperty("deletions", fragments.sumOf { it.endLine1 - it.startLine1 })
            } catch (_: DiffTooBigException) {
                addProperty("note", "Line counts unavailable for this change. Open the native diff.")
            }
        }
    }

    internal suspend fun webDiff(index: Int, checkpoint: String, native: Boolean): JsonObject {
        val source = requireNotNull(session)
        check(connected && snapshot == checkpoint) { "Recorded changes changed. Select the file again." }
        val file = requireNotNull(rows.firstOrNull { it["index"].asInt == index })
        val result = request("history.changeDiff", diffParams(source, checkpoint, file)).asJsonObject
        check(connected && session == source && snapshot == checkpoint) { "Recorded changes changed. Select the file again." }
        verifyDiffIdentity(result, source, checkpoint, file)
        val before = result.optionalText("before")
        val after = result.optionalText("after")
        check(before == null || before.toByteArray().size <= 65536)
        check(after == null || after.toByteArray().size <= 65536)
        if (native) {
            check(before != null && after != null) {
                val notes = listOfNotNull(result.optionalText("beforeNote"), result.optionalText("afterNote")).filter { it.isNotBlank() }.distinct().joinToString("; ")
                "Text diff unavailable. $notes. Inspect the recorded file in History."
            }
            val diff = diffRequest(file, result, before, after)
            if (present != null) present.invoke(diff) else DiffManager.getInstance().showDiff(project, diff)
        }
        return result
    }

    private fun openSelected() {
        val source = session ?: return
        val checkpoint = snapshot ?: return
        val file = rows.getOrNull(files.selectedIndex) ?: return
        if (!connected) return
        expanded = true
        showDetails()
        val version = revision
        selectionJob?.cancel()
        selectionJob = scope.launch {
            try {
                val result = request("history.changeDiff", diffParams(source, checkpoint, file)).asJsonObject
                if (version != revision || session != source || snapshot != checkpoint || rows.getOrNull(files.selectedIndex) !== file) return@launch
                verifyDiffIdentity(result, source, checkpoint, file)
                val before = result.optionalText("before")
                val after = result.optionalText("after")
                if (before == null || after == null) {
                    clearPreview()
                    note.text = result["beforeNote"].asString + "; " + result["afterNote"].asString
                    return@launch
                }
                check(before.toByteArray().size <= 65536 && after.toByteArray().size <= 65536)
                val diff = diffRequest(file, result, before, after)
                if (present != null) {
                    present.invoke(diff)
                } else {
                    val panel = viewer ?: DiffManager.getInstance().createRequestPanel(project, lifetime, null).also {
                        viewer = it
                        preview.add(it.component, BorderLayout.CENTER)
                    }
                    panel.setRequest(diff)
                    preview.preferredSize = com.intellij.util.ui.JBUI.size(0, 300)
                    preview.minimumSize = com.intellij.util.ui.JBUI.size(0, 240)
                    preview.isVisible = true
                    hide.isVisible = true
                    revalidate()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (source == session && version == revision) {
                    clearPreview()
                    note.text = "This recorded diff is unavailable. Select the file again to retry."
                }
            }
        }
    }

    private fun diffParams(source: String, checkpoint: String, file: JsonObject) = JsonObject().apply {
        addProperty("sessionId", source)
        addProperty("snapshotId", checkpoint)
        addProperty("fileIndex", file["index"].asInt)
        add("contentIds", file["contentIds"])
    }

    private fun diffRequest(file: JsonObject, result: JsonObject, before: String, after: String): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        val path = file.optionalText("newPath") ?: file.optionalText("oldPath").orEmpty()
        val type = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/'))
        val leftMode = result.optionalText("beforeMode")
        val rightMode = result.optionalText("afterMode")
        fun label(text: String, mode: String?) = text + if (leftMode != rightMode && mode != null) " · mode ${mode.toInt().toString(8)}" else ""
        return SimpleDiffRequest(path, factory.create(project, before, type), factory.create(project, after, type), label("Before agent changes", leftMode), label("Recorded changes so far", rightMode)).apply {
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, true))
            putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true)
        }
    }

    private fun verifyDiffIdentity(result: JsonObject, source: String, checkpoint: String, file: JsonObject) {
        check(
            result["sessionId"].asString == source &&
                result["snapshotId"].asString == checkpoint &&
                result["fileIndex"] == file["index"] &&
                result["contentIds"] == file["contentIds"],
        )
        check(
            result.optionalText("oldPath") == file.optionalText("oldPath") &&
                result.optionalText("newPath") == file.optionalText("newPath"),
        )
    }

    private fun clearPreview() {
        viewer?.setRequest(null)
        preview.isVisible = false
        hide.isVisible = false
        revalidate()
    }
    private fun showDetails() {
        details.isVisible = expanded
        title.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        title.accessibleContext.accessibleName = if (expanded) "Hide changed files" else "Show changed files"
        if (!expanded) clearPreview()
        revalidate()
        repaint()
    }
    override fun dispose() {
        scope.cancel()
        Disposer.dispose(lifetime)
    }
}
