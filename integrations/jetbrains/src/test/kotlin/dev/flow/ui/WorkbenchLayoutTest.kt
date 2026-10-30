package dev.flow.ui

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.flow.bridge.AgentEvent
import dev.flow.bridge.AgentPermission
import dev.flow.bridge.AgentView
import dev.flow.bridge.EngineController
import dev.flow.bridge.EngineView
import dev.flow.bridge.ProjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class WorkbenchLayoutTest : LightPlatformTestCase() {
    fun testFirstMessageControlsAndChangedDraftDuringWorkspaceSetup() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("first-message"), { error("No agent should launch") })
        val panel = AgentSessionPanel(controller, project, openSettings = {})
        try {
            fun button(name: String) = descendants(panel).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == name }
            val prompt = descendants(panel).filterIsInstance<com.intellij.ui.components.JBTextArea>().single { it.accessibleContext.accessibleName == "Task prompt" }
            val executable = descendants(panel.configuration).filterIsInstance<com.intellij.ui.components.JBTextField>().single { it.accessibleContext.accessibleName == "ACP adapter executable" }
            val online = EngineView(connected = true, agentSupported = true)
            panel.update(online)
            assertFalse("No empty messages", button("Start session").isEnabled)
            assertTrue("Choosing a model does not require a draft", button("Agent model and configuration").isEnabled)
            button("Understand this project").doClick(0)
            assertTrue(prompt.text.startsWith("Explain how this project"))
            assertTrue(button("Start session").isEnabled)
            assertEquals("Send", button("Start session").text)
            executable.text = "/fixture/adapter"
            button("Start session").doClick(0) // waits for sessions.baseline
            prompt.text = "A different task that I have not sent yet"
            panel.update(online.copy(baseline = "a".repeat(40), baselineRevision = 1))
            assertEquals("A different task that I have not sent yet", prompt.text)
            assertTrue("Late setup must not send a revised draft", controller.view.value.actionError.contains("draft changed"))
            val enter = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
            val shiftEnter = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK)
            assertEquals("flow.send", prompt.inputMap.get(enter))
            assertFalse("Shift+Enter inserts a line", prompt.inputMap.get(enter) == prompt.inputMap.get(shiftEnter))
            panel.update(online.copy(connected = false))
            assertFalse(button("Start session").isEnabled)
            assertEquals("A different task that I have not sent yet", prompt.text)
            panel.newConversation(null)
            val context = descendants(panel).filterIsInstance<ComposerContext>().single()
            val commit = descendants(context.workspaceSettings).filterIsInstance<com.intellij.ui.components.JBTextField>().single { it.accessibleContext.accessibleName == "Clean baseline commit" }
            assertTrue("New conversations resolve the current commit again", commit.text.isBlank())
            render("workbench-first-message-320", panel, 320, 650)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testChatFirstComposerGrowsWithoutLosingSubmitAndCollapsesAfterClearing() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("simple-composer"), { error("Preview cannot launch an agent") })
        val panel = WorkbenchPanel(project, controller, scope)
        try {
            val ready = EngineView(connected = true, agentSupported = true, agent = AgentView(id = "simple", status = "ready", agentName = "claude", configOptions = configuration()))
            panel.update(ready)
            val prompt = descendants(panel).filterIsInstance<com.intellij.ui.components.JBTextArea>().single { it.accessibleContext.accessibleName == "Task prompt" }
            val composer = descendants(panel).filterIsInstance<JComponent>().single { it.accessibleContext?.accessibleName == "Prompt composer" }
            render("workbench-simple-empty-320", panel, 320, 600)
            val compact = composer.height
            prompt.text = (1..24).joinToString("\n") { "Requirement $it: preserve the recorded changes." }
            render("workbench-simple-long-draft-320", panel, 320, 600)
            assertTrue("Long drafts grow the input", composer.height > compact)
            assertTrue("The input remains bounded", composer.height < 240)
            val submit = descendants(panel).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == "Send" }
            assertTrue("Submit remains visible", SwingUtilities.convertPoint(submit, 0, submit.height, panel).y <= panel.height)
            panel.update(ready.copy(agent = ready.agent.copy(status = "running")))
            assertTrue(descendants(panel).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == "Queue prompt" }.isVisible)
            prompt.text = ""
            render("workbench-simple-empty-running-320", panel, 320, 600)
            assertFalse("An empty draft needs no queue button", descendants(panel).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == "Queue prompt" }.isVisible)
            assertEquals("Clearing the draft restores the compact input", compact, composer.height)
            val previousLaf = javax.swing.UIManager.getLookAndFeel()
            val previousDark = !com.intellij.ui.JBColor.isBright()
            try {
                javax.swing.UIManager.setLookAndFeel(com.intellij.ide.ui.laf.darcula.DarculaLaf())
                com.intellij.ui.JBColor.setDark(true)
                com.intellij.openapi.util.IconLoader.setUseDarkIcons(true)
                SwingUtilities.updateComponentTreeUI(panel)
                panel.update(ready)
                for (width in listOf(320, 480)) render("workbench-simple-empty-dark-$width", panel, width, 600)
            } finally {
                javax.swing.UIManager.setLookAndFeel(previousLaf)
                com.intellij.ui.JBColor.setDark(previousDark)
                com.intellij.openapi.util.IconLoader.setUseDarkIcons(previousDark)
                SwingUtilities.updateComponentTreeUI(panel)
            }
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    private fun configuration() = dev.flow.bridge.AgentProtocol.configOptions(
        com.google.gson.JsonParser.parseString(
            """[
          {"id":"model","name":"Model","description":"","category":"model","kind":"select","currentValue":"model-a","options":[
            {"value":"model-a","name":"Provider model A","description":"A model advertised by the connected adapter","group":""},
            {"value":"model-b","name":"Provider model B","description":"Another advertised model","group":""}]},
          {"id":"effort","name":"Effort","description":"Reasoning effort supported by this model","category":"thought_level","kind":"select","currentValue":"high","options":[
            {"value":"low","name":"Low","description":"Less reasoning","group":""},{"value":"high","name":"High","description":"More reasoning","group":""}]},
          {"id":"speed","name":"Speed","description":"Available service tiers","category":"","kind":"select","currentValue":"standard","options":[
            {"value":"standard","name":"Standard","description":"Standard service","group":""},{"value":"fast","name":"Fast","description":"Priority service","group":""}]},
          {"id":"mode","name":"Permissions","description":"Agent approval policy","category":"mode","kind":"select","currentValue":"ask","options":[
            {"value":"ask","name":"Ask for approval","description":"Ask before commands and writes","group":""},{"value":"auto","name":"Full Auto","description":"Agent executes commands and writes automatically","group":""}]}
        ]""",
        ).asJsonArray,
    )
    fun testQueuedPromptAndRewindLayoutAtNarrowWidths() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("queue-layout"), { error("Preview cannot launch an agent") })
        val panel = AgentSessionPanel(controller, project, openSettings = {}, openChanges = {})
        try {
            panel.update(
                EngineView(
                    connected = true,
                    agentSupported = true,
                    agent = AgentView(
                        id = "queue-layout",
                        status = "running",
                        agentName = "codex-acp",
                        supportsImages = true,
                        configOptions = configuration(),
                        queuePaused = true,
                        queue = listOf(dev.flow.bridge.QueuedPrompt("q-1", "Review the screenshot before changing files", "pending", 2), dev.flow.bridge.QueuedPrompt("q-2", "Run the relevant checks", "pending", 0)),
                        events = listOf(AgentEvent("turn.started", "Match the reference layout and preserve the original files.", turnId = "turn-layout"), AgentEvent("message.delta", "I’m checking the files and will show each change for review.")),
                    ),
                ),
            )
            val pictures = descendants(panel).filterIsInstance<PromptAttachments>().single()
            val image = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val picture = PromptAttachments.normalize("layout reference.png", java.io.ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
            pictures.restore(
                com.google.gson.JsonArray().apply {
                    add(picture.json())
                    add(picture.copy(name = "second reference.png").json())
                },
            )
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Image draft must finish loading", { runCatching { pictures.snapshot().items.size == 2 }.getOrDefault(false) }, 10)
            val frozen = pictures.snapshot()
            for (width in listOf(320, 360, 480)) render("chat-queue-rewind-$width", panel, width, 700)
            pictures.restore(com.google.gson.JsonArray().apply { add(picture.copy(name = "replacement.png").json()) })
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Replacement draft must finish loading", { runCatching { pictures.snapshot().items.singleOrNull()?.name == "replacement.png" }.getOrDefault(false) }, 10)
            pictures.acknowledge(frozen)
            assertEquals("A late send acknowledgement must preserve newer image drafts", "replacement.png", pictures.snapshot().items.single().name)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    override fun setUp() {
        super.setUp()
        com.intellij.openapi.util.IconLoader.activate()
    }

    override fun tearDown() {
        try {
            com.intellij.openapi.util.IconLoader.deactivate()
        } finally {
            super.tearDown()
        }
    }

    fun testDarkThemePreview() {
        val previous = javax.swing.UIManager.getLookAndFeel()
        val wasDark = !com.intellij.ui.JBColor.isBright()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The light test fixture has no registered theme catalogue. Use the SDK LAF directly.
            javax.swing.UIManager.setLookAndFeel(com.intellij.ide.ui.laf.darcula.DarculaLaf())
            com.intellij.ui.JBColor.setDark(true)
            com.intellij.openapi.util.IconLoader.setUseDarkIcons(true)
            val controller = EngineController(scope, ProjectId("dark-layout"), { error("Preview cannot launch an agent") })
            val panel = AgentSessionPanel(controller, project, openSettings = {}, openHistory = {}, openChanges = {})
            panel.update(
                EngineView(
                    connected = true,
                    agentSupported = true,
                    agent = AgentView(
                        status = "ready",
                        id = "preview",
                        agentName = "codex-acp",
                        recordedOperations = 4,
                        configOptions = configuration(),
                        events = listOf(
                            AgentEvent("turn.started", "Make the history browser easier to scan on a small laptop screen."),
                            AgentEvent("message.delta", "I grouped the session controls, shortened the timestamps and kept the recorded file tree readable at narrow widths."),
                            AgentEvent("operation.finished", "Native layout tests passed"),
                        ),
                    ),
                ),
            )
            render("chat-dark-360", panel, 360)
            render("chat-dark-480", panel, 480)
            panel.update(EngineView(connected = true, agentSupported = true, agent = AgentView(id = "connected", status = "ready", agentName = "codex-acp", configOptions = configuration(), events = connectionDetails())))
            render("chat-connected-dark-480", panel, 480)
            for (provider in listOf("codex", "claude")) {
                val picker = AgentConfigurationPopup.content(configuration(), provider) { _, _ -> }
                render("agent-configuration-$provider-dark-360", picker, 360)
                render("agent-configuration-$provider-dark-480", picker, 480)
            }
        } finally {
            scope.cancel()
            javax.swing.UIManager.setLookAndFeel(previous)
            com.intellij.ui.JBColor.setDark(wasDark)
            com.intellij.openapi.util.IconLoader.setUseDarkIcons(wasDark)
        }
    }

    fun testNarrowWorkbenchLayoutsAndNativePreviews() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("layout"), { error("Layout must not launch an agent") })
        val session = AgentSessionPanel(controller, project, openSettings = {}, openHistory = {}, openChanges = {})
        val history = SessionHistoryPanel(project, controller, scope, newConversation = {})
        val review = CandidateReviewPanel(project, controller, scope)
        val application = ApplicationPanel(project, controller, scope, reviewCandidate = {})
        try {
            session.update(EngineView(connected = true, agentSupported = true))
            for (width in listOf(360, 480)) {
                render("chat-empty-$width", session, width)
                render("history-$width", history, width)
                render("review-$width", review, width)
                render("apply-$width", application, width)
                render("settings-$width", FlowUi.scroll(session.configuration), width)
            }
            session.update(
                EngineView(
                    connected = true,
                    agentSupported = true,
                    agent = AgentView(
                        id = "preview-session",
                        status = "running",
                        recordedOperations = 3,
                        events = listOf(
                            AgentEvent("turn.started", "Find why session history stops updating after reconnect, and add a regression test."),
                            AgentEvent("message.delta", "I found the stale cursor in the reconnect path. I’m checking the replay boundary before changing it."),
                            AgentEvent("operation.finished", "Read EngineController.kt (completed)"),
                        ),
                        permissions = listOf(AgentPermission("preview-permission", "Run the focused native tests?", "Gradle test", listOf("once" to "Allow once"), setOf("once"))),
                    ),
                ),
            )
            render("chat-approval-360", session, 360)
            render("chat-approval-480", session, 480)
            val approval = descendants(session).filterIsInstance<AbstractButton>().single { it.text == "Allow once" }
            assertEquals("preview-permission", approval.getClientProperty("flow.permission.requestId"))
        } finally {
            history.dispose()
            review.dispose()
            application.dispose()
            scope.cancel()
        }
    }

    fun testStreamingPreservesReaderPositionAndOffersReturnToLatest() {
        val conversation = ConversationView()
        val events = (1..30).map { AgentEvent("message.delta", "Message $it. " + "A readable streamed response. ".repeat(12)) }
        conversation.update(events)
        layout(conversation, 360, 600)
        UIUtil.dispatchAllInvocationEvents()
        layout(conversation, 360, 600)
        val bar = conversation.scroll.verticalScrollBar
        assertTrue("Transcript must overflow: max=${bar.maximum}, visible=${bar.visibleAmount}; " + descendants(conversation).filterIsInstance<JComponent>().joinToString { "${it.javaClass.simpleName} ${it.bounds} pref=${it.preferredSize}" }, bar.maximum > bar.visibleAmount)
        bar.value = 0
        conversation.update(events.dropLast(1) + events.last().copy(text = events.last().text + " More text."))
        layout(conversation, 360, 600)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("Streaming must not move a reader at the top", 0, bar.value)
        val latest = descendants(conversation).filterIsInstance<AbstractButton>().single { it.text == "Jump to latest" }
        assertTrue(latest.isVisible)
        latest.doClick(0)
        assertTrue(bar.maximum - bar.visibleAmount - bar.value <= 24)
    }

    private fun connectionDetails() = listOf(
        AgentEvent("adapter.compatibility", "Codex ACP 0.5.2 matches B01 compatibility reference"),
        AgentEvent("context.attachment", "Read-only Flow MCP context configured"),
        AgentEvent("agent.update", "available_commands_update"),
        AgentEvent("session.ready", "Connected with native login; choose configuration or send a task"),
    )

    fun testCompactComposerLeavesRoomForConversationAndKeepsDiagnosticsInSettings() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = EngineController(scope, ProjectId("compact-chat"), { error("Preview must not launch an agent") })
            val panel = AgentSessionPanel(controller, project, openSettings = {}, openHistory = {}, openChanges = {})
            val view = EngineView(connected = true, agentSupported = true, agent = AgentView(id = "connected", status = "ready", configOptions = configuration(), events = connectionDetails()))
            panel.update(view)
            for (width in listOf(360, 480)) {
                render("chat-connected-$width", panel, width)
                val composer = descendants(panel).filterIsInstance<JComponent>().single { it.accessibleContext?.accessibleName == "Prompt composer" }
                val conversation = descendants(panel).filterIsInstance<ConversationView>().single()
                assertTrue("Composer must stay compact: ${composer.height}; ${composer.components.joinToString { "${it.javaClass.simpleName} ${it.bounds} preferred=${it.preferredSize}" }}", composer.height <= if (width == 360) 230 else 200)
                assertTrue("Conversation should own most of the available height: ${conversation.height}", conversation.height >= 450)
            }
            assertFalse(descendants(panel).filterIsInstance<com.intellij.ui.components.JBTextArea>().any { it.text.contains("matches B01") || it.text == "available_commands_update" })
            assertTrue(descendants(panel.configuration).filterIsInstance<com.intellij.ui.components.JBTextArea>().any { it.text.contains("matches B01") })
            panel.update(view.copy(agent = view.agent.copy(events = connectionDetails() + AgentEvent("context.degraded", "Context unavailable: initial overview only"))))
            assertFalse("Lifecycle diagnostics must stay out of the conversation", descendants(panel).filterIsInstance<ConversationView>().flatMap(::descendants).filterIsInstance<com.intellij.ui.components.JBTextArea>().any { it.text.contains("Context unavailable") })
            assertTrue("Diagnostics remain available in Settings", descendants(panel.configuration).filterIsInstance<com.intellij.ui.components.JBTextArea>().any { it.text.contains("Context unavailable") })
        } finally {
            scope.cancel()
        }
    }

    fun testSearchableConfigurationUsesAdvertisedIdsAndNativeSelectors() {
        val selected = mutableListOf<Pair<String, String>>()
        val options = configuration()
        val popup = AgentConfigurationPopup.content(options, "claude") { option, value -> selected.add(option.id to value.asString) }
        for (width in listOf(360, 480)) render("agent-configuration-$width", popup, width)
        val search = descendants(popup).filterIsInstance<com.intellij.ui.components.JBTextField>().single()
        val list = descendants(popup).filterIsInstance<com.intellij.ui.components.JBList<*>>().single()
        search.text = "model B"
        assertEquals(1, list.model.size)
        search.postActionEvent()
        assertEquals("model" to "model-b", selected.last())
        val effort = descendants(popup).filterIsInstance<javax.swing.JComboBox<*>>().single { it.accessibleContext.accessibleName == "Effort" }
        effort.selectedIndex = 0
        assertEquals("effort" to "low", selected.last())
        assertFalse("The model popup must not contain permission modes", descendants(popup).filterIsInstance<javax.swing.JComboBox<*>>().any { it.accessibleContext.accessibleName == "Permissions" })
        val permissionPopup = AgentConfigurationPopup.content(options, "claude", modeOnly = true) { option, value -> selected.add(option.id to value.asString) }
        assertTrue(descendants(permissionPopup).filterIsInstance<com.intellij.ui.components.JBList<*>>().isEmpty())
        val permissions = descendants(permissionPopup).filterIsInstance<javax.swing.JComboBox<*>>().single { it.accessibleContext.accessibleName == "Permissions" }
        permissions.selectedIndex = 1
        assertEquals("mode" to "auto", selected.last())
        search.text = "not advertised"
        assertEquals(0, list.model.size)
        val before = selected.size
        search.postActionEvent()
        assertEquals(before, selected.size)
    }

    fun testModeOnlyAdaptersShowModelGuidanceAndKeepPermissionsSeparate() {
        val mode = dev.flow.bridge.AgentConfigOption("mode", "Mode", "Agent permissions and operating mode", "mode", "mode", com.google.gson.JsonPrimitive("read-only"), listOf(dev.flow.bridge.AgentConfigChoice("read-only", "Read Only")))
        var settingsOpened = false
        val popup = AgentConfigurationPopup.content(listOf(mode), "codex", openSettings = { settingsOpened = true }) { _, _ -> fail("Model guidance must not change permissions") }
        assertTrue(descendants(popup).filterIsInstance<com.intellij.ui.components.JBTextArea>().any { it.text.contains("Close this session") })
        assertTrue("Legacy modes are not model choices", descendants(popup).filterIsInstance<javax.swing.JComboBox<*>>().isEmpty())
        descendants(popup).filterIsInstance<AbstractButton>().single { it.text == "Agent settings" }.doClick(0)
        assertTrue(settingsOpened)
        render("model-catalogue-unavailable-360", popup, 360)
        val permissions = AgentConfigurationPopup.content(listOf(mode), "codex", modeOnly = true) { _, _ -> }
        render("agent-permissions-360", permissions, 360)
        assertEquals("Read Only", descendants(permissions).filterIsInstance<javax.swing.JComboBox<*>>().single().selectedItem.let { (it as dev.flow.bridge.AgentConfigChoice).name })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = EngineController(scope, ProjectId("mode-only"), { error("Rendering must not launch an agent") })
            val panel = AgentSessionPanel(controller, project, openSettings = {})
            panel.update(EngineView(connected = true, agentSupported = true, agent = AgentView(id = "legacy", status = "ready", configOptions = listOf(mode))))
            assertEquals("Model settings", descendants(panel).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == "Agent model and configuration" }.text)
        } finally {
            scope.cancel()
        }
    }

    fun testComposerControlsFollowSessionLifecycleAndNarrowWorkspace() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var destination = ""
        try {
            val controller = EngineController(scope, ProjectId("composer"), { error("Layout must not launch an agent") })
            val panel = AgentSessionPanel(controller, project, openSettings = { destination = "settings" }, openHistory = { destination = "history" }, openChanges = { destination = "changes" })
            fun button(name: String) = descendants(panel).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == name }
            val connected = EngineView(connected = true, agentSupported = true)
            panel.update(connected)
            assertTrue(button("Start session").isVisible)
            assertFalse(button("Send").isVisible)
            assertFalse(button("Stop").isVisible)
            assertTrue(button("Agent model and configuration").isEnabled)
            val profile = descendants(panel).filterIsInstance<javax.swing.JComboBox<*>>().single { it.accessibleContext.accessibleName == "Agent profile" }
            profile.selectedItem = "claude"
            assertEquals("claude", project.getService(AgentSelection::class.java).profile)
            assertEquals("claude", com.intellij.openapi.components.service<CodexSettings>().state.adapterProfile)
            assertSame(com.intellij.icons.AllIcons.General.ArrowDown, button("Agent model and configuration").icon)
            assertTrue(button("Agent model and configuration").isEnabled)
            assertEquals("Choose model", button("Agent model and configuration").text)
            profile.selectedItem = "automatic"
            assertTrue(button("More composer actions").isVisible)
            // Long user model names must not displace the submit action.
            descendants(panel.configuration).filterIsInstance<com.intellij.ui.components.JBTextField>().single { it.accessibleContext.accessibleName == "Model override" }.text = "a-very-long-model-name-".repeat(8)
            render("chat-workspace-360", panel, 360)
            panel.update(connected.copy(agent = AgentView(id = "fixture", status = "running")))
            assertFalse(button("Start session").isVisible)
            assertFalse(button("Send").isVisible)
            assertTrue(button("Stop").isVisible && button("Stop").isEnabled)
            assertFalse(button("Agent model and configuration").isEnabled)
            panel.update(connected.copy(connected = false, agent = AgentView(id = "fixture", status = "running")))
            assertFalse(profile.isEnabled)
            assertTrue(button("Stop").isVisible)
            assertFalse(button("Stop").isEnabled)
            panel.update(connected.copy(agent = AgentView(id = "fixture", status = "ready")))
            assertTrue(button("Send").isVisible)
            assertFalse("An empty follow-up is not sendable", button("Send").isEnabled)
            descendants(panel).filterIsInstance<com.intellij.ui.components.JBTextArea>().single { it.accessibleContext.accessibleName == "Task prompt" }.text = "Continue with the next change"
            assertTrue(button("Send").isEnabled)
            assertFalse(button("Stop").isVisible)
            panel.update(connected.copy(connected = false))
            assertFalse(button("Start session").isEnabled)
            assertFalse(button("Send").isEnabled)
        } finally {
            scope.cancel()
        }
    }

    fun testCompleteWorkbenchPreservesChatWhileNavigatingAndKeepsSubmitOnScreen() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("full-workbench"), { error("A preview must not start an agent") })
        val panel = WorkbenchPanel(project, controller, scope)
        try {
            panel.update(
                EngineView(
                    connected = true,
                    agentSupported = true,
                    agent = AgentView(
                        id = "preview",
                        status = "ready",
                        agentName = "codex-acp",
                        recordedOperations = 2,
                        configOptions = configuration(),
                        events = listOf(
                            AgentEvent("turn.started", "Fix the reconnect bug"),
                            AgentEvent("message.delta", "## Found the issue\n\nThe replay cursor **skips events** after reconnect.\n\n```rust\nlet cursor = replay.last_consumed();\n```\n\nI will preserve each committed event."),
                        ),
                    ),
                ),
            )
            val prompt = descendants(panel.sessions).filterIsInstance<com.intellij.ui.components.JBTextArea>().single { it.accessibleContext.accessibleName == "Task prompt" }
            prompt.text = "Keep this draft while reading history"
            for (width in listOf(320, 360, 480)) {
                render("workbench-chat-$width", panel, width, 600)
                val submit = descendants(panel).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == "Send" }
                val location = SwingUtilities.convertPoint(submit, 0, 0, panel)
                assertTrue("Submit must stay inside short tool windows", location.y >= 0 && location.y + submit.height <= 600)
                val selectors = descendants(panel.sessions).filterIsInstance<AbstractButton>().filter { it.accessibleContext.accessibleName in setOf("Agent model and configuration", "Agent permission mode") }
                assertTrue("Model and permissions must share one toolbar row", kotlin.math.abs(selectors[0].y + selectors[0].height / 2 - selectors[1].y - selectors[1].height / 2) <= 2)
            }
            val previousLaf = javax.swing.UIManager.getLookAndFeel()
            val previousDark = !com.intellij.ui.JBColor.isBright()
            try {
                javax.swing.UIManager.setLookAndFeel(com.intellij.ide.ui.laf.darcula.DarculaLaf())
                com.intellij.ui.JBColor.setDark(true)
                com.intellij.openapi.util.IconLoader.setUseDarkIcons(true)
                SwingUtilities.updateComponentTreeUI(panel)
                render("workbench-chat-dark-320", panel, 320, 600)
                render("workbench-chat-dark-480", panel, 480, 760)
            } finally {
                javax.swing.UIManager.setLookAndFeel(previousLaf)
                com.intellij.ui.JBColor.setDark(previousDark)
                com.intellij.openapi.util.IconLoader.setUseDarkIcons(previousDark)
                SwingUtilities.updateComponentTreeUI(panel)
            }
            assertFalse("No four-destination tab shell", descendants(panel).filterIsInstance<javax.swing.JTabbedPane>().any { it.tabCount == 4 })
            val chat = panel.sessions
            for (page in listOf("History", "Settings", "Changes")) {
                panel.showPage(page)
                render("workbench-${page.lowercase()}-360", panel, 360)
                if (page == "History") {
                    val heading = descendants(panel).filterIsInstance<com.intellij.ui.components.JBLabel>().single { it.text == "Session history" }
                    val position = SwingUtilities.convertPoint(heading, 0, 0, panel)
                    assertTrue("History heading must stay visible near the top: $position / ${heading.size}; ${generateSequence<Component>(heading) { it.parent }.takeWhile { it !== panel }.joinToString { "${it.javaClass.simpleName} ${it.bounds} preferred=${it.preferredSize} minimum=${it.minimumSize}" }}", heading.width > 0 && heading.height > 0 && position.y in 0..120)
                }
                descendants(panel).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == "Back to conversation" }.doClick(0)
                assertEquals("Chat", panel.destination)
                assertSame(chat, panel.sessions)
                assertEquals("Keep this draft while reading history", prompt.text)
            }
            panel.update(EngineView(connected = true, agentSupported = true, agent = AgentView(id = "preview", status = "running", configOptions = configuration(), permissions = listOf(AgentPermission("request", "Run the test command?", "test-tool", listOf("once" to "Allow once"))))))
            render("workbench-approval-320", panel, 320, 600)
            panel.update(EngineView(connected = false, actionError = "Agent connection interrupted. Your draft is retained."))
            render("workbench-error-360", panel, 360, 600)
            descendants(panel).filterIsInstance<JComponent>().forEach { component -> component.font?.let { component.font = it.deriveFont(it.size2D + 3f) } }
            render("workbench-enlarged-font-360", panel, 360, 600)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testMarkdownUsesInertMarkupAndRetainsExactCode() {
        val raw = "# Header\n\nA **bold** response and `inline` code.\n\n```rust\nfn main() {\n    println!(\"👩🏽‍💻中\");\n}\n```\n\n<img src=\"https://example.invalid/track\">\n\n![remote](https://example.invalid/image)\n[run](javascript:bad())"
        val parsed = MarkdownMessage.parse(raw)
        val prose = parsed.filterNot { it.code }.joinToString("") { it.text }
        assertTrue(prose.contains("<h1>"))
        assertTrue(prose.contains("<b>bold</b>"))
        assertFalse(prose.contains("<img"))
        assertFalse(prose.contains("href="))
        assertTrue(prose.contains("&lt;img"))
        assertEquals("fn main() {\n    println!(\"👩🏽‍💻中\");\n}\n", parsed.single { it.code }.text)
        assertTrue(MarkdownMessage.parse("```rust\nlet partial = 1;").single { it.code }.text.contains("let partial = 1;"))
    }

    fun testConversationNeverReusesAnotherSessionsMarkdownOrToolIdentity() {
        val view = ConversationView()
        view.update(listOf(AgentEvent("message.delta", "Session one"), AgentEvent("operation.started", "Read file", "tool-a")), "one")
        view.update(listOf(AgentEvent("message.delta", "Session two"), AgentEvent("operation.started", "Read file", "tool-b")), "two")
        assertFalse(descendants(view).filterIsInstance<javax.swing.JEditorPane>().any { it.text.contains("Session one") })
        assertTrue(descendants(view).filterIsInstance<javax.swing.JEditorPane>().any { it.text.contains("Session two") })
        view.update(emptyList(), null)
        assertTrue(descendants(view).filterIsInstance<com.intellij.ui.components.JBLabel>().any { it.text == "What are we building?" })
        assertFalse(descendants(view).filterIsInstance<javax.swing.JEditorPane>().any())
    }

    private fun render(name: String, component: JComponent, width: Int, height: Int = 760) {
        // LightPlatformTestCase supplies non-painting DummyIcons. Resolve the same
        // SDK asset paths for previews; production uses the IDE's real icon manager.
        for (button in descendants(component).filterIsInstance<AbstractButton>()) {
            val icon = button.icon as? com.intellij.openapi.util.DummyIcon ?: continue
            val path = icon.originalPath ?: continue
            button.icon = com.intellij.openapi.util.IconLoader.getIcon(path, com.intellij.icons.AllIcons::class.java)
        }
        layout(component, width, height)
        UIUtil.dispatchAllInvocationEvents()
        layout(component, width, height)
        if (name == "chat-dark-480") {
            val composer = descendants(component).filterIsInstance<JComponent>().single { it.accessibleContext?.accessibleName == "Prompt composer" }
            assertTrue("Dark composer must not reserve a phantom wrapped row: ${composer.height}", composer.height <= 190)
        }
        for (button in descendants(component).filterIsInstance<AbstractButton>()) {
            if (generateSequence<Component>(button) { it.parent }.takeWhile { it !== component }.any { !it.isVisible }) continue
            assertTrue("Oversized button ${button.text}: ${button.height}", button.height in 1..52)
            val position = SwingUtilities.convertPoint(button, 0, 0, component)
            assertTrue("Clipped button ${button.accessibleContext.accessibleName}: $position / ${button.size}; parents ${generateSequence<Component>(button) { it.parent }.takeWhile { it !== component }.joinToString { "${it.javaClass.simpleName} ${it.bounds} preferred=${it.preferredSize} minimum=${it.minimumSize}" }}", position.x >= 0 && position.x + button.width <= width)
        }
        for (scroll in descendants(component).filterIsInstance<JScrollPane>()) {
            assertFalse("Horizontal scrollbars must not be needed", scroll.horizontalScrollBar.isVisible)
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        component.printAll(graphics)
        graphics.dispose()
        val output = Path.of("build/reports/ui-preview")
        Files.createDirectories(output)
        ImageIO.write(image, "png", output.resolve("$name.png").toFile())
    }

    private fun layout(root: JComponent, width: Int, height: Int) {
        root.setSize(width, height)
        repeat(12) {
            fun visit(component: Component) {
                if (component is Container) {
                    component.doLayout()
                    component.components.forEach(::visit)
                }
            }
            visit(root)
        }
    }
    private fun descendants(root: Container): List<Component> = root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }
}
