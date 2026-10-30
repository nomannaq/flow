package dev.flow.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.flow.bridge.AgentEvent
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
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JButton
import javax.swing.JPanel

/** Validates ordered, session-scoped journal pages before rendering or export. */
internal class ConversationJournal(val sessionId: String) {
    var through: String? = null
        private set
    var next: String? = null
        private set
    val entries = mutableListOf<JsonObject>()
    val legacy get() = entries.any { it["legacy"]?.asBoolean != false }
    private var last = 0L
    private var bytes = 0L

    fun params() = JsonObject().apply {
        addProperty("sessionId", sessionId)
        next?.let { addProperty("after", it) }
        through?.let { addProperty("through", it) }
    }

    fun append(page: JsonObject) {
        require(page["sessionId"].asString == sessionId)
        val watermark = page["through"].asString.also { require(it.toLong() >= last) }
        require(through == null || through == watermark)
        val values = page.getAsJsonArray("items").also { require(it.size() <= 20) }.map { it.asJsonObject }
        var previous = last
        values.forEach { row ->
            val sequence = row["sequence"].asString.toLong()
            require(sequence > previous && sequence <= watermark.toLong())
            require(row["kind"].asString.length <= 128 && row["text"].asString.toByteArray().size <= 32 * 1024)
            previous = sequence
        }
        val cursor = page.optionalText("next")
        require(cursor == null || values.isNotEmpty() && cursor.toLong() == previous && previous < watermark.toLong())
        val added = values.sumOf { it.toString().toByteArray().size.toLong() }
        require(entries.size + values.size <= 20000 && bytes + added <= 32L * 1024 * 1024) { "Conversation exceeds the 32 MiB export limit." }
        entries.addAll(values)
        last = previous
        bytes += added
        through = watermark
        next = cursor
    }

    fun events() = entries.map { dev.flow.bridge.AgentProtocol.event(it) }

    fun export(json: Boolean, attachments: JsonObject = JsonObject()): String {
        check(through != null && next == null) { "Load every page before exporting." }
        if (json) {
            return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                JsonObject().apply {
                    addProperty("format", "flow.conversation.v1")
                    addProperty("sessionId", sessionId)
                    addProperty("through", through)
                    addProperty("legacyContentMayBeClipped", legacy)
                    add("attachments", attachments)
                    add("events", com.google.gson.JsonArray().apply { entries.forEach(::add) })
                },
            ) + "\n"
        }
        return buildString {
            append("# Flow conversation\n\nSession: $sessionId\n\nRecorded through event $through. This is an observed journal, not a provider billing or complete execution transcript.\n")
            if (legacy) append("\nOlder records may contain clipped text; missing text cannot be reconstructed.\n")
            var previous = ""
            entries.forEach { row ->
                val kind = row["kind"].asString
                val continuation = kind in setOf("message.delta", "thinking.delta") && previous == kind || kind == "agent.plan.delta" && previous in setOf("agent.plan", "agent.plan.delta") || kind == "turn.prompt.delta" && previous in setOf("turn.started", "turn.prompt.delta")
                if (!continuation) {
                    val title = when (kind) {
                        "turn.started" -> "You"
                        "message.delta" -> "Agent"
                        "thinking.delta" -> "Thinking"
                        else -> kind
                    }
                    append("\n\n## $title\n\n")
                }
                dev.flow.bridge.AgentProtocol.event(row).images.forEach { image ->
                    val attachment = attachments.getAsJsonObject(image.id)
                    if (attachment != null) append("\n![Recorded image](data:${image.mimeType};base64,${attachment["data"].asString})\n\n")
                }
                append(row["text"].asString)
                dev.flow.bridge.AgentProtocol.attachmentRefs(row["attachments"]).forEach { file ->
                    val name = file.name.replace("\\", "_").replace("[", "_").replace("]", "_")
                    val content = attachments.getAsJsonObject(file.id)
                    if (content != null) {
                        append("\n\n[$name](data:${file.mimeType};base64,${content["data"].asString})")
                    } else {
                        append("\n\nAttachment: $name (${file.mimeType}, ${file.size} bytes; content not included)")
                    }
                }
                previous = kind
            }
            append('\n')
        }
    }
}

/** Reading history cannot acquire a writer or reuse an expired permission. */
internal class ConversationHistoryPanel(
    private val project: Project,
    private val controller: EngineController,
    parentScope: CoroutineScope,
    private val rewind: ((String, AgentEvent) -> Unit)? = null,
    private val recover: ((JsonObject) -> Unit)? = null,
    private val request: suspend (String, JsonObject) -> JsonElement = controller::historyRequest,
) : JPanel(BorderLayout()),
    Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.EDT)
    private val chatChanges = ChatChangesPanel(project, controller)
    private val conversation = ConversationView(scope, emptyTitle = "No recorded messages", emptyDescription = "This snapshot has no recorded message text. Return to History to choose another conversation.", project = project, controller = controller, rewind = rewind, changes = chatChanges)
    private val note = FlowUi.note("Select a conversation from History.")
    private val more = JButton("Load more messages", AllIcons.General.ArrowDown)
    private val markdown = JButton("Export Markdown…", AllIcons.Actions.Download)
    private val json = JButton("Export JSON…", AllIcons.Actions.Download)
    private val recoverQueue = JButton("Recover queued draft…", AllIcons.Actions.Undo)
    private val find = JButton("Find in loaded messages", AllIcons.Actions.Search)
    private var journal: ConversationJournal? = null
    private var job: Job? = null
    private var connected = false

    init {
        getAccessibleContext().accessibleName = "Read recorded conversation"
        add(FlowUi.column(note, FlowUi.actions(more, find, markdown, json, recoverQueue)), BorderLayout.NORTH)
        add(conversation, BorderLayout.CENTER)
        more.addActionListener { load() }
        find.addActionListener { conversation.showFind() }
        markdown.addActionListener { export(false) }
        json.addActionListener { export(true) }
        recoverQueue.isVisible = recover != null
        recoverQueue.addActionListener { recoverQueued() }
        buttons()
    }

    private fun recoverQueued() {
        val selected = journal ?: return
        launchAction {
            val params = JsonObject().apply { addProperty("sessionId", selected.sessionId) }
            val items = request("queue.list", params).asJsonObject.getAsJsonArray("items").map { it.asJsonObject }
            if (items.isEmpty()) {
                note.text = "This conversation has no retained queued prompts."
                return@launchAction
            }
            val index = Messages.showChooseDialog(project, "Recover a draft for explicit review. An unknown-outcome prompt may already have run; nothing is sent automatically.", "Recover queued draft", null, items.map { it["status"].asString + " · " + it["preview"].asString }.toTypedArray(), null)
            items.getOrNull(index)?.let { item ->
                val command = request("queue.get", params.apply { addProperty("id", item["id"].asString) }).asJsonObject
                recover?.invoke(command)
            }
        }
    }

    fun open(sessionId: String) {
        job?.cancel()
        journal = ConversationJournal(sessionId)
        chatChanges.update(dev.flow.bridge.AgentView(id = sessionId, recordedOperations = 1), connected)
        conversation.update(emptyList(), sessionId)
        load()
    }

    private fun launchAction(action: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch(start = CoroutineStart.LAZY) {
            buttons(busy = true)
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                note.text = "Conversation unavailable or exceeds the viewing/export limit. No partial export was saved. Reopen history and retry."
            } finally {
                if (job == coroutineContext[Job]) buttons()
            }
        }
        job!!.start()
    }

    private fun load() {
        val selected = journal ?: return
        if (!connected) return
        launchAction {
            note.text = "Reading recorded conversation…"
            val page = request("history.conversation", selected.params()).asJsonObject
            selected.append(page)
            conversation.update(selected.events(), selected.sessionId)
            note.text = "Recorded conversation · ${selected.entries.size} journal entries · read only." +
                (if (selected.next != null) " More messages are available." else " End of this recorded snapshot.") +
                (if (selected.legacy) " Older records may contain clipped text." else "")
        }
    }

    private fun export(asJson: Boolean) {
        val selected = journal ?: return
        val extension = if (asJson) "json" else "md"
        val file = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Export recorded conversation", "Includes recorded messages, images, thinking and tool text. Review before sharing.", extension), project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, "flow-${selected.sessionId}.$extension")?.file?.toPath() ?: return
        if (Files.exists(file) && Messages.showYesNoDialog(project, "Replace ${file.fileName}?", "Export conversation", null) != Messages.YES) return
        launchAction {
            note.text = "Exporting the complete recorded snapshot…"
            val complete = ConversationJournal(selected.sessionId)
            do {
                val params = complete.params().apply { selected.through?.let { addProperty("through", it) } }
                complete.append(request("history.conversation", params).asJsonObject)
            } while (complete.next != null)
            val attachments = JsonObject()
            var imageBytes = 0L
            complete.events().forEach { event ->
                event.attachments.forEach { fileRef ->
                    if (!attachments.has(fileRef.id)) {
                        val result = request(
                            "history.attachment",
                            JsonObject().apply {
                                addProperty("sessionId", complete.sessionId)
                                addProperty("turnId", event.turnId)
                                add("attachment", fileRef.json())
                            },
                        ).asJsonObject
                        require(dev.flow.bridge.AgentProtocol.attachmentRefs(com.google.gson.JsonArray().apply { add(result["attachment"]) }).single() == fileRef)
                        imageBytes += ((fileRef.size.toLong() + 2) / 3) * 4
                        check(imageBytes <= 24L * 1024 * 1024) { "Attachments exceed the export limit." }
                        val encoded = withContext(Dispatchers.IO) {
                            val bytes = Files.newInputStream(Path.of(result["path"].asString)).use { it.readNBytes(fileRef.size + 1) }
                            check(bytes.size == fileRef.size)
                            java.util.Base64.getEncoder().encodeToString(bytes)
                        }
                        attachments.add(fileRef.id, fileRef.json().apply { addProperty("data", encoded) })
                    }
                }
                event.images.forEach { image ->
                    if (!attachments.has(image.id)) {
                        val encoded = request(
                            "history.image",
                            JsonObject().apply {
                                addProperty("sessionId", complete.sessionId)
                                addProperty("turnId", event.turnId)
                                addProperty("imageId", image.id)
                            },
                        ).asJsonObject
                        imageBytes += encoded["data"].asString.length
                        check(imageBytes <= 24L * 1024 * 1024) { "Images exceed the export limit." }
                        attachments.add(image.id, encoded.apply { addProperty("mimeType", image.mimeType) })
                    }
                }
            }
            withContext(Dispatchers.IO) {
                val text = complete.export(asJson, attachments)
                check(text.toByteArray().size <= 32 * 1024 * 1024) { "Conversation exceeds the export limit." }
                val temporary = Files.createTempFile(file.toAbsolutePath().parent, ".flow-conversation-", ".tmp")
                try {
                    // Replace the selected directory entry; never follow a target symlink.
                    Files.writeString(temporary, text)
                    try {
                        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            note.text = "Export saved to $file" + if (complete.legacy) ". Older records may contain clipped text." else ""
        }
    }

    private fun buttons(busy: Boolean = false) {
        recoverQueue.isEnabled = connected && !busy && journal != null
        more.isEnabled = connected && !busy && journal?.next != null && journal!!.entries.size < 1000
        more.toolTipText = "The reader loads up to 1,000 journal entries. Export reads the full snapshot, up to 32 MiB."
        markdown.isEnabled = connected && !busy && journal?.through != null
        json.isEnabled = markdown.isEnabled
        find.isEnabled = journal?.entries?.isNotEmpty() == true
    }

    fun update(view: EngineView) {
        journal?.let { chatChanges.update(dev.flow.bridge.AgentView(id = it.sessionId, recordedOperations = if (view.agent.id == it.sessionId) maxOf(1, view.agent.recordedOperations) else 1), view.connected) }
        conversation.setFontOffset(com.intellij.openapi.application.ApplicationManager.getApplication().getService(CodexSettings::class.java).state.conversationFontOffset)
        if (!view.connected && connected) {
            job?.cancel()
            note.text = "Engine disconnected. Reopen the conversation after reconnecting."
        }
        connected = view.connected
        buttons(job?.isActive == true)
    }

    override fun dispose() {
        scope.cancel()
        conversation.dispose()
        chatChanges.dispose()
    }
}
