package dev.flow.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.LightPlatformTestCase
import dev.flow.bridge.EngineController
import dev.flow.bridge.ProjectId
import dev.flow.bridge.optionalText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.JTree
import javax.swing.event.TreeSelectionEvent
import javax.swing.text.JTextComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Real Swing listeners -> controller -> Rust -> ACP fixture -> immutable native diff requests. */
class FirstVerticalSliceTest : LightPlatformTestCase() {
    private var turnPageGate: CompletableDeferred<Unit>? = null
    private var turnPageEntered = false
    private var filePageGate: CompletableDeferred<Unit>? = null
    private val historyTrace = mutableListOf<String>()
    private val updateTimes = mutableListOf<Double>()
    private val diffTimes = mutableListOf<Double>()
    override fun runInDispatchThread() = false

    fun testTwoEditsSurviveRestartAndLiveDeletion() = scenario(null)
    fun testCancellationAfterFirstWriteKeepsPartialHistory() = scenario("--cancel-after-write")
    fun testFailedSecondWriteStillRecordsChangedBytes() = scenario("--fail-second-write")
    fun testOptInRealCodexTwoEdits() = scenario(null, real = true)

    private fun scenario(flag: String?, real: Boolean = false) = runBlocking {
        val filename = if (real) "flow-b12.txt" else "probe.txt"
        val adapter = if (real) requireNotNull(System.getProperty("flow.test.realAdapter")) else requireNotNull(System.getProperty("flow.test.python"))
        if (real) {
            require(System.getProperty("flow.test.realAllowOnce") == "true") { "Real acceptance requires explicit one-time approval opt-in" }
            val path = Path.of(adapter)
            require(path.isAbsolute && Files.isExecutable(path))
            require(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) } == System.getProperty("flow.test.realSha256")) { "Adapter checksum mismatch" }
        }
        val root = Files.createTempDirectory("flow-b12-")
        val repository = Files.createDirectory(root.resolve("project"))
        val data = Files.createDirectory(root.resolve("data"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processes = CopyOnWriteArrayList<Process>()
        lateinit var taskPanel: AgentSessionPanel
        lateinit var historyPanel: SessionHistoryPanel
        var mounted = false
        var observer: Job? = null
        val requests = mutableListOf<SimpleDiffRequest>() // EDT only
        var realEvidence: JsonObject? = null
        var startupMs = 0.0
        var cancellationMs: Double? = null
        var cancellationStarted: Long? = null
        val controller = EngineController(scope, ProjectId("vertical-slice"), launchProcess = {
            ProcessBuilder(requireNotNull(System.getProperty("flow.test.engine")), "--project-root", repository.toString(), "--data-root", data.toString())
                .start().also { processes.add(it) }
        }, pollMs = 25)
        suspend fun mount() = withContext(Dispatchers.EDT) {
            taskPanel = AgentSessionPanel(controller)
            historyPanel = SessionHistoryPanel(project, controller, scope, historyRequest = { method, params ->
                historyTrace.add("$method: entered")
                try {
                    if (method == "history.files") filePageGate?.await()
                    if (method == "history.turns") {
                        turnPageGate?.let {
                            turnPageEntered = true
                            it.await()
                        }
                    }
                    historyTrace.add("$method: sent")
                    controller.historyRequest(method, params).also { historyTrace.add("$method: received") }
                } catch (failure: Exception) {
                    historyTrace.add("$method: ${failure.javaClass.simpleName}")
                    throw failure
                }
            }, presentDiff = { requests.add(it) }, newConversation = { error("Review must not start a conversation") })
            mounted = true
            observer = scope.launch(Dispatchers.EDT) {
                controller.view.collect {
                    val started = System.nanoTime()
                    taskPanel.update(it)
                    historyPanel.update(it)
                    updateTimes.add((System.nanoTime() - started) / 1_000_000.0)
                }
            }
        }
        try {
            git(repository, "init", "--initial-branch=main")
            Files.writeString(repository.resolve("tracked.txt"), "committed baseline\n")
            git(repository, "add", "tracked.txt")
            git(repository, "commit", "-m", "baseline")
            // Include staged, unstaged and untracked developer work in the invariant.
            Files.writeString(repository.resolve("tracked.txt"), "developer staged\n")
            git(repository, "add", "tracked.txt")
            Files.writeString(repository.resolve("tracked.txt"), "developer unstaged\n")
            Files.writeString(repository.resolve("untracked.txt"), "developer untracked\n")
            val before = projectState(repository)
            mount()
            val startup = System.nanoTime()
            controller.start()
            await("engine connection") { controller.view.value.connected }
            startupMs = (System.nanoTime() - startup) / 1_000_000.0
            withContext(Dispatchers.EDT) {
                field(taskPanel, "ACP adapter executable").text = adapter
                field(taskPanel, "Model override").text = if (real) requireNotNull(System.getProperty("flow.test.realModel")) else ""
                field(taskPanel, "Authentication method").text = ""
                field(taskPanel, "Advanced adapter arguments").text = JsonArray().apply {
                    if (!real) {
                        add(requireNotNull(System.getProperty("flow.test.adapterFixture")))
                        if (flag == null) {
                            add("--configuration")
                            add("--conversation-features")
                            add("--delegation-tools")
                        }
                        flag?.let { add(it) }
                    }
                }.toString()
            }
            // The ordinary Send path must resolve the baseline without opening settings.
            assertTrue(field(taskPanel, "Clean baseline commit").text.isBlank())
            suspend fun submit(first: Boolean) {
                withContext(Dispatchers.EDT) {
                    field(taskPanel, "Task prompt").text = if (real) {
                        if (first) "Create flow-b12.txt containing exactly \"version 1\" followed by a newline. Do not edit other files." else "Change flow-b12.txt to exactly \"version 2\" followed by a newline. Do not edit other files."
                    } else if (first) {
                        "Write first version"
                    } else {
                        "Write second version"
                    }
                    if (!real && flag == null) {
                        taskPanel.webAction(
                            JsonObject().apply {
                                addProperty("action", "send")
                                addProperty("sessionId", controller.view.value.agent.id.orEmpty())
                                addProperty("text", field(taskPanel, "Task prompt").text)
                                addProperty("version", if (first) 1 else 2)
                            },
                        )
                    } else {
                        click(taskPanel, if (first) "Start session" else "Send")
                    }
                }
                if (real) {
                    val answered = mutableSetOf<String>()
                    withTimeout(180000) {
                        while (true) {
                            val view = controller.view.value.agent
                            assertFalse("Real adapter could not complete; check native login/model", view.status in listOf("failed", "authenticationRequired", "closed"))
                            val permission = view.permissions.firstOrNull { it.id !in answered }
                            if (permission != null) {
                                val option = permission.allowOnce.firstOrNull()
                                assertNotNull("Real adapter must advertise an explicit one-time allow choice", option)
                                withContext(Dispatchers.EDT) {
                                    components(taskPanel).filterIsInstance<JButton>().firstOrNull { it.getClientProperty("flow.permission.requestId") == permission.id && it.getClientProperty("flow.permission.optionId") == option && it.isEnabled }?.let { button ->
                                        button.doClick(0)
                                        answered.add(permission.id)
                                    }
                                }
                            }
                            val workspace = view.workspace?.let(Path::of)
                            val bytes = workspace?.resolve(filename)?.takeIf(Files::isRegularFile)?.let(Files::readString)
                            if (view.status == "ready" && bytes == if (first) "version 1\n" else "version 2\n") break
                            delay(50)
                        }
                    }
                } else {
                    await("one-time permission") { controller.view.value.agent.permissions.isNotEmpty() && components(taskPanel).filterIsInstance<JButton>().any { it.text == "Once" } }
                    withContext(Dispatchers.EDT) {
                        if (flag == null) {
                            val agent = controller.view.value.agent
                            val permission = agent.permissions.first()
                            taskPanel.webAction(
                                JsonObject().apply {
                                    addProperty("action", "permission")
                                    addProperty("sessionId", agent.id)
                                    addProperty("requestId", permission.id)
                                    addProperty("option", permission.options.single { it.second == "Once" }.first)
                                },
                            )
                        } else {
                            click(taskPanel, "Once")
                        }
                    }
                }
            }
            submit(true)
            if (flag == "--cancel-after-write") {
                await("first write before cancellation") {
                    controller.view.value.agent.workspace?.let { Files.exists(Path.of(it).resolve("probe.txt")) } == true
                }
                cancellationStarted = System.nanoTime()
                withContext(Dispatchers.EDT) { click(taskPanel, "Stop") }
            }
            await("first turn ready") { controller.view.value.agent.status == "ready" }
            if (!real && flag == null) {
                withContext(Dispatchers.EDT) {
                    val cached = taskPanel.webAction(
                        JsonObject().apply {
                            addProperty("action", "promptHistory")
                            addProperty("operation", "list")
                        },
                    ).asJsonObject.getAsJsonArray("items")
                    assertEquals("Only the raw acknowledged user prompt is cached", "Write first version", cached.first().asJsonObject["text"].asString)
                }
                for ((configId, chosen) in listOf("effort" to "low", "speed" to "fast", "model" to "fixture-fast")) {
                    withContext(Dispatchers.EDT) {
                        val options = controller.view.value.agent.configOptions
                        taskPanel.webAction(
                            JsonObject().apply {
                                addProperty("action", "configure")
                                addProperty("sessionId", controller.view.value.agent.id)
                                addProperty("configId", configId)
                                add("modelValue", options.single { it.id == "model" }.currentValue)
                                add("currentValue", options.single { it.id == configId }.currentValue)
                                addProperty("value", chosen)
                            },
                        )
                    }
                    await("$configId choice confirmed by the ACP agent") {
                        controller.view.value.agent.let { it.status == "ready" && it.configOptions.single { option -> option.id == configId }.currentValue.asString == chosen }
                    }
                }
                withContext(Dispatchers.EDT) {
                    val effort = taskPanel.webState().getAsJsonArray("configOptions").single { it.asJsonObject["id"].asString == "effort" }.asJsonObject
                    assertEquals("Changing model must refresh dependent choices", 1, effort.getAsJsonArray("options").size())
                    assertEquals("low", effort["currentValue"].asString)
                }
                for ((operation, paused) in listOf("pause" to true, "resume" to false)) {
                    withContext(Dispatchers.EDT) {
                        taskPanel.webAction(
                            JsonObject().apply {
                                addProperty("action", "queue")
                                addProperty("sessionId", controller.view.value.agent.id)
                                addProperty("operation", operation)
                            },
                        )
                    }
                    await("React queue $operation acknowledged by Rust") { controller.view.value.agent.queuePaused == paused }
                }
            }
            cancellationStarted?.let { cancellationMs = (System.nanoTime() - it) / 1_000_000.0 }
            if (flag != "--cancel-after-write") {
                submit(false)
                await("second turn settled") { controller.view.value.agent.status == if (flag == null) "ready" else "failed" }
            }
            val expected = if (real) {
                listOf("" to "version 1\n", "version 1\n" to "version 2\n")
            } else if (flag == "--cancel-after-write") {
                listOf("" to "flow-probe-1\n")
            } else {
                listOf("" to "flow-probe-1\n", "flow-probe-1\n" to "flow-probe-2\n")
            }
            val workspace = Path.of(requireNotNull(controller.view.value.agent.workspace))
            assertEquals(expected.last().second, Files.readString(workspace.resolve(filename)))
            if (!real && flag == null) {
                val configuration = com.google.gson.JsonParser.parseString(Files.readString(workspace.resolve("configuration-proof.json"))).asJsonArray
                assertEquals("The follow-up must use the selected model", "fixture-fast", configuration.single { it.asJsonObject["id"].asString == "model" }.asJsonObject["currentValue"].asString)
                assertEquals("The follow-up must use the confirmed effort", "low", configuration.single { it.asJsonObject["id"].asString == "effort" }.asJsonObject["currentValue"].asString)
                assertEquals("The follow-up must use the confirmed speed", "fast", configuration.single { it.asJsonObject["id"].asString == "speed" }.asJsonObject["currentValue"].asString)
            }
            if (!real && flag == null) {
                withContext(Dispatchers.EDT) {
                    await("Edits summary captured by the native bridge") {
                        taskPanel.webState().getAsJsonObject("changes")["snapshot"]?.isJsonNull == false
                    }
                    val progress = taskPanel.webState().getAsJsonObject("progressPanels")
                    assertEquals(2, progress.getAsJsonObject("plan").getAsJsonArray("entries").size())
                    assertTrue(progress.getAsJsonArray("subagents").size() >= 1)
                    val catalog = taskPanel.webAction(
                        JsonObject().apply {
                            addProperty("action", "complete")
                            addProperty("sessionId", controller.view.value.agent.id)
                            add("profile", taskPanel.webState()["profile"])
                            addProperty("trigger", "/")
                            addProperty("query", "rev")
                        },
                    ).asJsonArray
                    assertEquals("/review ", catalog.single().asJsonObject["insert"].asString)
                    val changes = taskPanel.webState().getAsJsonObject("changes")
                    val stats = taskPanel.webAction(
                        JsonObject().apply {
                            addProperty("action", "changesStats")
                            addProperty("sessionId", controller.view.value.agent.id)
                            add("snapshot", changes["snapshot"])
                        },
                    ).asJsonObject
                    val file = changes.getAsJsonArray("files").single { it.asJsonObject.optionalText("newPath") == filename }.asJsonObject
                    val count = stats.getAsJsonArray("files").single { it.asJsonObject["index"] == file["index"] }.asJsonObject
                    assertEquals(1, count["additions"].asInt)
                    assertEquals("Counts describe the net baseline-to-checkpoint delta", 0, count["deletions"].asInt)
                    assertFalse(stats.toString().contains("flow-probe"))
                }
            }
            val firstReview = review(historyPanel, requests, filename)
            assertEquals(expected, firstReview.map { it.before to it.after })
            if (flag == "--cancel-after-write") assertTrue(firstReview.single().operation.contains("interrupted"))
            if (flag == "--fail-second-write") assertTrue(firstReview.last().operation.contains("failed"))
            assertEquals(before, projectState(repository))

            if (controller.view.value.agent.status == "ready") {
                withContext(Dispatchers.EDT) { closeThroughMenu(taskPanel) }
                await("session shutdown") { controller.view.value.agent.status in listOf("closed", "failed") }
            }
            if (real) assertEquals("Writer shutdown must be verified", "closed", controller.view.value.agent.status)
            if (!real && flag == null) reviewCandidate(controller, scope, requireNotNull(controller.view.value.agent.id), requests)
            // Unknown shutdown remains failed in restrictive sandboxes; never rewrite ownership evidence.
            val oldPid = controller.view.value.pid
            withContext(Dispatchers.EDT) {
                observer?.cancel()
                historyPanel.dispose()
            }
            controller.restart()
            await("replacement engine") { controller.view.value.connected && controller.view.value.pid != oldPid }
            Files.delete(workspace.resolve(filename))
            mount() // fresh Swing models, no retained selection or cached request
            val secondReview = review(historyPanel, requests, filename)
            assertEquals(firstReview, secondReview)
            assertEquals(before, projectState(repository))
            assertNull(controller.view.value.agent.id) // reading history did not launch an agent
            val retainedSession = controller.historyRequest("sessions.list").asJsonObject.getAsJsonArray("items").first().asJsonObject["id"].asString
            controller.historyRequest(
                "sessions.label",
                JsonObject().apply {
                    addProperty("sessionId", retainedSession)
                    addProperty("title", "Retained test conversation")
                    addProperty("favorite", true)
                },
            )
            val favorites = controller.historyRequest(
                "sessions.search",
                JsonObject().apply {
                    addProperty("query", "Retained test conversation")
                    addProperty("favoritesOnly", true)
                },
            ).asJsonObject.getAsJsonArray("items")
            assertEquals(retainedSession, favorites.single().asJsonObject["id"].asString)
            val transcript = ConversationJournal(retainedSession)
            do {
                transcript.append(controller.historyRequest("history.conversation", transcript.params()).asJsonObject)
            } while (transcript.next != null)
            assertTrue(transcript.events().any { it.kind == "turn.started" })
            assertTrue(transcript.export(false).contains("## You"))
            assertTrue(transcript.export(true).contains("flow.conversation.v1"))
            assertNull(controller.view.value.agent.id)
            if (real) {
                realEvidence = JsonObject().apply {
                    addProperty("nativeSwingAcceptancePassed", true)
                    addProperty("b12GatePassed", false)
                    addProperty("adapterSha256", System.getProperty("flow.test.realSha256"))
                    addProperty("modelOverride", System.getProperty("flow.test.realModel"))
                    addProperty("ideBuild", "IU-253.33813.55")
                    addProperty("nativeSwingSubmission", true)
                    addProperty("immutableNativeDiffRequests", true)
                    addProperty("engineRestartAndLiveDeletion", true)
                    addProperty("noAgentAutoResume", true)
                    addProperty("dirtyMainProjectAndIndexUnchanged", true)
                    addProperty("writerShutdownVerified", true)
                    addProperty("fullIdeProcessRestart", "pending host observation")
                    addProperty("renderedDiffPresentation", "pending host observation")
                    addProperty("scratch", root.toString())
                    add(
                        "pairs",
                        JsonArray().apply {
                            firstReview.forEach { pair ->
                                add(
                                    JsonObject().apply {
                                        addProperty("before", pair.before)
                                        addProperty("after", pair.after)
                                        addProperty("title", pair.title)
                                        addProperty("operation", pair.operation)
                                    },
                                )
                            }
                        },
                    )
                }
            }
        } finally {
            if (real && mounted && controller.view.value.agent.status !in listOf("closed", "failed", "interrupted")) {
                withContext(Dispatchers.EDT) { closeThroughMenu(taskPanel) }
                withTimeoutOrNull(15000) { while (controller.view.value.agent.status !in listOf("closed", "failed", "interrupted")) delay(50) }
            }
            withContext(Dispatchers.EDT) {
                observer?.cancel()
                if (mounted) historyPanel.dispose()
            }
            scope.cancel()
            withTimeout(15000) { scope.coroutineContext[Job]!!.join() }
            processes.forEach { if (it.isAlive) it.destroyForcibly() }
            processes.forEach { assertTrue("Engine must exit", it.waitFor(10, TimeUnit.SECONDS)) }
            if (real) {
                Files.writeString(
                    Path.of(requireNotNull(System.getProperty("flow.test.realEvidence"))),
                    (
                        realEvidence ?: JsonObject().apply {
                            addProperty("nativeSwingAcceptancePassed", false)
                            addProperty("scratch", root.toString())
                        }
                        ).toString(),
                )
            } else {
                root.toFile().deleteRecursively()
            }
            System.getProperty("flow.test.performanceOutput")?.let { output ->
                val value = JsonObject().apply {
                    addProperty("fixture", flag ?: if (real) "real-codex" else "two-edits")
                    addProperty("engineReadyMs", startupMs)
                    addProperty("cancelTurnReadyMs", cancellationMs)
                    add("edtUpdateMs", JsonArray().apply { updateTimes.forEach { add(it) } })
                    add("nativeDiffRequestMs", JsonArray().apply { diffTimes.forEach { add(it) } })
                    addProperty("visibleRenderingMeasured", false)
                }
                Files.writeString(Path.of("$output-${flag ?: if (real) "real" else "two-edits"}.json"), value.toString())
            }
        }
    }

    private suspend fun reviewCandidate(controller: EngineController, parent: CoroutineScope, sessionId: String, requests: MutableList<SimpleDiffRequest>) {
        lateinit var panel: CandidateReviewPanel
        lateinit var observer: Job
        withContext(Dispatchers.EDT) {
            panel = CandidateReviewPanel(project, controller, parent) { requests.add(it) }
            panel.selectSession(sessionId)
            observer = parent.launch(Dispatchers.EDT) { controller.view.collect { panel.update(it) } }
            components(panel).filterIsInstance<JCheckBox>().single().doClick(0)
        }
        try {
            await("candidate preparation enabled") { button(panel, "Prepare result").isEnabled }
            withContext(Dispatchers.EDT) { click(panel, "Prepare result") }
            // Full-result review includes process and configuration evidence files;
            // historical probe-only review above deliberately selects one file.
            await("complete candidate", { "files=${list(panel, "Complete-result files").model.size}; ${field(panel, "Candidate review status").text}" }) { list(panel, "Complete-result files").model.size == 3 && field(panel, "Candidate review status").text.contains("Decision: pending") }
            withContext(Dispatchers.EDT) {
                val resultFiles = list(panel, "Complete-result files")
                resultFiles.selectedIndex = (0 until resultFiles.model.size).single { resultFiles.model.getElementAt(it).toString().startsWith("probe.txt —") }
                requests.clear()
                click(panel, "Open result diff")
            }
            await("complete-result native diff") { requests.size == 1 }
            withContext(Dispatchers.EDT) {
                val diff = requests.single()
                assertEquals(true, diff.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
                assertEquals("", (diff.contents[0] as DocumentContent).document.text)
                assertEquals("flow-probe-2\n", (diff.contents[1] as DocumentContent).document.text)
                assertTrue(diff.contentTitles.first()!!.contains("absent"))
                assertTrue(diff.contentTitles.last()!!.contains("mode 644"))
            }
            await("candidate acceptance enabled") { button(panel, "Accept result").isEnabled }
            withContext(Dispatchers.EDT) { click(panel, "Accept result") }
            await("hash-bound acceptance") { field(panel, "Candidate review status").text.contains("Decision: accepted") && button(panel, "Run check (15s)").isEnabled }
            val originalId = withContext(Dispatchers.EDT) { Regex("[a-f0-9]{64}").find(field(panel, "Candidate review status").text)!!.value }
            withContext(Dispatchers.EDT) {
                field(panel, "Explicit candidate check command").text = JsonArray().apply {
                    add(requireNotNull(System.getProperty("flow.test.python")))
                    add("-c")
                    add("from pathlib import Path; Path('probe.txt').write_text('checked candidate\\n')")
                }.toString()
                click(panel, "Run check (15s)")
            }
            await("new unchecked candidate version") {
                val text = field(panel, "Candidate review status").text
                text.contains("Decision: pending") && text.contains("passed") && Regex("[a-f0-9]{64}").find(text)?.value != originalId && button(panel, "Accept result").isEnabled
            }
            withContext(Dispatchers.EDT) {
                val resultFiles = list(panel, "Complete-result files")
                resultFiles.selectedIndex = (0 until resultFiles.model.size).single { resultFiles.model.getElementAt(it).toString().startsWith("probe.txt —") }
                requests.clear()
                click(panel, "Open result diff")
            }
            await("checked candidate diff") { requests.size == 1 }
            withContext(Dispatchers.EDT) { assertEquals("checked candidate\n", (requests.single().contents[1] as DocumentContent).document.text) }
            await("renewed review enabled") { button(panel, "Accept result").isEnabled }
            withContext(Dispatchers.EDT) { click(panel, "Accept result") }
            await("renewed hash-bound acceptance") { field(panel, "Candidate review status").text.contains("Decision: accepted") && button(panel, "Reject result").isEnabled }
            withContext(Dispatchers.EDT) { click(panel, "Reject result") }
            await("whole-result rejection") { field(panel, "Candidate review status").text.contains("Decision: rejected") }
        } finally {
            withContext(Dispatchers.EDT) {
                observer.cancel()
                panel.dispose()
            }
        }
    }

    private data class Reviewed(val before: String, val after: String, val title: String?, val titles: List<String?>, val operation: String)

    private suspend fun review(panel: SessionHistoryPanel, requests: MutableList<SimpleDiffRequest>, filename: String = "probe.txt"): List<Reviewed> {
        await("history connection") { button(panel, "Refresh sessions").isEnabled }
        withContext(Dispatchers.EDT) { click(panel, "Refresh sessions") }
        await("retained session") { list(panel, "Sessions").model.size == 1 }
        withContext(Dispatchers.EDT) {
            turnPageEntered = false
            turnPageGate = CompletableDeferred()
            list(panel, "Sessions").selectedIndex = 0
        }
        await("turn page in flight") { turnPageEntered }
        // A descendant query must not cancel the still-loading turns page.
        withContext(Dispatchers.EDT) { click(panel, "All operations") }
        await("operations while turns pending") { list(panel, "Recorded operations").model.size > 0 }
        withContext(Dispatchers.EDT) {
            turnPageGate!!.complete(Unit)
            turnPageGate = null
        }

        await("turns", { historyState(panel) }) { list(panel, "Turns in the selected session").model.size >= 2 }
        withContext(Dispatchers.EDT) { click(panel, "All operations") }
        await("operations") { list(panel, "Recorded operations").model.size > 0 }
        val result = mutableListOf<Reviewed>()
        val count = withContext(Dispatchers.EDT) { list(panel, "Recorded operations").model.size }
        for (index in 0 until count) {
            val label = withContext(Dispatchers.EDT) {
                filePageGate = CompletableDeferred()
                val selected = list(panel, "Recorded operations").apply { selectedIndex = index }.selectedValue.toString()
                // Reproduce the deferred selection-clearing event from tree
                // model replacement while history.files is still in flight.
                val tree = tree(panel)
                val path = TreePath(tree.model.root)
                tree.treeSelectionListeners.forEach { it.valueChanged(TreeSelectionEvent(tree, path, false, null, null)) }
                filePageGate!!.complete(Unit)
                selected
            }
            await("file page", { historyState(panel) }) { tree(panel).model.getChildCount(tree(panel).model.root) > 0 }
            val started = System.nanoTime()
            val selected = withContext(Dispatchers.EDT) {
                val tree = tree(panel)
                val root = tree.model.root as DefaultMutableTreeNode
                val file = root.depthFirstEnumeration().asSequence().map { it as DefaultMutableTreeNode }
                    .firstOrNull { it.isLeaf && it.toString().endsWith(": $filename") }
                if (file == null) {
                    false
                } else {
                    tree.selectionPath = TreePath(file.path)
                    requests.clear()
                    click(panel, "Open historical diff")
                    true
                }
            }
            if (!selected) continue
            try {
                await("native diff request") { requests.size == 1 }
                diffTimes.add((System.nanoTime() - started) / 1_000_000.0)
            } catch (failure: AssertionError) {
                val detail = withContext(Dispatchers.EDT) {
                    "operation=$label; selection=${tree(panel).selectionPath}; notes=${components(panel).filterIsInstance<JTextComponent>().joinToString { it.text }}"
                }
                throw AssertionError(detail, failure)
            }
            result.add(
                withContext(Dispatchers.EDT) {
                    val request = requests.single()
                    assertEquals(true, request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
                    assertTrue(request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)!!.contentEquals(booleanArrayOf(true, true)))
                    assertEquals(true, request.getUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE))
                    assertTrue(request.title!!.contains("Flow historical: $filename"))
                    assertTrue(request.contentTitles.all { it!!.contains("[") })
                    Reviewed((request.contents[0] as DocumentContent).document.text, (request.contents[1] as DocumentContent).document.text, request.title, request.contentTitles, label)
                },
            )
        }
        return result
    }

    private fun historyState(panel: SessionHistoryPanel) = components(panel).filterIsInstance<JList<*>>().joinToString { "${it.accessibleContext.accessibleName}: count=${it.model.size}, selected=${it.selectedIndex}" } +
        "; notes=" + components(panel).filterIsInstance<JTextComponent>().joinToString { it.text } + "; requests=" + historyTrace.takeLast(35).joinToString()

    private suspend fun await(description: String, diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        try {
            withTimeout(20000) {
                while (!withContext(Dispatchers.EDT) { condition() }) delay(25)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $description. ${withContext(Dispatchers.EDT) { diagnostic() }}", error)
        }
    }
    private fun closeThroughMenu(panel: AgentSessionPanel) {
        val previous = com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.YES)
        try {
            panel.composerMenu.components.filterIsInstance<javax.swing.JMenuItem>().single { it.text == "Close session" }.takeIf { it.isEnabled }?.doClick(0)
        } finally {
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(previous)
        }
    }
    private fun components(root: Container): List<Component> = root.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() } +
        if (root is ComposerContext) components(root.workspaceSettings) else emptyList()
    private fun field(root: Container, name: String) = components(root).filterIsInstance<JTextComponent>().single { it.accessibleContext.accessibleName == name }
    private fun button(root: Container, text: String): JButton {
        val buttons = components(root).filterIsInstance<JButton>()
        return buttons.singleOrNull { it.accessibleContext.accessibleName == text } ?: buttons.single { it.isVisible && it.text == text }
    }
    private fun click(root: Container, text: String) {
        val button = button(root, text)
        assertTrue("$text must be enabled", button.isEnabled)
        button.doClick(0)
    }
    private fun list(root: Container, name: String) = components(root).filterIsInstance<JList<*>>().single { it.accessibleContext.accessibleName == name }
    private fun tree(root: Container) = components(root).filterIsInstance<JTree>().single()

    private fun git(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=${if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"}", "-c", "commit.gpgSign=false", "-c", "user.name=Flow", "-c", "user.email=fixture@example.invalid") + arguments)
            .directory(root.toFile()).redirectErrorStream(true).apply {
                environment().keys.removeIf { it.startsWith("GIT_") }
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_CONFIG_GLOBAL"] = if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"
                environment()["GIT_TEMPLATE_DIR"] = ""
            }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        return output
    }
    private fun projectState(root: Path): Map<String, String> = buildMap {
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.startsWith(root.resolve(".git")) }.forEach { put(root.relativize(it).toString(), java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(it))) }
        }
        put(".git/HEAD-resolved", git(root, "rev-parse", "HEAD"))
        put(".git/index-entries", git(root, "ls-files", "--stage"))
        put(".git/status", git(root, "status", "--porcelain=v1", "--untracked-files=all"))
    }
}
