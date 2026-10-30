package dev.flow.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.vcs.commit.NonModalCommitWorkflowUi
import git4idea.index.ui.GitStageCommitPanel

/** Native Commit toolbar action. Generation only fills the editable message; committing remains manual. */
class GenerateCommitMessageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val running = event.project?.getServiceIfCreated(NativeCommitMessageService::class.java)?.running == true
        val target = event.project?.let { runCatching { it.service<AgentSelection>().snapshot() } }
        event.presentation.icon = AllIcons.Toolwindows.ToolWindowAskAI
        event.presentation.text = if (running) "Cancel Flow Commit Message Generation" else "Generate Commit Message with Flow"
        event.presentation.isEnabled = running || context(event) != null && target?.isSuccess == true
        event.presentation.description = if (running) "Cancel generation and keep the existing message" else target?.getOrNull()?.description ?: target?.exceptionOrNull()?.message ?: "Select changes in the native Commit window"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = project.service<NativeCommitMessageService>()
        if (service.running) {
            service.cancel()
            return
        }
        val context = context(event) ?: return
        val handler = event.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) ?: return
        try {
            val selection = NativeCommitSelection(project, context.first, context.second) {
                !handler.amendCommitHandler.isAmendCommitMode && supportedMode(context.first)
            }
            service.generate(selection, context.first.commitMessageUi)
        } catch (error: IllegalStateException) {
            NativeCommitMessageService.report(project, error.message ?: "Agent configuration is unavailable. Retry when the session is ready.")
        } catch (error: IllegalArgumentException) {
            NativeCommitMessageService.report(project, error.message ?: "Select changes in the Commit window, then retry.")
        }
    }

    private fun context(event: AnActionEvent): Pair<CommitWorkflowUi, Document>? {
        if (event.project == null) return null
        val ui = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return null
        val handler = event.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) ?: return null
        if (handler.amendCommitHandler.isAmendCommitMode || !supportedMode(ui) || ui.getIncludedChanges().isEmpty() && ui.getIncludedUnversionedFiles().isEmpty()) return null
        val document = (ui.commitMessageUi as? CommitMessage)?.editorField?.document ?: event.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT) ?: return null
        return ui to document
    }

    private fun supportedMode(ui: CommitWorkflowUi): Boolean = (ui as? NonModalCommitWorkflowUi)?.editedCommit == null && (ui !is GitStageCommitPanel || !ui.isCommitAll)
}
