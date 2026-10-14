package dev.flow.bridge

import com.google.gson.JsonNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class EngineBridgeTest {
    @Test
    fun boundedFramesAndReplayRejectCorruption() {
        assertEquals("hello", EngineConnection.readFrame(ByteArrayInputStream("hello\n".toByteArray())))
        assertNull(EngineConnection.readFrame(ByteArrayInputStream(byteArrayOf())))
        assertThrows(IllegalArgumentException::class.java) {
            EngineConnection.readFrame(ByteArrayInputStream(ByteArray(FLOW_MAX_FRAME_BYTES + 1) { 65 }))
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            EngineConnection.readFrame(ByteArrayInputStream(byteArrayOf(-1, 10)))
        }
        val cursor = EventCursor("store", "9007199254740992")
        val events = (1..100).map { offset ->
            DurableEvent(EventCursor("store", (cursor.sequence.toULong() + offset.toUInt()).toString()), 1, "workspace", null, "future.kind", JsonNull.INSTANCE)
        }
        val page = ReplayPage(events, events.last().cursor, false)
        assertEquals(events.last().cursor, EngineController.advance(cursor, page))
        for (bad in listOf(page.copy(events = events.drop(1)), page.copy(events = events + events.last()), page.copy(highWater = EventCursor("other", "0")), page.copy(hasMore = true))) {
            assertThrows(IllegalArgumentException::class.java) { EngineController.advance(cursor, bad) }
        }
    }

    @Test
    fun realEngineTwoProjectsHealthRestartAndScopeDisposal() = runBlocking {
        val root = Files.createTempDirectory("flow-bridge-")
        val data = Files.createDirectory(root.resolve("data"))
        val processes = CopyOnWriteArrayList<Process>()
        val scopes = List(2) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        try {
            val controllers = scopes.mapIndexed { index, scope ->
                val project = Files.createDirectory(root.resolve("project $index"))
                Files.writeString(project.resolve("sentinel.txt"), "developer content")
                EngineController(scope, ProjectId("project-$index"), launchProcess = {
                    ProcessBuilder(requireNotNull(System.getProperty("flow.test.engine")), "--project-root", project.toString(), "--data-root", data.toString())
                        .start().also { processes.add(it) }
                }, pollMs = 25, retryMs = 10)
            }
            controllers.forEach { controller ->
                repeat(10) { controller.start() }
                controller.health()
            }
            withTimeout(10000) { controllers.forEach { it.view.first { view -> view.health.contains("healthy") } } }
            assertEquals(2, processes.size)
            assertNotEquals(controllers[0].view.value.pid, controllers[1].view.value.pid)
            val old = controllers[0].view.value.pid
            controllers[0].restart()
            withTimeout(10000) { controllers[0].view.first { it.connected && it.pid != old } }
            assertFalse(processes.first { it.pid() == old }.isAlive)
            assertTrue(controllers[1].view.value.connected)
            val crashed = controllers[0].view.value.pid
            processes.first { it.pid() == crashed }.destroyForcibly()
            withTimeout(10000) { controllers[0].view.first { it.connected && it.pid != crashed } }
            assertFalse(controllers[0].view.value.health.contains("healthy"))
            scopes[0].cancel()
            scopes[0].coroutineContext[kotlinx.coroutines.Job]!!.join()
            assertTrue(processes.first { it.pid() == controllers[1].view.value.pid }.isAlive)
            assertEquals("developer content", Files.readString(root.resolve("project 0/sentinel.txt")))
        } finally {
            scopes.forEach { it.cancel() }
            withTimeout(10000) { scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]!!.join() } }
            assertTrue(processes.none { it.isAlive })
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun replayFloodAndInvalidCursorRefreshAuthoritativeSnapshot() = runBlocking {
        for (mode in listOf("replay", "cursor-reset")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val process = fake(mode)
            val controller = EngineController(scope, ProjectId("test"), launchProcess = { process }, pollMs = 10)
            try {
                controller.start()
                withTimeout(10000) { controller.view.first { it.connected && it.operations == 200L } }
                delay(50)
                assertEquals(200L, controller.view.value.operations)
            } finally {
                scope.cancel()
                scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
                assertFalse(process.isAlive)
            }
        }
    }

    private fun fake(mode: String): Process {
        val script = requireNotNull(System.getProperty("flow.test.fakeEngine"))
        return ProcessBuilder("/usr/bin/python3", script, mode).start()
    }

    @Test
    fun fakeInjectionFloodStaleReplyTimeoutAndCleanup() = runBlocking {
        for (mode in listOf("flood", "hang")) {
            val process = fake(mode)
            coroutineScope {
                val connection = EngineConnection(process, this)
                try {
                    connection.initialize(ProjectId("test"))
                    if (mode == "flood") {
                        val result = connection.request("engine.health")
                        assertEquals("fixture", ProtocolJson.health(result.asJsonObject).engineVersion)
                    } else {
                        var timedOut = false
                        try {
                            connection.request("engine.health", timeoutMs = 100)
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            timedOut = true
                        }
                        assertTrue(timedOut)
                    }
                } finally {
                    connection.close()
                }
            }
            assertFalse(process.isAlive)
        }
    }

    @Test
    fun brokenEngineStopsAfterBoundedRetries() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processes = CopyOnWriteArrayList<Process>()
        val controller = EngineController(scope, ProjectId("test"), launchProcess = {
            fake("broken").also { processes.add(it) }
        }, retryMs = 5)
        try {
            controller.start()
            withTimeout(10000) { controller.view.first { it.status.startsWith("Disconnected. Check") } }
            delay(100)
            assertEquals(4, processes.size)
            assertTrue(processes.none { it.isAlive })
            assertFalse(controller.view.value.connected)
            assertFalse(controller.view.value.health.contains("healthy"))
        } finally {
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        }
    }
}
