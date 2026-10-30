package dev.flow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener

/** Track only Flow's own writes. Typing and undo still invalidate the pending draft. */
internal class CommitMessageSnapshot(private val document: Document) : Disposable {
    val original = document.text
    private var expected = original
    private var stamp = document.modificationStamp
    private var revision = 0L
    private var expectedRevision = 0L
    private val listener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            // Native undo can restore an old modification stamp. This counter cannot rewind.
            revision++
        }
    }

    init {
        document.addDocumentListener(listener, this)
    }

    fun unchanged() = revision == expectedRevision && document.modificationStamp == stamp && document.text == expected

    fun update(write: () -> Unit) {
        check(unchanged()) { "The commit message changed during generation. Your edit was retained." }
        write()
        expected = document.text
        stamp = document.modificationStamp
        expectedRevision = revision
    }

    fun restore() = update { document.setText(original) }

    override fun dispose() = Unit
}
