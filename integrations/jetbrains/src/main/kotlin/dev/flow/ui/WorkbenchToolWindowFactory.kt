package dev.flow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.flow.bridge.EngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JTabbedPane

class WorkbenchToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<EngineService>()
        val controller = service.controller
        val status = JBLabel("Connecting to engine…")
        val details = JBList<String>()
        val sessions = AgentSessionPanel(controller)
        val tabs = JTabbedPane()
        val history = SessionHistoryPanel(project, controller, service.scope) { source ->
            sessions.newConversation(source)
            tabs.selectedIndex = 0
        }
        val actions = DefaultActionGroup(
            object : DumbAwareAction("Check Engine Health", "Ask the Rust engine for its status", null) {
                init {
                    templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
                }
                override fun actionPerformed(event: AnActionEvent) {
                    controller.health()
                }
            },
            object : DumbAwareAction("Restart Engine", "Reconnect to the Flow engine", null) {
                init {
                    templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
                }
                override fun actionPerformed(event: AnActionEvent) {
                    controller.restart()
                }
            },
        )
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            add(status, BorderLayout.NORTH)
            add(
                tabs.apply {
                    addTab("Session", sessions)
                    addTab("History", history)
                    addTab("Engine", JBScrollPane(details))
                },
                BorderLayout.CENTER,
            )
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("Flow.Workbench", actions, true)
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.SOUTH)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        val lifetime = Disposer.newDisposable("Flow tool window content")
        val disposed = AtomicBoolean()
        content.setDisposer(lifetime)
        Disposer.register(lifetime, history)
        val observer = service.scope.launch {
            controller.view.collect { view ->
                withContext(Dispatchers.EDT) {
                    if (!disposed.get()) {
                        status.text = view.status
                        sessions.update(view)
                        history.update(view)
                        details.setListData(
                            arrayOf(
                                view.pid?.let { "Engine PID: $it" } ?: "Engine offline",
                                "${view.sessions} recorded sessions",
                                "${view.operations} recorded operations",
                                view.historyStatus,
                                view.health,
                            ),
                        )
                    }
                }
                // StateFlow conflates floods; at most ten Swing refreshes per second.
                delay(100)
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
