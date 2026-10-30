package dev.flow.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.vcs.commit.CommitMessageUi
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
internal class NativeCommitMessageService(private val project: Project) : Disposable {
    private var cancellation: AtomicBoolean? = null

    @Volatile private var indicator: ProgressIndicator? = null
    val running get() = cancellation != null

    fun cancel() {
        cancellation?.set(true)
        indicator?.cancel()
    }

    fun generate(selection: NativeCommitSelection, message: CommitMessageUi, request: ((String, ProgressIndicator, (String) -> Unit) -> String)? = null) {
        check(!running)
        val agentSelection = project.service<AgentSelection>()
        val target = if (request == null) {
            runCatching { agentSelection.snapshot() }.getOrElse {
                Disposer.dispose(selection)
                throw it
            }
        } else {
            null
        }
        val cancelled = AtomicBoolean()
        val modality = ModalityState.current()
        cancellation = cancelled
        selection.whenDisposed { cancel() }
        object : Task.Backgroundable(project, "Generate commit message with Flow", true) {
            private var draft = ""
            private val finished = AtomicBoolean()
            private val latest = AtomicReference<String?>()
            private val scheduled = AtomicBoolean()
            private val commandGroup = "flow.commit.${UUID.randomUUID()}"
            private var previewed = false
            private var accepted = false

            private fun invalidReason(): String? = when {
                !selection.unchanged() -> "The message, checked changes or commit mode changed during generation. Your message was retained; generate again."
                target != null && runCatching { agentSelection.snapshot() }.getOrNull() != target -> "The provider or model changed during generation. Existing message retained; generate again."
                else -> null
            }

            private fun write(text: String) = WriteCommandAction.runWriteCommandAction(
                project,
                "Generate Commit Message with Flow",
                commandGroup,
                Runnable {
                    selection.updateMessage { message.text = text }
                },
            )

            private fun preview(text: String) {
                latest.set(text)
                if (!scheduled.compareAndSet(false, true)) return
                ApplicationManager.getApplication().invokeLater({
                    scheduled.set(false)
                    val next = latest.getAndSet(null) ?: return@invokeLater
                    if (finished.get() || cancelled.get() || cancellation !== cancelled || project.isDisposed) return@invokeLater
                    invalidReason()?.let {
                        cancel()
                        report(project, it)
                        return@invokeLater
                    }
                    write(next)
                    previewed = true
                }, modality)
            }

            override fun run(progress: ProgressIndicator) {
                indicator = progress
                if (cancelled.get()) progress.cancel()
                progress.checkCanceled()
                progress.text = "Reading checked changes"
                val diff = selection.diff()
                val fingerprint = NativeCommitSelection.fingerprint(diff)
                draft = request?.invoke(diff, progress, ::preview) ?: target!!.generate(diff, "commit", progress, ::preview)
                progress.checkCanceled()
                check(NativeCommitSelection.fingerprint(selection.diff()) == fingerprint) { "Selected content changed during generation. Existing message retained; generate again." }
            }

            override fun onSuccess() {
                finished.set(true)
                if (cancelled.get() || project.isDisposed) return
                invalidReason()?.let {
                    report(project, it)
                    return
                }
                write(draft)
                accepted = true
                message.focus()
            }

            override fun onThrowable(error: Throwable) {
                if (!cancelled.get() && !project.isDisposed) report(project, error.message ?: "Could not generate a commit message. Existing message retained.")
            }

            override fun onFinished() {
                finished.set(true)
                latest.set(null)
                try {
                    if (!accepted && previewed && !project.isDisposed && selection.messageUnchanged()) {
                        WriteCommandAction.runWriteCommandAction(project, "Generate Commit Message with Flow", commandGroup, Runnable { selection.restoreMessage() })
                    }
                } finally {
                    Disposer.dispose(selection)
                    indicator = null
                    cancellation = null
                }
            }
        }.queue()
    }

    override fun dispose() = cancel()

    companion object {
        fun report(project: Project, text: String) {
            NotificationGroupManager.getInstance().getNotificationGroup("Flow.CommitGeneration")
                .createNotification("Flow commit message", text, NotificationType.WARNING).notify(project)
        }
    }
}
