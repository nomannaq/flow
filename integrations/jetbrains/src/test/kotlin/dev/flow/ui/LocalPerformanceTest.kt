package dev.flow.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.vcs.commit.ChangesViewCommitPanel
import com.intellij.vcsUtil.VcsUtil
import dev.flow.bridge.AgentEvent
import dev.flow.bridge.AgentView
import dev.flow.bridge.EngineController
import dev.flow.bridge.EngineView
import dev.flow.bridge.LocalAgent
import dev.flow.bridge.LocalAgents
import dev.flow.bridge.ProjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.tree.DefaultTreeModel

/** Opt-in, local-only measurements. No installed agents, credentials, or inference requests. */
class LocalPerformanceTest : LightPlatformTestCase() {
    fun testMeasureLocalHotPaths() {
        val root = Files.createTempDirectory("flow local performance ")
        val results = JsonObject()
        try {
            results.add("selectedFileAdmissionMs", admission(root))
            results.add("installedCliDiscoveryMs", discovery(root))
            results.add("chatDraftProjectionMs", chat(results))
            results.add("streamPreviewMs", preview(root))
            results.addProperty("inferenceRequests", 0)
            results.addProperty("selectedFilesPerSample", 96)
            results.addProperty("selectedFileBytes", 32768)
            results.addProperty("warmupSamples", 2)
            val path = Path.of(requireNotNull(System.getProperty("flow.test.performanceOutput")) + "-local.json")
            Files.createDirectories(path.parent)
            Files.writeString(path, results.toString())
            println("Flow local performance: $results")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun samples(block: (Int) -> Double): JsonArray = JsonArray().apply {
        repeat(9) { index ->
            val elapsed = block(index)
            if (index >= 2) add(elapsed)
        }
    }

    private fun admission(root: Path): JsonArray = samples { sample ->
        val changes = (0 until 96).map { index ->
            val disk = Files.createDirectories(root.resolve("selection-$sample")).resolve("file-$index.txt")
            Files.writeString(disk, "a selected source line\n".repeat(1600).take(32768))
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(disk)!!
            val path = VcsUtil.getFilePath(file)
            Change(SimpleContentRevision("previous content\n", path, "HEAD"), CurrentContentRevision.create(path))
        }
        val tree = object : ChangesListView(project, true) {}
        val node = ChangesBrowserNode.createRoot()
        changes.forEach { node.add(ChangesBrowserNode.createChange(project, it)) }
        tree.updateTreeModel(DefaultTreeModel(node), ChangesTree.ALWAYS_RESET)
        tree.inclusionModel.setInclusion(changes)
        val panel = ChangesViewCommitPanel(project, tree)
        val document = EditorFactory.getInstance().createDocument("existing draft")
        try {
            val started = System.nanoTime()
            val selection = NativeCommitSelection(project, panel, document) { true }
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            assertTrue(selection.unchanged())
            Disposer.dispose(selection)
            elapsed
        } finally {
            Disposer.dispose(panel)
        }
    }

    private fun discovery(root: Path): JsonArray {
        val bin = Files.createDirectories(root.resolve("bin"))
        for (name in listOf("codex", "claude")) {
            Files.writeString(bin.resolve(name), "fixture").toFile().setExecutable(true)
        }
        val cache = root.resolve("RustRoverActive")
        for (ide in listOf(cache) + (1..4).map { root.resolve("IntelliJPerf$it") }) {
            for (agent in listOf("codex", "claude")) {
                repeat(12) { Files.createDirectories(ide.resolve("acp-agents/$agent/version-$it/node_modules/.bin")) }
            }
        }
        return samples {
            val started = System.nanoTime()
            repeat(10) {
                assertEquals(2, LocalAgents.discover(emptyMap(), cache, root.resolve("home"), listOf(bin), windowsHost = false, nativeCli = true).size)
            }
            (System.nanoTime() - started) / 10_000_000.0
        }
    }

    private fun chat(results: JsonObject): JsonArray {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("perf-chat"), { error("No engine should start") })
        val panel = AgentSessionPanel(controller, project)
        try {
            val events = (0 until 40).flatMap { turn ->
                listOf(AgentEvent("turn.started", "Explain module $turn", turnId = "t$turn"), AgentEvent("message.delta", "The selected module preserves the existing behavior. ".repeat(40), turnId = "t$turn"))
            }
            panel.update(EngineView(connected = true, agentSupported = true, agent = AgentView(id = "s1", status = "ready", events = events)))
            var version = 0
            var previous = panel.webState()
            return samples {
                val started = System.nanoTime()
                var bytes = 0
                repeat(20) {
                    runBlocking {
                        panel.webAction(
                            JsonObject().apply {
                                addProperty("action", "draft")
                                addProperty("version", ++version)
                                addProperty("text", "My edited draft $version")
                            },
                        )
                    }
                    val state = panel.webState()
                    bytes = ReactChatPanel.changedState(previous, state).toString().toByteArray().size
                    previous = state
                }
                results.addProperty("chatDraftPayloadBytes", bytes)
                (System.nanoTime() - started) / 20_000_000.0
            }
        } finally {
            panel.dispose()
            scope.cancel()
        }
    }

    private fun preview(root: Path): JsonArray {
        val script = root.resolve("stream fixture.py")
        Files.writeString(
            script,
            """
            import json, pathlib, sys, time
            root = pathlib.Path(sys.argv[1])
            sys.stdin.read()
            (root / 'ready').write_text('ready')
            deadline = time.monotonic() + 10
            while not (root / 'go').exists() and time.monotonic() < deadline:
                time.sleep(0.001)
            print(json.dumps({'type':'stream_event','event':{'delta':{'type':'text_delta','text':'feat: fast fixture'}}}), flush=True)
            while not (root / 'finish').exists() and time.monotonic() < deadline:
                time.sleep(0.001)
            print(json.dumps({'type':'result','is_error':False,'result':'feat: fast fixture'}), flush=True)
            """.trimIndent(),
        )
        return samples { sample ->
            val scratch = Files.createDirectories(root.resolve("preview-$sample"))
            val agent = LocalAgent("claude", System.getProperty("flow.test.python"), listOf(script.toString(), scratch.toString()), "fixture")
            val first = CompletableFuture<Long>()
            val progress = EmptyProgressIndicator()
            val work = CompletableFuture.supplyAsync {
                NativeTextGenerator.generate(agent, "selected diff", progress, onPartial = {
                    first.complete(System.nanoTime())
                    Files.writeString(scratch.resolve("finish"), "finish")
                })
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!Files.exists(scratch.resolve("ready")) && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue(Files.exists(scratch.resolve("ready")))
                val started = System.nanoTime()
                Files.writeString(scratch.resolve("go"), "go")
                val elapsed = (first.get(10, TimeUnit.SECONDS) - started) / 1_000_000.0
                assertEquals("feat: fast fixture", work.get(10, TimeUnit.SECONDS))
                elapsed
            } finally {
                progress.cancel()
                work.get(10, TimeUnit.SECONDS)
            }
        }
    }
}
