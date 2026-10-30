package dev.flow.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.InclusionListener
import com.intellij.vcs.commit.CommitWorkflowUi
import java.io.StringWriter
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Snapshot checked changes, including native partial-commit exclusions; never fall back to all changes. */
internal class NativeCommitSelection(
    private val project: Project,
    private val ui: CommitWorkflowUi,
    document: Document,
    private val modeUnchanged: () -> Boolean,
) : Disposable {
    private val lifetime: Disposable
    private val message: CommitMessageSnapshot
    private val changes = ui.getIncludedChanges().toList()
    private val unversioned = ui.getIncludedUnversionedFiles().toList()
    private val identity = identity(ui)
    private val inclusionRevision = AtomicLong()
    private val disposed = AtomicBoolean()
    private val sourceEdited = AtomicBoolean()

    init {
        require(changes.size + unversioned.size in 1..256) { "Select between 1 and 256 files in the Commit window." }
        val files = (changes.mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.virtualFile } + unversioned.mapNotNull { it.virtualFile }).distinct()
        require(files.none { it.isDirectory || it.length > 2 * 1024 * 1024 }) { "Select files smaller than 2 MiB for commit-message generation." }
        require(files.sumOf { it.length } <= 8 * 1024 * 1024) { "Selected files exceed 8 MiB. Select fewer files and generate again." }
        message = CommitMessageSnapshot(document)
        lifetime = Disposer.newDisposable("Flow commit selection")
        Disposer.register(lifetime, Disposable { disposed.set(true) })
        val selected = files.toSet()
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    // Observe edits, including documents opened later, without loading files on the EDT.
                    if (FileDocumentManager.getInstance().getFile(event.document) in selected) sourceEdited.set(true)
                }
            },
            lifetime,
        )
        ui.addInclusionListener(
            object : InclusionListener {
                override fun inclusionChanged() {
                    inclusionRevision.incrementAndGet()
                }
            },
            lifetime,
        )
        Disposer.register(ui, lifetime)
    }

    fun unchanged(): Boolean = !project.isDisposed && alive && message.unchanged() &&
        inclusionRevision.get() == 0L && !sourceEdited.get() && identity(ui) == identity && modeUnchanged()

    fun whenDisposed(action: () -> Unit) = Disposer.register(lifetime, Disposable { action() })
    val alive get() = !disposed.get()
    fun messageUnchanged() = message.unchanged()
    fun updateMessage(write: () -> Unit) = message.update(write)
    fun restoreMessage() = message.restore()

    fun diff(): String = diff(project, changes + unversioned.map { Change(null, CurrentContentRevision.create(it)) })

    override fun dispose() {
        try {
            Disposer.dispose(lifetime)
        } finally {
            Disposer.dispose(message)
        }
    }

    companion object {
        private fun identity(ui: CommitWorkflowUi): List<String> = (
            ui.getIncludedChanges().map { change ->
                listOf(change.beforeRevision?.file?.path, change.afterRevision?.file?.path, change.beforeRevision?.revisionNumber?.asString(), change.afterRevision?.revisionNumber?.asString(), (change as? com.intellij.openapi.vcs.changes.ChangeListChange)?.changeListId).joinToString("\u0000")
            } + ui.getIncludedUnversionedFiles().map { "unversioned\u0000${it.path}" }
            ).sorted()

        internal fun diff(project: Project, changes: List<Change>): String {
            require(changes.size in 1..256) { "Select between 1 and 256 files in the Commit window." }
            val manifest = JsonArray().apply {
                changes.forEach { change ->
                    add(
                        JsonObject().apply {
                            addProperty("status", change.type.name)
                            addProperty("before", change.beforeRevision?.file?.path)
                            addProperty("after", change.afterRevision?.file?.path)
                        },
                    )
                }
            }
            val textChanges = changes.filter { change -> listOfNotNull(change.beforeRevision, change.afterRevision).none(IdeaTextPatchBuilder::isBinaryRevision) }
            val patches = IdeaTextPatchBuilder.buildPatch(project, textChanges, Path.of(requireNotNull(project.basePath)), false, true)
            val writer = StringWriter()
            UnifiedDiffWriter.write(project, patches.filterIsInstance<TextFilePatch>(), writer, "\n", CommitContext())
            val result = "Selected changes (JSON metadata; binary content is omitted):\n$manifest\n\nSelected text diff:\n$writer"
            require(result.toByteArray().size <= 256 * 1024) { "The selected diff exceeds 256 KiB. Select fewer files and generate again." }
            return result
        }

        fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
