package dev.flow.ui

import com.google.gson.JsonArray
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.LightPlatformTestCase
import dev.flow.bridge.EngineController
import dev.flow.bridge.ProjectId
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
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JTree
import javax.swing.text.JTextComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Real Swing listeners -> controller -> Rust -> ACP fixture -> immutable native diff requests. */
class FirstVerticalSliceTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testTwoEditsSurviveRestartAndLiveDeletion() = scenario(null)
    fun testCancellationAfterFirstWriteKeepsPartialHistory() = scenario("--cancel-after-write")
    fun testFailedSecondWriteStillRecordsChangedBytes() = scenario("--fail-second-write")

    private fun scenario(flag: String?) = runBlocking {
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
        val controller = EngineController(scope, ProjectId("vertical-slice"), launchProcess = {
            ProcessBuilder(requireNotNull(System.getProperty("flow.test.engine")), "--project-root", repository.toString(), "--data-root", data.toString())
                .start().also { processes.add(it) }
        }, pollMs = 25)
        suspend fun mount() = withContext(Dispatchers.EDT) {
            taskPanel = AgentSessionPanel(controller)
            historyPanel = SessionHistoryPanel(project, controller, scope, presentDiff = { requests.add(it) }, newConversation = { error("Review must not start a conversation") })
            mounted = true
            observer = scope.launch(Dispatchers.EDT) {
                controller.view.collect {
                    taskPanel.update(it)
                    historyPanel.update(it)
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
            controller.start()
            await("engine connection") { controller.view.value.connected }
            withContext(Dispatchers.EDT) {
                field(taskPanel, "ACP adapter executable").text = "/usr/bin/python3"
                field(taskPanel, "Model override").text = ""
                field(taskPanel, "Authentication method").text = ""
                field(taskPanel, "Advanced adapter arguments").text = JsonArray().apply {
                    add(requireNotNull(System.getProperty("flow.test.adapterFixture")))
                    flag?.let { add(it) }
                }.toString()
                click(taskPanel, "Use current commit")
            }
            await("baseline displayed") { field(taskPanel, "Clean baseline commit").text.length == 40 }
            suspend fun submit(first: Boolean) {
                withContext(Dispatchers.EDT) {
                    field(taskPanel, "Task prompt").text = if (first) "Write first version" else "Write second version"
                    click(taskPanel, if (first) "Start Codex session" else "Send follow-up")
                }
                await("one-time permission") { controller.view.value.agent.permissions.isNotEmpty() && components(taskPanel).filterIsInstance<JButton>().any { it.text == "Once" } }
                withContext(Dispatchers.EDT) { click(taskPanel, "Once") }
            }
            submit(true)
            if (flag == "--cancel-after-write") {
                await("first write before cancellation") {
                    controller.view.value.agent.workspace?.let { Files.exists(Path.of(it).resolve("probe.txt")) } == true
                }
                withContext(Dispatchers.EDT) { click(taskPanel, "Cancel turn") }
            }
            await("first turn ready") { controller.view.value.agent.status == "ready" }
            if (flag != "--cancel-after-write") {
                submit(false)
                await("second turn settled") { controller.view.value.agent.status == if (flag == null) "ready" else "failed" }
            }
            val expected = if (flag == "--cancel-after-write") listOf("" to "flow-probe-1\n") else listOf("" to "flow-probe-1\n", "flow-probe-1\n" to "flow-probe-2\n")
            val workspace = Path.of(requireNotNull(controller.view.value.agent.workspace))
            assertEquals(expected.last().second, Files.readString(workspace.resolve("probe.txt")))
            val firstReview = review(historyPanel, requests)
            assertEquals(expected, firstReview.map { it.before to it.after })
            if (flag == "--cancel-after-write") assertTrue(firstReview.single().operation.contains("interrupted"))
            if (flag == "--fail-second-write") assertTrue(firstReview.last().operation.contains("failed"))
            assertEquals(before, projectState(repository))

            if (controller.view.value.agent.status == "ready") {
                withContext(Dispatchers.EDT) { click(taskPanel, "Close session") }
                await("session shutdown") { controller.view.value.agent.status in listOf("closed", "failed") }
            }
            // Unknown shutdown remains failed in restrictive sandboxes; never rewrite ownership evidence.
            val oldPid = controller.view.value.pid
            withContext(Dispatchers.EDT) {
                observer?.cancel()
                historyPanel.dispose()
            }
            controller.restart()
            await("replacement engine") { controller.view.value.connected && controller.view.value.pid != oldPid }
            Files.delete(workspace.resolve("probe.txt"))
            mount() // fresh Swing models, no retained selection or cached request
            val secondReview = review(historyPanel, requests)
            assertEquals(firstReview, secondReview)
            assertEquals(before, projectState(repository))
            assertNull(controller.view.value.agent.id) // reading history did not launch an agent
        } finally {
            withContext(Dispatchers.EDT) {
                observer?.cancel()
                if (mounted) historyPanel.dispose()
            }
            scope.cancel()
            withTimeout(15000) { scope.coroutineContext[Job]!!.join() }
            processes.forEach { if (it.isAlive) it.destroyForcibly() }
            processes.forEach { assertTrue("Engine must exit", it.waitFor(10, TimeUnit.SECONDS)) }
            root.toFile().deleteRecursively()
        }
    }

    private data class Reviewed(val before: String, val after: String, val title: String?, val titles: List<String?>, val operation: String)

    private suspend fun review(panel: SessionHistoryPanel, requests: MutableList<SimpleDiffRequest>): List<Reviewed> {
        await("history connection") { button(panel, "Refresh sessions").isEnabled }
        withContext(Dispatchers.EDT) { click(panel, "Refresh sessions") }
        await("retained session") { list(panel, "Sessions").model.size == 1 }
        withContext(Dispatchers.EDT) { list(panel, "Sessions").selectedIndex = 0 }
        await("turns") { list(panel, "Turns in the selected session").model.size >= 2 }
        withContext(Dispatchers.EDT) { click(panel, "All operations") }
        await("operations") { list(panel, "Recorded operations").model.size > 0 }
        val result = mutableListOf<Reviewed>()
        val count = withContext(Dispatchers.EDT) { list(panel, "Recorded operations").model.size }
        for (index in 0 until count) {
            val label = withContext(Dispatchers.EDT) {
                list(panel, "Recorded operations").apply { selectedIndex = index }.selectedValue.toString()
            }
            await("file page") { tree(panel).model.getChildCount(tree(panel).model.root) > 0 }
            val selected = withContext(Dispatchers.EDT) {
                val tree = tree(panel)
                val root = tree.model.root as DefaultMutableTreeNode
                val file = root.depthFirstEnumeration().asSequence().map { it as DefaultMutableTreeNode }
                    .firstOrNull { it.isLeaf && it.toString().endsWith(": probe.txt") }
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
                    assertTrue(request.title!!.contains("Flow historical: probe.txt"))
                    assertTrue(request.contentTitles.all { it!!.contains("[") })
                    Reviewed((request.contents[0] as DocumentContent).document.text, (request.contents[1] as DocumentContent).document.text, request.title, request.contentTitles, label)
                },
            )
        }
        return result
    }

    private suspend fun await(description: String, condition: () -> Boolean) {
        try {
            withTimeout(20000) {
                while (!withContext(Dispatchers.EDT) { condition() }) delay(25)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $description", error)
        }
    }
    private fun components(root: Container): List<Component> = root.components.flatMap { listOf(it) + if (it is Container) components(it) else emptyList() }
    private fun field(root: Container, name: String) = components(root).filterIsInstance<JTextComponent>().single { it.accessibleContext.accessibleName == name }
    private fun button(root: Container, text: String) = components(root).filterIsInstance<JButton>().single { it.text == text }
    private fun click(root: Container, text: String) {
        val button = button(root, text)
        assertTrue("$text must be enabled", button.isEnabled)
        button.doClick(0)
    }
    private fun list(root: Container, name: String) = components(root).filterIsInstance<JList<*>>().single { it.accessibleContext.accessibleName == name }
    private fun tree(root: Container) = components(root).filterIsInstance<JTree>().single()

    private fun git(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("/usr/bin/git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false", "-c", "user.name=Flow", "-c", "user.email=fixture@example.invalid") + arguments)
            .directory(root.toFile()).redirectErrorStream(true).apply {
                environment().keys.removeIf { it.startsWith("GIT_") }
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
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
