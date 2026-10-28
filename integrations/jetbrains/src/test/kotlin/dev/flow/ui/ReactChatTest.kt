package dev.flow.ui

import com.google.gson.JsonObject
import com.intellij.testFramework.LightPlatformTestCase
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
import kotlinx.coroutines.runBlocking

class ReactChatTest : LightPlatformTestCase() {
    fun testPromptHistoryBridgeIsProjectLocalAndCannotInjectUnsentPrompts() {
        val history = project.getService(PromptHistory::class.java)
        val saved = com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(history.state), PromptHistory.Preferences::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("prompt-cache"), { error("Prompt recall must not launch an engine") })
        val panel = AgentSessionPanel(controller, project)
        fun action(operation: String, revision: Long = history.revision, configure: JsonObject.() -> Unit = {}) = runBlocking {
            panel.webAction(
                JsonObject().apply {
                    addProperty("action", "promptHistory")
                    addProperty("operation", operation)
                    addProperty("revision", revision)
                    configure()
                },
            ).asJsonObject
        }
        try {
            history.loadState(PromptHistory.Preferences())
            history.record("A confirmed prompt")
            val result = action("list")
            assertEquals("A confirmed prompt", result.getAsJsonArray("items").single().asJsonObject["text"].asString)
            assertTrue(ReactChatPanel.ACTIONS.contains("promptHistory"))
            assertEquals(history.revision, panel.webState()["promptHistoryRevision"].asLong)
            assertTrue(runCatching { action("record") { addProperty("text", "Never submitted") } }.isFailure)
            assertTrue(runCatching { action("clear", revision = history.revision - 1) }.isFailure)
            assertTrue(runCatching { action("enable") { addProperty("enabled", "false") } }.isFailure)
            action("enable") { addProperty("enabled", false) }
            assertFalse(action("list")["enabled"].asBoolean)
            action("clear")
            assertEquals(0, action("list").getAsJsonArray("items").size())
            assertEquals("", panel.webState()["draft"].asString)
        } finally {
            panel.dispose()
            history.loadState(saved)
            scope.cancel()
        }
    }

    fun testEditsActionsRejectStaleSessionsBeforeDispatch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("edits-actions"), { error("No adapter needed") })
        var opened = false
        val panel = AgentSessionPanel(controller, project, openChanges = { opened = true })
        try {
            panel.update(EngineView(connected = true, agentSupported = true, agent = AgentView(id = "s1", status = "ready")))
            for (action in listOf("nativeDiff", "changesStats", "refreshChanges", "review", "complete", "openReference", "knowledge", "knowledgeOpen")) {
                val failure = runCatching {
                    runBlocking {
                        panel.webAction(
                            JsonObject().apply {
                                addProperty("action", action)
                                addProperty("sessionId", "different")
                            },
                        )
                    }
                }.exceptionOrNull()
                assertTrue("A stale $action must not dispatch", failure is IllegalStateException)
            }
            assertFalse(opened)
            runBlocking {
                panel.webAction(
                    JsonObject().apply {
                        addProperty("action", "review")
                        addProperty("sessionId", "s1")
                    },
                )
            }
            assertTrue(opened)
            assertTrue(ReactChatPanel.ACTIONS.containsAll(listOf("nativeDiff", "changesStats", "refreshChanges")))
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testCodeExplorerRejectsArbitraryMethodsRootsAndSessions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("code-explorer"), { error("Invalid requests must not launch an engine") })
        val panel = AgentSessionPanel(controller, project)
        try {
            panel.update(EngineView(connected = true, agentSupported = true, agent = AgentView(id = "s1", status = "ready")))
            fun invalid(operation: String, workspace: String = "project", params: String = "{}") = runCatching {
                runBlocking {
                    panel.webAction(com.google.gson.JsonParser.parseString("""{"action":"knowledge","sessionId":"s1","workspace":"$workspace","operation":"$operation","params":$params}""").asJsonObject)
                }
            }.exceptionOrNull()
            assertTrue(invalid("execute") is IllegalArgumentException)
            assertTrue(invalid("find", workspace = "/another/root") is IllegalArgumentException)
            assertTrue(invalid("find", params = """{"root":"/another/root"}""") is IllegalArgumentException)
            assertTrue(invalid("find", params = """{"sessionId":"other"}""") is IllegalArgumentException)
            assertTrue(ReactChatPanel.ACTIONS.containsAll(listOf("knowledge", "knowledgeOpen")))
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testChatToolCardsKeepTheirPlaceAndReplyActionsUseTheProjectedIndex() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("react-tools"), { error("Rendering tool events must not launch a process") })
        val panel = AgentSessionPanel(controller, project)
        val tool = AgentEvent("operation.started", "Read", "call-1", "t1", tool = dev.flow.bridge.AgentToolCall(title = "Read source", kind = "read", status = "pending", input = "source.rs"))
        val events = listOf(AgentEvent("turn.started", "Explain source", turnId = "t1"), AgentEvent("message.delta", "I will read it.", turnId = "t1"), tool, AgentEvent("message.delta", "The result.", turnId = "t1"))
        try {
            panel.update(EngineView(connected = true, agentSupported = true, agent = AgentView(id = "s1", status = "running", events = events)))
            val first = panel.webState()
            assertEquals(4, first.getAsJsonArray("messages").size())
            assertEquals("tool.call", first.getAsJsonArray("messages")[2].asJsonObject["kind"].asString)
            val updated = events + AgentEvent("operation.finished", "Done", "call-1", "t1", tool = dev.flow.bridge.AgentToolCall(status = "completed", output = "file contents")) + AgentEvent("turn.completed", "Lifecycle stays hidden", turnId = "t1")
            panel.update(EngineView(connected = true, agentSupported = true, agent = AgentView(id = "s1", status = "ready", events = updated)))
            val messages = panel.webState().getAsJsonArray("messages")
            assertEquals(4, messages.size())
            assertEquals("completed", messages[2].asJsonObject.getAsJsonObject("tool")["status"].asString)
            assertEquals("Read source", messages[2].asJsonObject["text"].asString)
            assertEquals("file contents", messages[2].asJsonObject.getAsJsonObject("tool")["output"].asString)
            assertEquals("pending", first.getAsJsonArray("messages")[2].asJsonObject.getAsJsonObject("tool")["status"].asString)
            runBlocking {
                panel.webAction(
                    JsonObject().apply {
                        addProperty("action", "copy")
                        addProperty("sessionId", "s1")
                        addProperty("index", 3)
                        addProperty("turnId", "t1")
                    },
                )
            }
            assertEquals("The result.", com.intellij.openapi.ide.CopyPasteManager.getInstance().contents!!.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor))
            assertFalse(messages.toString().contains("Lifecycle stays hidden"))
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testAgentSelectionIsLocalAndBoundToTheExpectedProfileAndConversation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("react-agents"), { error("Choosing an agent must not launch a process") })
        val selection = project.getService(AgentSelection::class.java)
        val settings = com.intellij.openapi.application.ApplicationManager.getApplication().getService(CodexSettings::class.java)
        val savedProfile = selection.profile
        val savedSettings = settings.state
        selection.select("automatic")
        val panel = AgentSessionPanel(controller, project)
        fun choose(next: String, previous: String = panel.webState()["profile"].asString, session: String = "") = runBlocking {
            panel.webAction(
                JsonObject().apply {
                    addProperty("action", "provider")
                    addProperty("sessionId", session)
                    addProperty("currentProfile", previous)
                    addProperty("value", next)
                },
            )
        }
        try {
            panel.update(EngineView(connected = true, agentSupported = true))
            runBlocking {
                panel.webAction(
                    JsonObject().apply {
                        addProperty("action", "draft")
                        addProperty("version", 1)
                        addProperty("text", "Keep this draft")
                    },
                )
            }
            assertTrue(runCatching { choose("codex", session = "stale-session") }.isFailure)
            for (profile in listOf("claude", "codex", "cursor", "automatic")) {
                choose(profile)
                assertEquals(profile, panel.webState()["profile"].asString)
                assertEquals(profile, selection.profile)
                assertEquals(profile, settings.state.adapterProfile)
                assertEquals("Keep this draft", panel.webState()["draft"].asString)
            }
            assertTrue(runCatching { choose("codex", previous = "claude") }.isFailure)
            assertTrue(runCatching { choose("arbitrary-executable") }.isFailure)
            val agent = AgentView(id = "active", status = "ready", agentName = "codex-acp", events = listOf(AgentEvent("message.delta", "Existing reply", turnId = "t1")))
            panel.update(EngineView(connected = true, agentSupported = true, agent = agent))
            assertEquals("codex", panel.webState()["provider"].asString)
            assertFalse(panel.webState()["providerEnabled"].asBoolean)
            assertTrue(runCatching { choose("claude", session = "active") }.isFailure)
            assertEquals("Existing reply", panel.webState().getAsJsonArray("messages")[0].asJsonObject["text"].asString)
            panel.update(EngineView(connected = true, agentSupported = true, agent = agent.copy(status = "closed")))
            assertTrue(runCatching { choose("claude") }.isFailure)
            choose("claude", session = "active")
            assertEquals("claude", selection.profile)
        } finally {
            panel.dispose()
            selection.select(savedProfile)
            settings.loadState(savedSettings)
            scope.cancel()
        }
    }

    fun testModelCatalogueAndConfigurationStayBoundToTheConfirmedSession() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("react-models"), { error("Model browsing must not launch an agent") })
        val panel = AgentSessionPanel(controller, project)
        val options = dev.flow.bridge.AgentProtocol.configOptions(
            com.google.gson.JsonParser.parseString(
                """[
          {"id":"model","name":"Model","description":"","category":"model","kind":"select","currentValue":"a","options":[
            {"value":"a","name":"Model A","description":"","group":""},{"value":"b","name":"Model B","description":"","group":""}]},
          {"id":"mode","name":"Permissions","description":"","category":"mode","kind":"mode","currentValue":"ask","options":[{"value":"ask","name":"Ask","description":"","group":""},{"value":"plan","name":"Plan","description":"","group":""}]},
          {"id":"fast","name":"Fast mode","description":"","category":"","kind":"boolean","currentValue":false,"options":[]},
          {"id":"collaboration_mode","name":"Collaboration mode","description":"","category":"collaboration_mode","kind":"select","currentValue":"default","options":[{"value":"default","name":"Default","description":"","group":""},{"value":"plan","name":"Plan","description":"","group":""}]}
        ]""",
            ).asJsonArray,
        )
        val agent = AgentView(id = "models", status = "ready", configOptions = options)
        fun call(action: String = "configure", session: String = "models", config: String = "model", before: com.google.gson.JsonElement = com.google.gson.JsonPrimitive("a"), value: com.google.gson.JsonElement = com.google.gson.JsonPrimitive("b"), model: String = "a") = runBlocking {
            panel.webAction(
                JsonObject().apply {
                    addProperty("action", action)
                    addProperty("sessionId", session)
                    addProperty("configId", config)
                    add("currentValue", before)
                    addProperty("modelValue", model)
                    add("value", value)
                },
            )
        }
        try {
            panel.update(EngineView(connected = true, agentSupported = true, agent = agent))
            val state = panel.webState()
            assertEquals("b", state.getAsJsonArray("configOptions")[0].asJsonObject.getAsJsonArray("options")[1].asJsonObject["value"].asString)
            assertSame("Unchanged catalogues should not be serialized on every draft update", state["configOptions"], panel.webState()["configOptions"])
            assertTrue(state["configReady"].asBoolean)
            assertEquals(setOf("sessionId", "configId", "value"), dev.flow.bridge.AgentProtocol.configure("models", options[0], com.google.gson.JsonPrimitive("b")).keySet())
            call("model") // Browsing the advertised list must never open a native dialog.
            call(value = com.google.gson.JsonPrimitive("a")) // Choosing the confirmed value is a no-op.
            call(action = "mode", config = "collaboration_mode", before = com.google.gson.JsonPrimitive("default"), value = com.google.gson.JsonPrimitive("default"))
            call(action = "mode", config = "mode", before = com.google.gson.JsonPrimitive("ask"), value = com.google.gson.JsonPrimitive("ask"))
            assertTrue(runCatching { call(action = "mode", config = "model") }.isFailure)
            assertTrue(runCatching { call(config = "collaboration_mode", before = com.google.gson.JsonPrimitive("default"), value = com.google.gson.JsonPrimitive("plan")) }.isFailure)
            assertTrue(runCatching { call(action = "mode", config = "collaboration_mode", session = "old", before = com.google.gson.JsonPrimitive("default"), value = com.google.gson.JsonPrimitive("plan")) }.isFailure)
            assertTrue(runCatching { call(action = "mode", config = "collaboration_mode", before = com.google.gson.JsonPrimitive("plan"), value = com.google.gson.JsonPrimitive("default")) }.isFailure)
            assertTrue(runCatching { call(action = "mode", config = "collaboration_mode", before = com.google.gson.JsonPrimitive("default"), value = com.google.gson.JsonPrimitive(true)) }.isFailure)
            assertTrue(runCatching { call(action = "mode", config = "collaboration_mode", before = com.google.gson.JsonPrimitive("default"), value = com.google.gson.JsonPrimitive("unsupported")) }.isFailure)
            assertTrue(runCatching { call(session = "old-session") }.isFailure)
            assertTrue(runCatching { call(before = com.google.gson.JsonPrimitive("old-value")) }.isFailure)
            assertTrue(runCatching { call(model = "previous-model") }.isFailure)
            assertTrue(runCatching { call(value = com.google.gson.JsonPrimitive("unadvertised")) }.isFailure)
            assertTrue(runCatching { call(config = "fast", before = com.google.gson.JsonPrimitive(false), value = com.google.gson.JsonPrimitive("true")) }.isFailure)
            assertTrue(runCatching { call(config = "mode", before = com.google.gson.JsonPrimitive("ask"), value = com.google.gson.JsonPrimitive("auto")) }.isFailure)
            panel.update(EngineView(connected = true, agentSupported = true, agent = agent.copy(status = "running")))
            assertFalse(panel.webState()["configReady"].asBoolean)
            assertTrue(runCatching { call() }.isFailure)
            panel.update(EngineView(connected = false, agent = agent))
            assertTrue(runCatching { call() }.isFailure)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testDraftPatchesReuseMessagesAndClearExpiredApprovals() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("react-patches"), { error("No process should launch") })
        val panel = AgentSessionPanel(controller, project)
        try {
            val events = listOf(AgentEvent("turn.started", "Fix this", turnId = "t1"), AgentEvent("message.delta", "Hello", turnId = "t1"))
            val agent = AgentView(id = "s1", status = "ready", events = events, permissions = listOf(AgentPermission("p1", "Edit", "tool", listOf("once" to "Allow once"))))
            panel.update(EngineView(connected = true, agentSupported = true, agent = agent))
            val before = panel.webState()
            assertEquals(before, ReactChatPanel.changedState(null, before))
            assertEquals(0, ReactChatPanel.changedState(before, panel.webState()).size())
            runBlocking {
                panel.webAction(
                    JsonObject().apply {
                        addProperty("action", "draft")
                        addProperty("version", 1)
                        addProperty("text", "my local draft")
                    },
                )
            }
            val edited = panel.webState()
            assertSame("Draft changes must not reproject the entire transcript", before["messages"], edited["messages"])
            val patch = ReactChatPanel.changedState(before, edited)
            assertEquals("my local draft", patch["draft"].asString)
            assertFalse(patch.has("messages"))
            panel.update(EngineView(connected = true, agentSupported = true, agent = agent.copy(events = events + AgentEvent("thinking.delta", "private reasoning"), permissions = emptyList())))
            val progress = panel.webState()
            assertSame(edited["messages"], progress["messages"])
            assertTrue(ReactChatPanel.changedState(edited, progress)["permission"].isJsonNull)
            assertFalse(before["permission"].isJsonNull)
            panel.update(EngineView(connected = true, agentSupported = true, agent = agent.copy(events = events + AgentEvent("message.delta", " world", turnId = "t1"))))
            val changed = panel.webState()
            assertEquals("Hello world", changed.getAsJsonArray("messages")[1].asJsonObject["text"].asString)
            assertTrue(ReactChatPanel.changedState(progress, changed).has("messages"))
            assertEquals("Hello", before.getAsJsonArray("messages")[1].asJsonObject["text"].asString)
            assertTrue(ReactChatPanel.changedState(before, JsonObject())["permission"].isJsonNull)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testActivityFollowsRealEventsAndResetsAfterTheTurn() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("react-activity"), { error("No process should launch") })
        val panel = AgentSessionPanel(controller, project)
        try {
            var events = listOf(AgentEvent("turn.started", "Explain the function", turnId = "t1"))
            fun update(status: String = "running", permissions: List<AgentPermission> = emptyList(), connected: Boolean = true): JsonObject {
                panel.update(EngineView(connected = connected, agentSupported = true, agent = AgentView(id = "s1", status = status, agentName = "claude", events = events, permissions = permissions)))
                return panel.webState().getAsJsonObject("activity")
            }
            val started = update()
            assertEquals("working", started["phase"].asString)
            val time = started["startedAt"].asLong
            fun event(kind: String, phase: String) {
                events += AgentEvent(kind, "private event payload", turnId = "t1")
                val activity = update()
                assertEquals(phase, activity["phase"].asString)
                assertEquals("Phase changes must not reset the elapsed turn time", time, activity["startedAt"].asLong)
            }
            event("thinking.delta", "thinking")
            assertFalse("Thought content is not a chat message", panel.webState().getAsJsonArray("messages").toString().contains("private event payload"))
            event("operation.started", "tools")
            event("operation.updated", "tools")
            assertEquals("approval", update(permissions = listOf(AgentPermission("p1", "Edit", "tool", listOf("once" to "Allow once"))))["phase"].asString)
            event("operation.finished", "working")
            event("message.delta", "responding")
            assertEquals("Responding…", panel.webState()["progress"].asString)
            assertEquals("stopping", update("cancelling")["phase"].asString)
            assertEquals("idle", update("ready")["phase"].asString)
            assertTrue(panel.webState().getAsJsonObject("activity")["startedAt"].isJsonNull)
            events += AgentEvent("turn.started", "Next task", turnId = "t2")
            val next = update()
            assertEquals("working", next["phase"].asString)
            assertEquals("t2", next["turnId"].asString)
            assertEquals("idle", update(connected = false)["phase"].asString)
            assertEquals("idle", update("failed")["phase"].asString)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testEnhancementCursorContextStaysBoundedAndPreservesUnicode() {
        val prefix = "line one\n" + "あ".repeat(3000)
        val text = prefix + "🦀fn selected() {}\n" + "🦀".repeat(2000)
        val context = PromptEnhancement.cursorContext(text, prefix.length + 2)
        assertTrue(context["text"].asString.contains("fn selected() {}"))
        assertTrue(context["text"].asString.toByteArray().size <= 8192)
        val snippet = context["text"].asString
        assertFalse(snippet.first().isLowSurrogate())
        assertFalse(snippet.last().isHighSurrogate())
        assertEquals(2, context["startLine"].asInt)
        assertTrue(context["truncated"].asBoolean)
        assertEquals("", PromptEnhancement.cursorContext("", 0)["text"].asString)
    }

    fun testBridgeRejectsUntrustedUnknownAndOversizedRequests() {
        fun envelope(token: String = "secret", action: String = "draft", id: String = "1") = """{"token":"$token","payload":{"id":$id,"action":"$action"}}"""
        assertEquals("draft", ReactChatPanel.parse(envelope(), "secret")["action"].asString)
        listOf(envelope("wrong"), envelope(action = "sessions.start"), envelope(id = "0"), envelope(id = "1.5"), envelope(id = "\"1\""), "x".repeat(12 * 1024 * 1024 + 1)).forEach { value ->
            assertTrue("Invalid requests must never reach the engine", runCatching { ReactChatPanel.parse(value, "secret") }.isFailure)
        }
        val html = ReactChatPanel.document("fixture", "window.flowPost = () => {};")
        assertTrue(html.contains("script-src 'nonce-fixture'"))
        assertTrue(html.contains("connect-src 'none'"))
        assertTrue(html.contains("frame-src 'none'"))
        assertFalse(html.contains("src=\"https:"))
        assertFalse("Production bundle has no Node globals", html.contains("process.env.NODE_ENV"))
    }

    fun testSharedProjectionAndDraftSessionGuards() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("react-guard"), { error("No process should launch") })
        val panel = AgentSessionPanel(controller, project)
        try {
            val agent = AgentView(
                id = "s1",
                status = "ready",
                agentName = "claude",
                events = listOf(
                    AgentEvent("turn.started", "Fix this", turnId = "t1"),
                    AgentEvent("message.delta", "Hello", turnId = "t1"),
                    AgentEvent("tool.started", "do not render this", turnId = "t1"),
                    AgentEvent("message.delta", " world", turnId = "t1"),
                ),
                permissions = listOf(AgentPermission("p1", "Edit file", "edit", listOf("once" to "Allow once"))),
            )
            panel.update(EngineView(connected = true, agentSupported = true, agent = agent))
            val messages = panel.webState().getAsJsonArray("messages")
            assertEquals(2, messages.size())
            assertEquals("Hello world", messages[1].asJsonObject["text"].asString)
            assertFalse(messages.toString().contains("do not render"))
            fun call(action: String, session: String = "s1", version: Long = 1, text: String = "my draft", extra: JsonObject.() -> Unit = {}) = runBlocking {
                panel.webAction(
                    JsonObject().apply {
                        addProperty("action", action)
                        addProperty("sessionId", session)
                        addProperty("version", version)
                        addProperty("text", text)
                        extra()
                    },
                )
            }
            call("draft", version = 2)
            assertTrue(runCatching { call("draft", version = 1, text = "stale draft") }.isFailure)
            assertEquals("my draft", panel.webState()["draft"].asString)
            assertTrue(runCatching { call("send", session = "s0", version = 3) }.isFailure)
            assertTrue(
                runCatching {
                    call("permission") {
                        addProperty("requestId", "p1")
                        addProperty("option", "invented")
                    }
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    call("permission") {
                        addProperty("requestId", "expired")
                        addProperty("option", "once")
                    }
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    call("upload") {
                        addProperty("name", "../escape")
                        addProperty("data", "")
                    }
                }.isFailure,
            )
            panel.update(EngineView(connected = false, agent = agent))
            assertEquals("my draft", panel.webState()["draft"].asString)
            assertTrue(runCatching { call("queue") { addProperty("operation", "resume") } }.isFailure)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }
}
