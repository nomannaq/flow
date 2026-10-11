package dev.flow.spikes

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

abstract class CompatibilityProbeAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            event.project != null && java.lang.Boolean.getBoolean("flow.compatibility.spikes")
    }
}

class HistoricalDiffProbeAction : CompatibilityProbeAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val snapshots = listOf("flow-probe-0\n", "flow-probe-1\n", "flow-probe-2\n")
        val factory = DiffContentFactory.getInstance()
        snapshots.zipWithNext().forEachIndexed { index, (before, after) ->
            DiffManager.getInstance().showDiff(
                project,
                SimpleDiffRequest(
                    "Flow historical operation ${index + 1}",
                    factory.create(project, before, PlainTextFileType.INSTANCE),
                    factory.create(project, after, PlainTextFileType.INSTANCE),
                    "Checkpoint $index",
                    "Checkpoint ${index + 1}",
                ),
            )
        }
    }
}

class MergeProbeAction : CompatibilityProbeAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val base = "setting=base\n"
        val output = EditorFactory.getInstance().createDocument(base)
        val request = DiffRequestFactory.getInstance().createMergeRequest(
            project,
            PlainTextFileType.INSTANCE,
            output,
            listOf("setting=developer\n", base, "setting=agent\n"),
            "Flow compatibility merge — in-memory fixture",
            listOf("Current project", "Session baseline", "Agent candidate"),
        ) { result ->
            if (result == MergeResult.CANCEL) {
                Messages.showInfoMessage(project, "Merge cancelled; no result accepted.", "Flow Compatibility")
            } else {
                Messages.showInfoMessage(project, "Result: $result\n\n${output.text}", "Flow Compatibility")
            }
        }
        DiffManager.getInstance().showMerge(project, request)
    }
}

class ProcessLifecycleProbeAction : CompatibilityProbeAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<ProcessLifecycleProbe>()?.start()
    }
}

@Service(Service.Level.PROJECT)
class ProcessLifecycleProbe(private val project: Project, private val scope: CoroutineScope) {
    private val logger = Logger.getInstance(ProcessLifecycleProbe::class.java)
    private var active: Job? = null

    fun start() {
        if (active?.isActive == true) return
        active = scope.launch(Dispatchers.IO) {
            val process = try {
                ProcessBuilder("/bin/sleep", "60")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (exception: java.io.IOException) {
                logger.warn("Flow lifecycle fixture failed to start", exception)
                withContext(Dispatchers.EDT) {
                    Messages.showErrorDialog(project, "Could not launch /bin/sleep. This probe targets macOS.", "Flow Compatibility")
                }
                return@launch
            }
            try {
                logger.info("Flow lifecycle fixture started: pid=${process.pid()}")
                withContext(Dispatchers.EDT) {
                    Messages.showInfoMessage(
                        project,
                        "Fixture PID: ${process.pid()}\nClose this project within 60 seconds, then verify the process exits.\nSee idea.log for lifecycle evidence.",
                        "Flow Compatibility",
                    )
                }
                while (process.isAlive) delay(100)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(2, TimeUnit.SECONDS)
                    }
                    logger.info("Flow lifecycle fixture stopped: pid=${process.pid()}, alive=${process.isAlive}")
                }
            }
        }
    }
}
