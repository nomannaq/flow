package dev.flow.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.LightPlatformTestCase
import dev.flow.bridge.AgentEvent
import dev.flow.bridge.AgentProtocol
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel

class ConversationFeaturesTest : LightPlatformTestCase() {
    fun testToolPatchesStayBetweenRepliesAndRetainDisclosureAndTurnIdentity() {
        fun event(json: String) = AgentProtocol.event(JsonParser.parseString(json).asJsonObject)
        val start = event("""{"kind":"operation.started","text":"Reading","toolCallId":"same","turnId":"t1","tool":{"title":"Read Main.java","kind":"read","status":"pending","input":"{\"path\":\"Main.java\"}","locations":[{"path":"Main.java","line":0}]}}""")
        val done = event("""{"kind":"operation.finished","text":"Completed","toolCallId":"same","turnId":"t1","tool":{"status":"completed","output":"public class Main {}"}}""")
        val events = listOf(AgentEvent("turn.started", "Explain Main.java", turnId = "t1"), AgentEvent("message.delta", "I will read it.", turnId = "t1"), start, AgentEvent("message.delta", "It is ", turnId = "t1"), done, AgentEvent("message.delta", "a starter project.", turnId = "t1"))
        val rows = ConversationView.messages(events)
        assertEquals(listOf("turn.started", "message.delta", "tool.call", "message.delta"), rows.map { it.kind })
        assertEquals("Read Main.java", rows[2].text)
        assertEquals("completed", rows[2].tool!!.status)
        assertEquals(start.tool!!.input, rows[2].tool!!.input)
        assertEquals("It is a starter project.", rows[3].text)
        val extended = ConversationView.messages(events + start.copy(turnId = "t2") + AgentEvent("turn.completed", "Finished", turnId = "t2"))
        assertEquals("completed", extended[2].tool!!.status)
        assertEquals("unknown", extended.last().tool!!.status)
        val cancelled = ConversationView.messages(listOf(start, AgentEvent("turn.cancelling", "Stopping", turnId = "t1"), AgentEvent("turn.completed", "Stopped", turnId = "t1")))
        assertEquals("interrupted", cancelled.single().tool!!.status)
        val clear = event("""{"kind":"operation.updated","text":"No output","toolCallId":"same","turnId":"t1","tool":{"output":"","locations":[],"diffs":[]}}""")
        assertTrue(ConversationView.messages(events + clear)[2].tool!!.locations!!.isEmpty())
        val view = ConversationView()
        try {
            view.update(events.take(3), "session")
            val card = descendants(view).filterIsInstance<ToolCallView>().single()
            descendants(card).filterIsInstance<AbstractButton>().single().doClick(0)
            view.update(events.take(2) + start.copy(tool = start.tool!!.copy(status = "in_progress")), "session")
            assertSame(card, descendants(view).filterIsInstance<ToolCallView>().single())
            assertTrue(descendants(card).filterIsInstance<com.intellij.ui.components.JBTextArea>().single().isVisible)
            assertTrue(descendants(card).filterIsInstance<com.intellij.ui.components.JBTextArea>().single().text.contains("Main.java:0"))
            assertTrue(descendants(card).filterIsInstance<AbstractButton>().single().text.contains("Running"))
        } finally {
            view.dispose()
        }
        val oversized = JsonParser.parseString("""{"kind":"operation.updated","text":"Bad","toolCallId":"call","tool":{"output":""}}""").asJsonObject
        oversized.getAsJsonObject("tool").addProperty("output", "x".repeat(4097))
        assertFails { AgentProtocol.event(oversized) }
        val untrusted = ToolCallView(start.copy(text = "<html><b>Untrusted title</b>"))
        val header = descendants(untrusted).filterIsInstance<AbstractButton>().single()
        assertEquals(true, header.getClientProperty("html.disable"))
        assertNull(header.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey))
    }

    private fun page(session: String = "retained", through: String = "10", next: String? = null, vararg rows: Triple<String, String, String>) = JsonObject().apply {
        addProperty("sessionId", session)
        addProperty("through", through)
        addProperty("next", next)
        add(
            "items",
            com.google.gson.JsonArray().apply {
                rows.forEach { (sequence, kind, text) ->
                    add(
                        JsonObject().apply {
                            addProperty("sequence", sequence)
                            addProperty("kind", kind)
                            addProperty("text", text)
                            addProperty("legacy", false)
                        },
                    )
                }
            },
        )
    }

    fun testTranscriptExportPreservesChunksAndPinsItsSnapshot() {
        val journal = ConversationJournal("retained")
        journal.append(page(next = "3", rows = arrayOf(Triple("1", "turn.started", "A long "), Triple("2", "turn.prompt.delta", "prompt 🦀"), Triple("3", "message.delta", "Hello "))))
        assertEquals("10", journal.params()["through"].asString)
        assertEquals("3", journal.params()["after"].asString)
        assertFails { journal.export(false) }
        journal.append(page(rows = arrayOf(Triple("4", "message.delta", "world\n```rust\nfn main() {}\n```"))))
        val markdown = journal.export(false)
        assertTrue(markdown.contains("A long prompt 🦀"))
        assertTrue(markdown.contains("Hello world\n```rust"))
        val json = JsonParser.parseString(journal.export(true)).asJsonObject
        assertEquals("10", json["through"].asString)
        assertEquals(4, json.getAsJsonArray("events").size())
        assertFalse(json["legacyContentMayBeClipped"].asBoolean)
        assertFails { journal.append(page(session = "another")) }
        assertFails { journal.append(page(through = "11")) }
        assertFails { journal.append(page(rows = arrayOf(Triple("2", "message.delta", "reordered")))) }
        assertEquals(4, journal.entries.size)
    }

    fun testNativeConversationFindAndQuoteUseJoinedCurrentText() {
        var quoted = ""
        val view = ConversationView(quote = { quoted = it })
        try {
            view.update(listOf(AgentEvent("turn.started", "Question"), AgentEvent("message.delta", "Hello "), AgentEvent("message.delta", "world")), "session")
            view.showFind()
            val field = descendants(view).filterIsInstance<com.intellij.ui.components.JBTextField>().single()
            field.text = "hello world"
            descendants(view).filterIsInstance<AbstractButton>().single { it.text == "Next" }.doClick(0)
            assertTrue(descendants(view).filterIsInstance<JLabel>().any { it.text == "1 of 1 matching messages" })
            descendants(view).filterIsInstance<AbstractButton>().filter { it.accessibleContext.accessibleName == "Quote" }.last().doClick(0)
            assertEquals("Hello world", quoted)
            view.update(listOf(AgentEvent("turn.started", "New question")), "replacement")
            assertFalse(descendants(view).filterIsInstance<com.intellij.ui.components.JBTextArea>().any { it.text == "Hello world" })
        } finally {
            view.dispose()
        }
    }

    fun testAdvertisedCommandsAndUsageAreBoundedAndOptional() {
        val data = JsonParser.parseString("""{"sessionId":"session","status":"ready","activity":[],"permissions":[],"availableCommands":[{"name":"review","description":"Review project","inputHint":"Optional scope"}],"usage":{"used":"12345","size":"18446744073709551615"}}""").asJsonObject
        val view = AgentProtocol.view(data)
        assertEquals("review", view.availableCommands.single().name)
        assertEquals("Optional scope", view.availableCommands.single().inputHint)
        assertEquals("18446744073709551615", view.usage!!.size)
        data.getAsJsonObject("usage").addProperty("used", "18446744073709551616")
        assertFails { AgentProtocol.view(data) }
        data.remove("usage")
        data.getAsJsonArray("availableCommands")[0].asJsonObject.addProperty("name", "bad\ncommand")
        assertFails { AgentProtocol.view(data) }
        data.remove("availableCommands")
        assertTrue(AgentProtocol.view(data).availableCommands.isEmpty())
        assertNull(AgentProtocol.view(data).usage)
    }

    fun testTablesAndFontPreferencesRetainNativeRenderingAndProviderSettings() {
        val text = "| Name | State |\n| --- | --- |\n| engine | **ready** |\n\n~~old~~ <img src='https://invalid.example/tracker'>"
        val html = MarkdownMessage.parse(text).joinToString("") { it.text }
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<td>"))
        assertTrue(html.contains("engine"))
        assertTrue(html.contains("<strike>"))
        assertFalse(html.contains("<img"))
        val preferences = CodexSettings()
        preferences.setConversationFontOffset(2)
        preferences.save("/native/adapter", "model", "codex")
        preferences.selectProfile("claude")
        assertEquals(2, preferences.state.conversationFontOffset)
        preferences.loadState(CodexSettings.Preferences().apply { conversationFontOffset = 999 })
        assertEquals(12, preferences.state.conversationFontOffset)
    }

    fun testImageAttachmentsAreFrozenBoundedAndIncludedInHistoryExport() {
        val image = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        val picture = PromptAttachments.normalize("diagram.png", bytes)
        assertEquals("image/png", picture.mimeType)
        assertTrue(java.util.Base64.getDecoder().decode(picture.data).contentEquals(bytes))
        assertTrue(picture.json().get("thumbnail") == null)
        val large = java.awt.image.BufferedImage(1200, 1200, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val random = java.util.Random(42)
        for (y in 0 until large.height) for (x in 0 until large.width) large.setRGB(x, y, random.nextInt())
        val optimized = PromptAttachments.normalize("large.png", java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(large, "png", it) }.toByteArray())
        assertTrue(optimized.name.startsWith("Optimized"))
        assertTrue(java.util.Base64.getDecoder().decode(optimized.data).size <= 128 * 1024)
        assertFails { PromptAttachments.normalize("fake.png", "not an image".toByteArray()) }
        val journal = ConversationJournal("retained")
        val page = page(rows = arrayOf(Triple("1", "turn.started", "Explain the diagram")))
        page.getAsJsonArray("items")[0].asJsonObject.apply {
            addProperty("turnId", "turn-image")
            add("images", JsonParser.parseString("""[{"id":"${"a".repeat(64)}","name":"diagram.png","mimeType":"image/png","size":${bytes.size}}]"""))
        }
        journal.append(page)
        assertEquals("turn-image", journal.events().single().turnId)
        val attachments = JsonObject().apply { add("a".repeat(64), picture.json()) }
        assertTrue(journal.export(false, attachments).contains("data:image/png;base64,${picture.data}"))
        assertTrue(JsonParser.parseString(journal.export(true, attachments)).asJsonObject.getAsJsonObject("attachments").has("a".repeat(64)))
    }

    fun testRewindTargetsTheRecordedTurnAndQueueMetadataIsBounded() {
        var selected = ""
        val view = ConversationView(rewind = { source, turn -> selected = "$source:${turn.turnId}" })
        try {
            view.update(listOf(AgentEvent("turn.started", "Change files", turnId = "turn-2")), "session")
            descendants(view).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == "Rewind" }.doClick(0)
            assertEquals("session:turn-2", selected)
        } finally {
            view.dispose()
        }
        val data = JsonParser.parseString("""{"sessionId":"session","status":"running","activity":[],"permissions":[],"capabilities":{"promptCapabilities":{"image":true}},"queue":[{"id":"q-1","preview":"Later","status":"pending","imageCount":2}],"queuePaused":true}""").asJsonObject
        val agent = AgentProtocol.view(data)
        assertTrue(agent.supportsImages)
        assertTrue(agent.queuePaused)
        assertEquals(2, agent.queue.single().imageCount)
        data.getAsJsonArray("queue")[0].asJsonObject.addProperty("imageCount", 5)
        assertFails { AgentProtocol.view(data) }
    }

    fun testConversationContainsOnlyMessagesNotLifecycleThinkingOrTools() {
        val view = ConversationView()
        try {
            view.update(
                listOf(
                    AgentEvent("session.ready", "SESSION STATE"),
                    AgentEvent("turn.started", "User question"),
                    AgentEvent("operation.started", "TOOL STATE"),
                    AgentEvent("thinking.delta", "PRIVATE THINKING"),
                    AgentEvent("context.prompt", "CONTEXT STATE"),
                    AgentEvent("message.delta", "Agent answer"),
                    AgentEvent("workspace.captureIncomplete", "CAPTURE STATE"),
                    AgentEvent("session.closed", "CLOSED STATE"),
                ),
                "session",
            )
            val text = descendants(view).filterIsInstance<com.intellij.ui.components.JBTextArea>().joinToString { it.text }
            assertTrue(text.contains("User question"))
            assertFalse(text.contains("STATE") || text.contains("PRIVATE THINKING"))
            assertTrue(descendants(view).filterIsInstance<javax.swing.JEditorPane>().any { it.text.contains("Agent answer") })
        } finally {
            view.dispose()
        }
    }

    fun testNativeModelListAppliesOnClickButNotKeyboardHighlight() {
        val option = dev.flow.bridge.AgentConfigOption("model", "Model", "", "model", "select", com.google.gson.JsonPrimitive("a"), listOf(dev.flow.bridge.AgentConfigChoice("a", "Model A"), dev.flow.bridge.AgentConfigChoice("b", "Model B")))
        var selected: String? = null
        val content = AgentConfigurationPopup.content(listOf(option)) { _, value -> selected = value.asString }
        val list = descendants(content).filterIsInstance<com.intellij.ui.components.JBList<*>>().single()
        list.setSize(350, 180)
        list.selectedIndex = 1
        assertNull("Moving the highlight must not change the model", selected)
        val bounds = list.getCellBounds(1, 1)
        list.dispatchEvent(java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, bounds.x + 4, bounds.y + 4, 1, false, java.awt.event.MouseEvent.BUTTON1))
        assertEquals("b", selected)
    }

    fun testGenericAttachmentsAreFrozenSeparateFromInlineImagesAndSurviveDraftRecovery() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val controller = dev.flow.bridge.EngineController(scope, dev.flow.bridge.ProjectId("file-attachments"), { error("No process") })
        val ref = dev.flow.bridge.AgentAttachmentRef("d".repeat(64), "reference/report.pdf", "application/pdf", 800000)
        var selected: String? = null
        val files = PromptAttachments(project, controller, request = { method, params ->
            assertEquals("attachments.import", method)
            selected = params.getAsJsonArray("paths").single().asString
            JsonObject().apply { add("items", com.google.gson.JsonArray().apply { add(ref.json()) }) }
        })
        try {
            files.importFiles(listOf("/chosen/report.pdf"))
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Selected PDF should be imported", { runCatching { files.snapshot().files().size() == 1 }.getOrDefault(false) }, 10)
            assertEquals("/chosen/report.pdf", selected)
            val frozen = files.snapshot()
            assertFalse("Files do not require the image prompt capability", frozen.hasInlineImages)
            assertEquals(0, frozen.json().size())
            assertEquals(ref.json(), frozen.files()[0])
            files.restore(com.google.gson.JsonArray(), frozen.files())
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Recovered file draft should settle", { runCatching { files.snapshot().revision > frozen.revision }.getOrDefault(false) }, 10)
            files.acknowledge(frozen)
            assertEquals("A late send acknowledgement must preserve a newer file draft", ref.json(), files.snapshot().files()[0])
            val journal = ConversationJournal("file-session")
            val page = page(session = "file-session", rows = arrayOf(Triple("1", "turn.started", "Read this PDF")))
            page.getAsJsonArray("items")[0].asJsonObject.add("attachments", frozen.files())
            journal.append(page)
            assertEquals(ref, journal.events().single().attachments.single())
            val content = JsonObject().apply { add(ref.id, ref.json().apply { addProperty("data", "UEZG") }) }
            assertTrue(journal.export(false, content).contains("data:application/pdf;base64,UEZG"))
            val invalid = ref.json().apply { addProperty("size", 33 * 1024 * 1024) }
            assertFails { AgentProtocol.attachmentRefs(com.google.gson.JsonArray().apply { add(invalid) }) }
        } finally {
            files.dispose()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    fun testPastedImagesUseFileImportCleanTemporaryFilesAndRespectDraftClears() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val controller = dev.flow.bridge.EngineController(scope, dev.flow.bridge.ProjectId("clipboard-file"), { error("No process") })
        var imported: java.nio.file.Path? = null
        val files = PromptAttachments(project, controller, request = { method, params ->
            assertEquals("attachments.import", method)
            val path = java.nio.file.Path.of(params.getAsJsonArray("paths").single().asString)
            val bytes = java.nio.file.Files.readAllBytes(path)
            assertTrue(bytes.size > 8)
            assertEquals(4, PromptAttachments.decode(bytes).width)
            imported = path
            JsonObject().apply { add("items", com.google.gson.JsonArray().apply { add(dev.flow.bridge.AgentAttachmentRef("e".repeat(64), "Clipboard image.png", "image/png", bytes.size).json()) }) }
        })
        try {
            val image = java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            files.attachImage(image)
            files.clear()
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Cancelled clipboard draft should settle", {
                runCatching {
                    files.snapshot()
                    true
                }.getOrDefault(false)
            }, 10)
            assertNull("A cleared draft must not import an old clipboard image", imported)
            files.attachImage(image)
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Clipboard image should become a frozen file", { runCatching { files.snapshot().files().size() == 1 }.getOrDefault(false) }, 10)
            assertFalse(files.snapshot().hasInlineImages)
            assertFalse("Temporary clipboard originals must be removed after durable import", java.nio.file.Files.exists(imported))
        } finally {
            files.dispose()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    fun testFileDropHandlerPreservesNormalTextCopyAndPaste() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val controller = dev.flow.bridge.EngineController(scope, dev.flow.bridge.ProjectId("text-transfer"), { error("No process") })
        val panel = AgentSessionPanel(controller, project)
        try {
            val composer = descendants(panel).filterIsInstance<javax.swing.JComponent>().single { it.accessibleContext?.accessibleName == "Prompt composer" }
            val prompt = descendants(composer).filterIsInstance<com.intellij.ui.components.JBTextArea>().single { it.isEditable }
            prompt.text = "original draft"
            prompt.selectAll()
            val clipboard = java.awt.datatransfer.Clipboard("local test clipboard")
            prompt.transferHandler.exportToClipboard(prompt, clipboard, javax.swing.TransferHandler.COPY)
            assertEquals("original draft", clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor))
            assertTrue(prompt.transferHandler.importData(javax.swing.TransferHandler.TransferSupport(prompt, java.awt.datatransfer.StringSelection("pasted draft"))))
            assertEquals("pasted draft", prompt.text)
        } finally {
            panel.dispose()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid conversation data to be rejected")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun descendants(root: Component): List<Component> = listOf(root) + (root as? Container)?.components.orEmpty().flatMap(::descendants)
}
