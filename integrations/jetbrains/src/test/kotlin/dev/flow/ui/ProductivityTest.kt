package dev.flow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import dev.flow.bridge.ContextStartupActivity
import git4idea.index.ui.GitStageCommitPanel
import git4idea.index.ui.GitStageUiSettings
import git4idea.index.ui.GitStageUiSettingsListener
import javax.swing.JTextArea

class ProductivityTest : LightPlatformTestCase() {
    fun testContextRefreshIgnoresItsOwnWritesAndOtherProjects() {
        assertTrue(ContextStartupActivity.affectsContext("/project", "/project/src/lib.rs"))
        assertTrue(ContextStartupActivity.affectsContext("/project", "/project/.gitignore"))
        assertFalse(ContextStartupActivity.affectsContext("/project", "/project/.flow/map.md"))
        assertFalse(ContextStartupActivity.affectsContext("/project", "/project/.git/index"))
        assertFalse(ContextStartupActivity.affectsContext("/project", "/project-other/lib.rs"))
        assertTrue(ContextStartupActivity.affectsContext("C:/project", "C:/project/src/lib.rs"))
        assertFalse(ContextStartupActivity.affectsContext("C:/project", "C:/project/.flow/map.md"))
    }
    fun testPromptRevisionDetectsTypingAndUndoWhileDraftIsPending() {
        val prompt = JTextArea("Keep the existing API")
        val revision = TextRevision(prompt)
        val before = revision.version
        prompt.append(" and tests")
        prompt.text = "Keep the existing API"
        assertTrue(revision.version > before)
        assertEquals("Keep the existing API", prompt.text)
    }
    fun testNativeMessageSnapshotRejectsUserTypingEvenAfterUndo() {
        val document = EditorFactory.getInstance().createDocument("my message")
        val pending = disposeOnTearDown(CommitMessageSnapshot(document))
        assertTrue(pending.unchanged())
        WriteCommandAction.runWriteCommandAction(project) { document.setText("new user message") }
        assertFalse(pending.unchanged())
        WriteCommandAction.runWriteCommandAction(project) { document.setText("my message") }
        assertFalse(pending.unchanged())
        assertEquals("my message", pending.original)
    }
    fun testPinnedStagingPanelExposesEditableMessageWithoutCommitting() {
        val settings = object : GitStageUiSettings {
            override var ignoredFilesShown = false
            override var isCommitAllEnabled = false
            override fun addListener(listener: GitStageUiSettingsListener, disposable: Disposable) = Unit
        }
        val panel = GitStageCommitPanel(project, settings)
        try {
            val document = panel.commitMessage.editorField.document
            val pending = disposeOnTearDown(CommitMessageSnapshot(document))
            assertNull(panel.editedCommit)
            assertFalse(panel.isCommitAll)
            panel.commitMessageUi.text = "Reviewed draft"
            assertEquals("Reviewed draft", panel.commitMessageUi.text)
            assertFalse(pending.unchanged())
            assertEquals("Reviewed draft", document.text.trim())
            panel.commitMessageUi.text = "User edits remain editable"
            assertEquals("User edits remain editable", panel.commitMessageUi.text)
        } finally {
            Disposer.dispose(panel)
        }
    }
}
