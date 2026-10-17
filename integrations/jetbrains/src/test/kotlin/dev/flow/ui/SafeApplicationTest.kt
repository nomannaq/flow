package dev.flow.ui

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.LightPlatformTestCase
import dev.flow.bridge.AgentProtocol
import dev.flow.bridge.EngineController
import dev.flow.bridge.ProjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.text.JTextComponent

class SafeApplicationTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testNativeMergeRenewedReviewApplyAndRollbackPreserveDeveloperWork() = runBlocking {
        val scratch = Files.createTempDirectory("flow-b19-").toRealPath()
        withContext(Dispatchers.EDT) { VfsRootAccess.allowRootAccess(testRootDisposable, scratch.toString()) }
        val repository = Files.createDirectory(scratch.resolve("project"))
        val data = Files.createDirectory(scratch.resolve("data"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processes = CopyOnWriteArrayList<Process>()
        lateinit var panel: ApplicationPanel
        var mounted = false
        val controller = EngineController(scope, ProjectId("safe-application"), launchProcess = {
            ProcessBuilder(requireNotNull(System.getProperty("flow.test.engine")), "--project-root", repository.toString(), "--data-root", data.toString()).start().also { processes.add(it) }
        }, pollMs = 25)
        try {
            git(repository, "init", "--initial-branch=main")
            Files.writeString(repository.resolve("probe.txt"), "base\n")
            Files.writeString(repository.resolve("sentinel.txt"), "base sentinel\n")
            git(repository, "add", ".")
            git(repository, "commit", "-m", "baseline")
            Files.writeString(repository.resolve("sentinel.txt"), "staged sentinel\n")
            git(repository, "add", "sentinel.txt")
            Files.writeString(repository.resolve("sentinel.txt"), "unstaged sentinel\n")
            Files.writeString(repository.resolve("untracked.txt"), "untracked sentinel\n")
            Files.writeString(repository.resolve("probe.txt"), "developer\n")
            val index = Files.readAllBytes(repository.resolve(".git/index"))
            controller.start()
            withTimeout(10000) { controller.view.first { it.connected } }
            val arguments = JsonArray().apply { add(requireNotNull(System.getProperty("flow.test.adapterFixture"))) }
            controller.agentRequest("sessions.start", AgentProtocol.start(requireNotNull(System.getProperty("flow.test.python")), arguments.toString(), git(repository, "rev-parse", "HEAD").trim(), "write first version", ""))
            val permission = withTimeout(15000) { controller.view.first { it.agent.permissions.isNotEmpty() } }.agent
            controller.agentRequest("permissions.respond", AgentProtocol.permission(requireNotNull(permission.id), permission.permissions.single().id, "once"))
            val ready = withTimeout(15000) { controller.view.first { it.agent.status == "ready" } }.agent
            controller.agentRequest("sessions.close", AgentProtocol.command(requireNotNull(ready.id)))
            withTimeout(10000) { controller.view.first { it.agent.status == "closed" } }
            val candidate = controller.historyRequest(
                "candidates.prepare",
                JsonObject().apply {
                    addProperty("sessionId", ready.id)
                    addProperty("writersStopped", true)
                },
            ).asJsonObject
            suspend fun accept(id: String) = controller.historyRequest(
                "candidates.decide",
                JsonObject().apply {
                    addProperty("candidateId", id)
                    addProperty("accept", true)
                    addProperty("writersStopped", true)
                },
            )
            accept(candidate["id"].asString)
            var renewed: String? = null
            withContext(Dispatchers.EDT) {
                val vf = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repository.resolve("probe.txt")))
                assertEquals("developer\n", FileDocumentManager.getInstance().getDocument(vf)!!.text)
                panel = ApplicationPanel(project, controller, scope, reviewCandidate = { renewed = it }, native = NativeApplication(project, repository), presentMerge = { request ->
                    val merge = request as TextMergeRequest
                    assertEquals(listOf("developer\n", "base\n", "flow-probe-1\n"), merge.contents.map { (it as com.intellij.diff.contents.DocumentContent).document.text })
                    WriteCommandAction.runWriteCommandAction(project) { merge.outputContent.document.setText("developer + agent resolved\n") }
                    merge.applyResult(MergeResult.RESOLVED)
                })
                mounted = true
                panel.selectCandidate(candidate["id"].asString)
                components(panel).filterIsInstance<JCheckBox>().single().doClick(0)
                click(panel, "Plan application")
            }
            await { status(panel).contains("Status: conflicted") && button(panel, "Load application history").isEnabled }
            withContext(Dispatchers.EDT) {
                val files = components(panel).filterIsInstance<JList<*>>().single { it.accessibleContext.accessibleName == "Application files" }
                files.selectedIndex = (0 until files.model.size).single { files.model.getElementAt(it).toString().endsWith("probe.txt") }
                click(panel, "Resolve conflict")
            }
            await { renewed != null && button(panel, "Load application history").isEnabled }
            val version = requireNotNull(renewed)
            assertFalse(candidate["id"].asString == version)
            // New bytes cannot use the original acceptance.
            assertEquals("pending", controller.historyRequest("candidates.get", JsonObject().apply { addProperty("candidateId", version) }).asJsonObject["decision"].asString)
            accept(version)
            val applyStarted = System.nanoTime()
            withContext(Dispatchers.EDT) {
                panel.selectCandidate(version) // Returning from review keeps the merge plan.
                components(panel).filterIsInstance<JCheckBox>().single().doClick(0)
                click(panel, "Apply result")
            }
            await { status(panel).contains("Status: complete") && button(panel, "Roll back Flow writes").isEnabled }
            val applyElapsedMs = (System.nanoTime() - applyStarted) / 1_000_000.0
            assertEquals("developer + agent resolved\n", Files.readString(repository.resolve("probe.txt")))
            assertTrue(index.contentEquals(Files.readAllBytes(repository.resolve(".git/index"))))
            val rollbackStarted = System.nanoTime()
            withContext(Dispatchers.EDT) { click(panel, "Roll back Flow writes") }
            await { status(panel).contains("Status: rolledBack") && button(panel, "Load application history").isEnabled }
            val rollbackElapsedMs = (System.nanoTime() - rollbackStarted) / 1_000_000.0
            assertEquals("developer\n", Files.readString(repository.resolve("probe.txt")))
            assertEquals("unstaged sentinel\n", Files.readString(repository.resolve("sentinel.txt")))
            assertEquals("untracked sentinel\n", Files.readString(repository.resolve("untracked.txt")))
            assertTrue(index.contentEquals(Files.readAllBytes(repository.resolve(".git/index"))))
            System.getProperty("flow.test.performanceOutput")?.let { output ->
                Files.writeString(
                    Path.of("$output-application.json"),
                    JsonObject().apply {
                        addProperty("fixture", "native Swing / two candidate files / one text conflict / dirty index and unrelated sentinels")
                        addProperty("applyIncludingRpcAndTestPollingMs", applyElapsedMs)
                        addProperty("rollbackIncludingRpcAndTestPollingMs", rollbackElapsedMs)
                        addProperty("visibleRenderingMeasured", false)
                    }.toString(),
                )
            }
            // A real process exit after the write but before its receipt must
            // neither replay the write nor lose the preimage on reconnect.
            withContext(Dispatchers.EDT) {
                val vf = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(repository.resolve("probe.txt")))
                val document = requireNotNull(FileDocumentManager.getInstance().getDocument(vf))
                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText("base\n")
                    FileDocumentManager.getInstance().saveDocument(document)
                }
            }
            val interrupted = controller.historyRequest(
                "apply.plan",
                JsonObject().apply {
                    addProperty("planId", "native-interrupted")
                    addProperty("candidateId", candidate["id"].asString)
                    addProperty("writersStopped", true)
                },
            ).asJsonObject
            val writer = NativeApplication(project, repository)
            val stamps = withContext(Dispatchers.EDT) { writer.capture(interrupted) }
            fun recoveryAction() = JsonObject().apply {
                addProperty("planId", interrupted["id"].asString)
                addProperty("candidateId", interrupted["candidateId"].asString)
                addProperty("writersStopped", true)
                add("documentStamps", stamps)
            }
            val files = interrupted.getAsJsonArray("files")
            val changed = files.mapIndexedNotNull { i, v -> if (v.asJsonObject["path"].asString == "probe.txt") i else null }.single()
            fun step(undo: Boolean) = JsonObject().apply {
                addProperty("planId", interrupted["id"].asString)
                addProperty("fileIndex", changed)
                addProperty("rollback", undo)
            }
            controller.historyRequest("apply.begin", recoveryAction())
            val ticket = controller.historyRequest("apply.authorize", step(false)).asJsonObject
            val oldEpoch = requireNotNull(controller.view.value.epoch)
            withContext(Dispatchers.EDT) { writer.write(ticket) }
            processes.last().destroyForcibly()
            withTimeout(15000) { controller.view.first { it.connected && it.epoch != oldEpoch } }
            assertFalse(controller.applicationEpochIsLive(oldEpoch))
            assertEquals("flow-probe-1\n", Files.readString(repository.resolve("probe.txt")))
            val observed = controller.historyRequest("apply.reconcile", recoveryAction()).asJsonObject
            assertEquals("recoveryRequired", observed["status"].asString)
            assertEquals("applied", observed.getAsJsonArray("files")[changed].asJsonObject["status"].asString)
            // Rollback is a new explicit host action, using fresh buffer stamps.
            val fresh = withContext(Dispatchers.EDT) { writer.capture(observed) }
            val undoAction = recoveryAction().apply { add("documentStamps", fresh) }
            controller.historyRequest("apply.rollback", undoAction)
            val undoTicket = controller.historyRequest("apply.authorize", step(true)).asJsonObject
            withContext(Dispatchers.EDT) { writer.write(undoTicket) }
            controller.historyRequest("apply.receipt", step(true))
            val outcome = controller.historyRequest("apply.finish", step(true)).asJsonObject
            assertEquals("rolledBack", outcome["status"].asString)
            assertEquals("base\n", Files.readString(repository.resolve("probe.txt")))
            assertTrue(index.contentEquals(Files.readAllBytes(repository.resolve(".git/index"))))
        } finally {
            if (mounted) withContext(Dispatchers.EDT) { panel.dispose() }
            scope.cancel()
            withTimeout(10000) { while (processes.any { it.isAlive }) delay(25) }
            Files.walk(scratch).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    fun testUnsavedTypingExternalWritesAndClosedBinaryPreconditions() = runBlocking {
        val root = Files.createTempDirectory("flow-b19-native-").toRealPath()
        withContext(Dispatchers.EDT) { VfsRootAccess.allowRootAccess(testRootDisposable, root.toString()) }
        val path = root.resolve("open.txt")
        Files.writeString(path, "base\n")
        try {
            withContext(Dispatchers.EDT) {
                val native = NativeApplication(project, root)
                val vf = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                val document = requireNotNull(FileDocumentManager.getInstance().getDocument(vf))
                val plan = JsonObject().apply {
                    addProperty("id", "native-plan")
                    addProperty("root", root.toString())
                    add(
                        "files",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("path", "open.txt")
                                    addProperty("status", "todo")
                                },
                            )
                        },
                    )
                }
                native.capture(plan)
                WriteCommandAction.runWriteCommandAction(project) { document.setText("unsaved typing\n") }
                val ticket = ticket("open.txt", "base\n".toByteArray(), "candidate\n".toByteArray())
                assertTrue(runCatching { native.write(ticket) }.isFailure)
                assertEquals("unsaved typing\n", document.text)
                assertEquals("base\n", Files.readString(path))
                assertTrue(runCatching { native.capture(plan) }.isFailure)
                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText("base\n")
                    FileDocumentManager.getInstance().saveDocument(document)
                }
                native.capture(plan)
                Files.writeString(path, "external edit\n")
                assertTrue(runCatching { native.write(ticket) }.isFailure)
                assertEquals("external edit\n", Files.readString(path))
                val binary = root.resolve("binary.bin")
                Files.write(binary, byteArrayOf(0, 1, 2))
                plan.getAsJsonArray("files").get(0).asJsonObject.addProperty("path", "binary.bin")
                native.capture(plan)
                native.write(ticket("binary.bin", byteArrayOf(0, 1, 2), byteArrayOf(0, -1, 3)))
                assertTrue(Files.readAllBytes(binary).contentEquals(byteArrayOf(0, -1, 3)))
                native.capture(plan)
                native.write(ticket("binary.bin", byteArrayOf(0, -1, 3), null))
                assertFalse(Files.exists(binary))
                // Closed-file VFS writes preserve exact encodings and line separators.
                for ((i, bytes) in listOf("\uFEFFBOM\r\n".toByteArray(), "CRLF\r\n".toByteArray(), byteArrayOf(-1, -2, 65, 0)).withIndex()) {
                    val name = "encoded-$i.dat"
                    val encoded = root.resolve(name)
                    Files.write(encoded, byteArrayOf(1, 2, 3))
                    plan.getAsJsonArray("files").get(0).asJsonObject.addProperty("path", name)
                    native.capture(plan)
                    native.write(ticket(name, byteArrayOf(1, 2, 3), bytes))
                    assertTrue(Files.readAllBytes(encoded).contentEquals(bytes))
                }
                // An open text document cannot normalize BOM/CRLF candidate bytes.
                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText("base\n")
                    FileDocumentManager.getInstance().saveDocument(document)
                }
                plan.getAsJsonArray("files").get(0).asJsonObject.addProperty("path", "open.txt")
                for (bytes in listOf("\uFEFFcandidate\n".toByteArray(), "candidate\r\n".toByteArray())) {
                    native.capture(plan)
                    assertTrue(runCatching { native.write(ticket("open.txt", "base\n".toByteArray(), bytes)) }.isFailure)
                    assertEquals("base\n", document.text)
                    assertEquals("base\n", Files.readString(path))
                }
            }
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    fun testPlannedSavedDocumentSurvivesCollectionBeforeAuthorizedWrite() = runBlocking {
        val root = Files.createTempDirectory("flow-planned-document-").toRealPath()
        withContext(Dispatchers.EDT) { VfsRootAccess.allowRootAccess(testRootDisposable, root.toString()) }
        val path = root.resolve("saved.txt")
        Files.writeString(path, "before\n")
        try {
            withContext(Dispatchers.EDT) {
                val native = NativeApplication(project, root)
                val vf = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                fun captureSaved(): java.lang.ref.WeakReference<com.intellij.openapi.editor.Document> {
                    val document = requireNotNull(FileDocumentManager.getInstance().getDocument(vf))
                    native.capture(
                        JsonObject().apply {
                            addProperty("id", "native-plan")
                            addProperty("root", root.toString())
                            add(
                                "files",
                                JsonArray().apply {
                                    add(
                                        JsonObject().apply {
                                            addProperty("path", "saved.txt")
                                            addProperty("status", "todo")
                                        },
                                    )
                                },
                            )
                        },
                    )
                    return java.lang.ref.WeakReference(document)
                }
                val captured = captureSaved()
                repeat(3) { System.gc() }
                assertNotNull("Planning must retain the clean buffer until the authorized write", captured.get())
                native.write(ticket("saved.txt", "before\n".toByteArray(), "after\n".toByteArray()))
                assertEquals("after\n", Files.readString(path))
            }
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    private fun ticket(path: String, before: ByteArray?, after: ByteArray?) = JsonObject().apply {
        addProperty("planId", "native-plan")
        addProperty("fileIndex", 0)
        addProperty("path", path)
        fun array(bytes: ByteArray?) = bytes?.let { JsonArray().apply { it.forEach { add(it.toInt() and 255) } } } ?: JsonNull.INSTANCE
        add("expected", array(before))
        add("replacement", array(after))
        addProperty("beforeMode", 420)
        addProperty("afterMode", 420)
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(15000) { while (!withContext(Dispatchers.EDT) { condition() }) delay(25) }
    private fun status(panel: ApplicationPanel) = components(panel).filterIsInstance<JTextComponent>().single { it.accessibleContext.accessibleName == "Application status" }.text
    private fun button(panel: ApplicationPanel, text: String) = components(panel).filterIsInstance<JButton>().single { it.text == text }
    private fun click(panel: ApplicationPanel, text: String) {
        val button = button(panel, text)
        assertTrue("$text: ${status(panel)}", button.isEnabled)
        button.doClick(0)
    }
    private fun components(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(components(it)) }
    }
    private fun git(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=${if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"}", "-c", "commit.gpgSign=false", "-c", "user.name=Flow", "-c", "user.email=fixture@example.invalid") + arguments).directory(root.toFile()).redirectErrorStream(true).apply {
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
}
