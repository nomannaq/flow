package dev.flow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.flow.bridge.EngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class WorkbenchToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<EngineService>()
        val controller = service.controller
        val panel = WorkbenchPanel(project, controller, service.scope)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        val lifetime = Disposer.newDisposable("Flow tool window content")
        val disposed = AtomicBoolean()
        content.setDisposer(lifetime)
        Disposer.register(lifetime, panel)
        val observer = service.scope.launch {
            controller.view.collect { view ->
                withContext(Dispatchers.EDT) {
                    if (!disposed.get()) {
                        panel.update(view)
                    }
                }
                // Conflate intermediate projections while keeping the live tail responsive.
                delay(33)
            }
        }
        Disposer.register(
            lifetime,
            Disposable {
                disposed.set(true)
                observer.cancel()
            },
        )
        toolWindow.contentManager.addContent(content)
        controller.start()
    }
}
