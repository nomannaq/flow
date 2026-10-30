package dev.flow.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.flow.bridge.AgentEvent
import dev.flow.bridge.AgentView
import dev.flow.bridge.EngineController
import dev.flow.bridge.ProjectId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JList

class ChatChangesTest : LightPlatformTestCase() {
    private val checkpoint = "a".repeat(64)
    private val ids = """{"before":"${"b".repeat(64)}","after":"${"c".repeat(64)}"}"""
    private fun summary(next: String? = null) = JsonParser.parseString("""{"sessionId":"s","snapshotId":"$checkpoint","baselineId":"${"d".repeat(64)}","total":1,"complete":true,"captureGap":false,"items":[{"index":0,"oldPath":"src/main.rs","newPath":"src/main.rs","kind":"modified","contentIds":$ids}],"next":${next?.let { "\"$it\"" } ?: "null"}}""")
    private fun diff(session: String = "s") = JsonParser.parseString("""{"sessionId":"$session","snapshotId":"$checkpoint","fileIndex":0,"oldPath":"src/main.rs","newPath":"src/main.rs","contentIds":$ids,"before":"fn old() {}\n","after":"fn updated() {}\n","beforeNote":"","afterNote":""}""")

    fun testEditsCountsUseVerifiedContentAreCachedAndDoNotExposeTextToTheBrowser() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("edits-counts"), { error("No adapter needed") })
        var reads = 0
        val panel = ChatChangesPanel(project, controller, request = { method, params ->
            if (method == "history.changes") {
                summary()
            } else {
                assertEquals("history.changeDiff", method)
                assertEquals(checkpoint, params["snapshotId"].asString)
                assertEquals(JsonParser.parseString(ids), params["contentIds"])
                reads++
                diff()
            }
        })
        try {
            panel.update(AgentView(id = "s", recordedOperations = 1), true)
            waitFor { panel.isVisible }
            val stats = runBlocking { panel.webStats(checkpoint) }
            val file = stats.getAsJsonArray("files").single().asJsonObject
            assertEquals(1, file["additions"].asInt)
            assertEquals(1, file["deletions"].asInt)
            assertEquals(0, file["index"].asInt)
            assertFalse(stats.toString().contains("fn old"))
            assertFalse(stats.toString().contains("fn updated"))
            assertEquals(stats, runBlocking { panel.webStats(checkpoint) })
            assertEquals("Unchanged bytes must not be reread for each browser update", 1, reads)
            panel.update(AgentView(id = "s", recordedOperations = 1), false)
            assertTrue(runCatching { runBlocking { panel.webStats(checkpoint) } }.isFailure)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testEditsCountsHandleEmptyFilesNewlinesAndUnavailableTextWithoutInventingNumbers() {
        val cases = listOf(
            Triple("" to "", 0, 0),
            Triple("" to "first\nsecond\n", 2, 0),
            Triple("first\nsecond\n" to "", 0, 2),
            Triple("first\r\nold\r\n" to "first\r\nnew\r\n", 1, 1),
            Triple("old" to "new", 1, 1),
        )
        for ((texts, added, removed) in cases) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = EngineController(scope, ProjectId("edits-special"), { error("No adapter needed") })
            var binary = false
            val panel = ChatChangesPanel(project, controller, request = { method, _ ->
                if (method == "history.changes") {
                    summary()
                } else {
                    diff().asJsonObject.apply {
                        if (binary) add("after", com.google.gson.JsonNull.INSTANCE) else addProperty("after", texts.second)
                        addProperty("before", texts.first)
                        addProperty("afterNote", "Binary or unsupported text encoding; immutable bytes retained")
                    }
                }
            })
            try {
                panel.update(AgentView(id = "s", recordedOperations = 1), true)
                waitFor { panel.isVisible }
                val counts = runBlocking { panel.webStats(checkpoint) }.getAsJsonArray("files").single().asJsonObject
                assertEquals(added, counts["additions"].asInt)
                assertEquals(removed, counts["deletions"].asInt)
                if (added == 0 && removed == 0) {
                    binary = true
                    panel.update(AgentView(id = "other"), true)
                    panel.update(AgentView(id = "s", recordedOperations = 1), true)
                    waitFor { panel.isVisible }
                    val unavailable = runBlocking { panel.webStats(checkpoint) }.getAsJsonArray("files").single().asJsonObject
                    assertFalse(unavailable.has("additions"))
                    assertFalse(unavailable.has("deletions"))
                    assertTrue(unavailable["note"].asString.contains("Binary"))
                }
            } finally {
                panel.dispose()
                scope.cancel()
            }
        }
    }

    fun testChangedFilePreviewUsesPinnedVerifiedReadOnlyContentAndHidesOnSessionSwitch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("chat-diff"), { error("Cannot launch an agent") })
        var shown: SimpleDiffRequest? = null
        var query: JsonObject? = null
        val panel = ChatChangesPanel(project, controller, request = { method, params ->
            if (method == "history.changes") {
                summary()
            } else {
                assertEquals("history.changeDiff", method)
                query = params.deepCopy()
                diff()
            }
        }, present = { shown = it })
        val chat = ConversationView(changes = panel)
        try {
            panel.update(AgentView(id = "s", recordedOperations = 2), true)
            chat.update(listOf(AgentEvent("turn.started", "Update the function"), AgentEvent("message.delta", "Updated the function.")), "s")
            waitFor { panel.isVisible && descendants(panel).filterIsInstance<JList<*>>().single().model.size == 1 }
            assertTrue("Changed files belong inside the conversation", javax.swing.SwingUtilities.isDescendingFrom(panel, chat))
            val toggle = descendants(panel).filterIsInstance<AbstractButton>().single { it.accessibleContext.accessibleName == "Show changed files" }
            assertFalse("A changed-file summary starts compact", descendants(panel).filterIsInstance<JList<*>>().single().parent.parent.parent.isVisible)
            toggle.doClick(0)
            assertEquals("Hide changed files", toggle.accessibleContext.accessibleName)
            descendants(panel).filterIsInstance<JList<*>>().single().selectedIndex = 0
            waitFor { shown != null }
            assertEquals(checkpoint, query!!["snapshotId"].asString)
            assertEquals(JsonParser.parseString(ids), query!!["contentIds"])
            assertEquals("fn old() {}\n", (shown!!.contents[0] as DocumentContent).document.text)
            assertEquals("fn updated() {}\n", (shown!!.contents[1] as DocumentContent).document.text)
            assertEquals(true, shown!!.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
            assertEquals(true, shown!!.getUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE))
            assertEquals("src/main.rs", shown!!.title)
            assertEquals(1, panel.webState()["total"].asInt)
            panel.update(AgentView(id = "different", recordedOperations = 0), true)
            assertFalse(panel.isVisible)
            assertEquals(0, panel.webState()["total"].asInt)
            assertEquals(0, descendants(panel).filterIsInstance<JList<*>>().single().model.size)
        } finally {
            chat.dispose()
            panel.dispose()
            scope.cancel()
        }
    }

    fun testForeignDiffAndLateSessionRepliesAreNeverDisplayed() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("chat-diff-mismatch"), { error("Cannot launch an agent") })
        var shown = false
        var delayed: CompletableDeferred<JsonElement>? = null
        var requested = false
        val panel = ChatChangesPanel(project, controller, request = { method, _ ->
            if (method == "history.changes") {
                summary()
            } else {
                requested = true
                delayed?.await() ?: diff("foreign")
            }
        }, present = { shown = true })
        try {
            panel.update(AgentView(id = "s", recordedOperations = 1), true)
            waitFor { panel.isVisible }
            val list = descendants(panel).filterIsInstance<JList<*>>().single()
            list.selectedIndex = 0
            waitFor { descendants(panel).filterIsInstance<com.intellij.ui.components.JBTextArea>().any { it.text.contains("unavailable") } }
            assertFalse("Foreign session content must never open", shown)
            requested = false
            delayed = CompletableDeferred()
            descendants(panel).filterIsInstance<AbstractButton>().single { it.text == "View diff" }.doClick(0)
            waitFor { requested }
            panel.update(AgentView(id = "different"), true)
            delayed.complete(diff())
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertFalse(shown)
            assertFalse(panel.isVisible)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testWebDiffRejectsResponsesOutsideTheSelectedRecordedFile() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("chat-web-diff"), { error("Cannot launch an agent") })
        var response = diff().asJsonObject
        var query: JsonObject? = null
        val panel = ChatChangesPanel(project, controller, request = { method, params ->
            if (method == "history.changes") {
                summary()
            } else {
                assertEquals("history.changeDiff", method)
                query = params.deepCopy()
                response
            }
        })
        try {
            panel.update(AgentView(id = "s", recordedOperations = 1), true)
            waitFor { panel.isVisible }
            assertEquals(response, runBlocking { panel.webDiff(0, checkpoint, native = false) })
            assertEquals("s", query!!["sessionId"].asString)
            assertEquals(checkpoint, query!!["snapshotId"].asString)
            assertEquals(0, query!!["fileIndex"].asInt)
            assertEquals(JsonParser.parseString(ids), query!!["contentIds"])

            val substitutions = mapOf(
                "sessionId" to JsonPrimitive("foreign"),
                "snapshotId" to JsonPrimitive("e".repeat(64)),
                "fileIndex" to JsonPrimitive(1),
                "contentIds" to JsonParser.parseString("""{"before":"${"e".repeat(64)}","after":"${"f".repeat(64)}"}"""),
                "oldPath" to JsonPrimitive("other.rs"),
                "newPath" to JsonPrimitive("other.rs"),
            )
            for ((field, value) in substitutions) {
                response = diff().asJsonObject.apply { add(field, value) }
                val failure = runCatching { runBlocking { panel.webDiff(0, checkpoint, native = false) } }.exceptionOrNull()
                assertTrue("A mismatched $field must not reach React", failure is IllegalStateException)
            }
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testWebNativeDiffUsesTypedReadOnlyContentAndExplainsUnavailableText() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("chat-web-native-diff"), { error("Cannot launch an agent") })
        var response = diff().asJsonObject.apply {
            addProperty("beforeMode", 33188)
            addProperty("afterMode", 33261)
        }
        var shown: SimpleDiffRequest? = null
        val panel = ChatChangesPanel(project, controller, request = { method, _ -> if (method == "history.changes") summary() else response }, present = { shown = it })
        try {
            panel.update(AgentView(id = "s", recordedOperations = 1), true)
            waitFor { panel.isVisible }
            runBlocking { panel.webDiff(0, checkpoint, native = true) }
            val opened = requireNotNull(shown)
            assertEquals("fn old() {}\n", (opened.contents[0] as DocumentContent).document.text)
            assertEquals("fn updated() {}\n", (opened.contents[1] as DocumentContent).document.text)
            val expectedType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName("main.rs")
            assertEquals(expectedType, (opened.contents[0] as DocumentContent).contentType)
            assertEquals("Before agent changes · mode 100644", opened.contentTitles[0])
            assertEquals("Recorded changes so far · mode 100755", opened.contentTitles[1])
            assertEquals(true, opened.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
            assertTrue(opened.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)!!.all { it })
            assertEquals(true, opened.getUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE))

            shown = null
            response = diff().asJsonObject.apply {
                add("after", com.google.gson.JsonNull.INSTANCE)
                addProperty("afterNote", "Binary or unsupported text encoding; immutable bytes retained")
            }
            val failure = runCatching { runBlocking { panel.webDiff(0, checkpoint, native = true) } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("Binary"))
            assertNull("Unavailable text must never open as an empty document", shown)
            assertTrue(runBlocking { panel.webDiff(0, checkpoint, native = false) }["after"].isJsonNull)
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    fun testNativeDiffEmbedsInConversationAndCanBeClosedWithoutChangingFiles() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("chat-native-diff"), { error("Cannot launch an agent") })
        val panel = ChatChangesPanel(project, controller, request = { method, _ -> if (method == "history.changes") summary() else diff() })
        val chat = ConversationView(changes = panel)
        // Use the IDE's unified-view preference in this fixture so its native
        // editor can be painted offscreen without a screen-dependent divider.
        val preferences = com.intellij.diff.impl.DiffSettingsHolder.DiffSettings.getSettings()
        val previousTools = preferences.diffToolsOrder.toList()
        preferences.diffToolsOrder = listOf("com.intellij.diff.tools.fragmented.UnifiedDiffTool")
        try {
            panel.update(AgentView(id = "s", recordedOperations = 1), true)
            chat.update(listOf(AgentEvent("turn.started", "Update the function"), AgentEvent("message.delta", "Updated the function.")), "s")
            waitFor { panel.isVisible }
            descendants(panel).filterIsInstance<JList<*>>().single().selectedIndex = 0
            waitFor { descendants(panel).filterIsInstance<AbstractButton>().any { it.text == "Hide diff" && it.isVisible } }
            assertTrue("Native diff must be embedded", descendants(panel).any { it.javaClass.name.contains("DiffRequestPanel") || it.javaClass.name.contains("DiffRequestProcessor") })
            for (width in listOf(320, 480)) {
                chat.setSize(width, 900)
                repeat(12) { layout(chat) }
                val image = java.awt.image.BufferedImage(width, 900, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                chat.printAll(graphics)
                graphics.dispose()
                val path = java.nio.file.Path.of("build/reports/ui-preview/chat-inline-diff-$width.png")
                java.nio.file.Files.createDirectories(path.parent)
                javax.imageio.ImageIO.write(image, "png", path.toFile())
            }
            descendants(panel).filterIsInstance<AbstractButton>().single { it.text == "Hide diff" }.doClick(0)
            assertFalse(descendants(panel).filterIsInstance<AbstractButton>().single { it.text == "Hide diff" }.isVisible)
        } finally {
            preferences.diffToolsOrder = previousTools
            chat.dispose()
            panel.dispose()
            scope.cancel()
        }
    }

    private fun waitFor(test: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("Chat preview should finish", test, 10)
    private fun descendants(root: Container): List<Component> = root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }
    private fun layout(root: Component) {
        if (root is Container) {
            root.doLayout()
            root.components.forEach(::layout)
        }
    }
}
