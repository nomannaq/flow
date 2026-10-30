package dev.flow.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.LightPlatformTestCase
import dev.flow.bridge.AgentProtocol
import dev.flow.bridge.EngineController
import dev.flow.bridge.ProjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Composer commands traverse the host bridge, Rust engine, and offline ACP mode APIs. */
class ModeSwitchTest : LightPlatformTestCase() {
    override fun runInDispatchThread() = false

    fun testPlanningAndImplementationSwitchWithoutSendingOrChangingPermissions() = runBlocking {
        val root = Files.createTempDirectory("flow-mode-switch-")
        val repository = Files.createDirectory(root.resolve("project"))
        val data = Files.createDirectory(root.resolve("data"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processes = mutableListOf<Process>()
        val controller = EngineController(scope, ProjectId("mode-switch"), launchProcess = {
            ProcessBuilder(requireNotNull(System.getProperty("flow.test.engine")), "--project-root", repository.toString(), "--data-root", data.toString())
                .start().also { processes.add(it) }
        }, pollMs = 25)
        val panel = withContext(Dispatchers.EDT) { AgentSessionPanel(controller) }
        suspend fun awaitReady(configId: String? = null, expected: String? = null) = withTimeout(15000) {
            while (controller.view.value.agent.let { agent -> agent.status != "ready" || configId != null && agent.configOptions.find { it.id == configId }?.currentValue?.asString != expected }) delay(25)
        }
        try {
            fun git(vararg args: String): String {
                val command = listOf("git", "-c", "core.hooksPath=${if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"}", "-c", "commit.gpgSign=false", "-c", "user.name=Flow", "-c", "user.email=fixture@example.invalid") + args
                val process = ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true).start()
                val output = process.inputStream.readAllBytes().decodeToString()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(output, 0, process.exitValue())
                return output.trim()
            }
            git("init", "--initial-branch=main")
            Files.writeString(repository.resolve("tracked.txt"), "developer baseline\n")
            git("add", "tracked.txt")
            git("commit", "-m", "baseline")
            val commit = git("rev-parse", "HEAD")
            controller.start()
            withTimeout(15000) { while (!controller.view.value.connected) delay(25) }
            for (legacy in listOf(false, true)) {
                controller.submitAgent(
                    "sessions.start",
                    JsonObject().apply {
                        addProperty("executable", requireNotNull(System.getProperty("flow.test.python")))
                        add(
                            "arguments",
                            JsonArray().apply {
                                add(requireNotNull(System.getProperty("flow.test.adapterFixture")))
                                if (legacy) {
                                    add("--legacy-modes")
                                } else {
                                    add("--configuration")
                                    add("--collaboration-modes")
                                }
                            },
                        )
                        addProperty("commit", commit)
                        addProperty("prompt", "")
                        addProperty("connectOnly", true)
                    },
                )
                awaitReady()
                val configId = if (legacy) "mode" else "collaboration_mode"
                for (next in listOf("plan", if (legacy) "ask" else "default")) {
                    withContext(Dispatchers.EDT) {
                        panel.update(controller.view.value)
                        val options = controller.view.value.agent.configOptions
                        panel.webAction(
                            JsonObject().apply {
                                addProperty("action", "mode")
                                addProperty("sessionId", controller.view.value.agent.id)
                                addProperty("configId", configId)
                                addProperty("value", next)
                                add("currentValue", options.single { it.id == configId }.currentValue)
                                add("modelValue", options.firstOrNull { it.id == "model" }?.currentValue ?: com.google.gson.JsonNull.INSTANCE)
                            },
                        )
                    }
                    awaitReady(configId, next)
                    val agent = controller.view.value.agent
                    assertEquals(next, agent.configOptions.single { it.id == configId }.currentValue.asString)
                    if (!legacy) assertEquals("ask", agent.configOptions.single { it.id == "mode" }.currentValue.asString)
                    assertFalse(agent.events.any { it.kind == "turn.started" })
                    assertTrue(agent.permissions.isEmpty())
                }
                controller.agentRequest("sessions.close", AgentProtocol.command(requireNotNull(controller.view.value.agent.id)))
                withTimeout(15000) { while (controller.view.value.agent.status != "closed") delay(25) }
            }
            assertEquals("developer baseline\n", Files.readString(repository.resolve("tracked.txt")))
            assertEquals("", git("status", "--porcelain"))
        } finally {
            withContext(Dispatchers.EDT) { panel.dispose() }
            scope.cancel()
            withTimeout(15000) { scope.coroutineContext[Job]!!.join() }
            processes.forEach { if (it.isAlive) it.destroyForcibly() }
            processes.forEach { assertTrue(it.waitFor(10, TimeUnit.SECONDS)) }
            root.toFile().deleteRecursively()
        }
    }
}
