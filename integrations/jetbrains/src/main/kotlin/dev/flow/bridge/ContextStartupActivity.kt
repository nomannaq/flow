package dev.flow.bridge

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Initializes project data on open, without waiting for the tool window. */
class ContextStartupActivity : ProjectActivity {
    @OptIn(FlowPreview::class)
    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode || project.isDefault || project.basePath == null) return
        val targetProject = project
        val service = project.service<EngineService>()
        val controller = service.controller
        val changes = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val bus = ApplicationManager.getApplication().messageBus.connect(service)
        bus.subscribe(
            TrustedProjectsListener.TOPIC,
            object : TrustedProjectsListener {
                override fun onProjectTrusted(project: Project) {
                    if (project === targetProject) controller.start()
                }
            },
        )
        bus.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { affectsContext(project.basePath!!, it.path) }) changes.tryEmit(Unit)
                }
            },
        )
        // Reconnects/restarts rebuild the map too. Cancellation is owned by the
        // project service scope, so unload cannot leave a background indexer.
        service.scope.launch {
            controller.view.map { it.epoch }.distinctUntilChanged().collect { epoch ->
                if (epoch != null) changes.tryEmit(Unit)
            }
        }
        service.scope.launch(Dispatchers.IO) {
            changes.debounce(750).collectLatest {
                if (!TrustedProjects.isProjectTrusted(project)) return@collectLatest
                controller.view.first { it.connected }
                controller.showContextStatus("Refreshing .flow/ project context…")
                try {
                    val result = controller.productivityRequest("context.initialize")
                    controller.showContextStatus(".flow/ ready: ${result["files"].asInt} source files, ${result["symbols"].asInt} symbols${if (result["truncated"].asBoolean) " (limited coverage)" else ""}; token savings not measured")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    controller.showContextStatus("Project context unavailable. Check .flow/ ownership and write access, then restart the engine. Agent sessions remain available.")
                }
            }
        }
        if (TrustedProjects.isProjectTrusted(project)) controller.start()
    }

    companion object {
        internal fun affectsContext(root: String, path: String): Boolean {
            val prefix = root.trimEnd('/') + "/"
            if (!path.startsWith(prefix)) return false
            val relative = path.removePrefix(prefix)
            return relative.split('/').none { it == ".flow" || it == ".git" }
        }
    }
}
