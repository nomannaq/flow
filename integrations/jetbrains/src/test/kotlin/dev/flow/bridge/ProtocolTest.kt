package dev.flow.bridge

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProtocolTest {
    private fun resource(name: String): JsonObject = javaClass.getResourceAsStream("/$name")!!.bufferedReader().use {
        ProtocolJson.parse(it.readText())
    }

    @Test
    fun goldenRoundTripsAndSchemaFieldsMatchRust() {
        val fixtures = resource("golden.json")
        for (name in listOf("initializeRequest", "healthRequest")) {
            val value = fixtures[name].asJsonObject
            assertEquals(value, ProtocolJson.request(ProtocolJson.request(value)))
        }
        for (name in listOf("initializeResponse", "healthResponse", "errorResponse", "shutdownResponse", "parseErrorResponse")) {
            val value = fixtures[name].asJsonObject
            assertEquals(value, ProtocolJson.response(ProtocolJson.response(value)))
        }
        val note = fixtures["cancelNotification"].asJsonObject
        assertEquals(note, ProtocolJson.notification(ProtocolJson.notification(note)))
        assertEquals(note, ProtocolJson.notification(ProtocolJson.cancel(RequestId.Number(21), EngineEpoch("fixture-epoch"))))
        val initialize = fixtures["initializeRequest"].asJsonObject["params"].asJsonObject
        assertEquals(
            initialize,
            ProtocolJson.initialize(
                InitializeParams(
                    1,
                    "Flow JetBrains — 日本語",
                    "0.1.0",
                    ProjectId("project-fixture"),
                    HostCapabilities(true, true, true, false),
                ),
            ),
        )
        val initialized = ProtocolJson.initialized(fixtures["initializeResponse"].asJsonObject["result"].asJsonObject)
        assertEquals(EngineEpoch("fixture-epoch"), initialized.epoch)
        assertFalse(initialized.capabilities.persistentEvents)
        assertEquals(FLOW_MAX_FRAME_BYTES, initialized.maxFrameBytes)
        assertEquals(FLOW_MAX_IN_FLIGHT, initialized.maxInFlight)
        assertEquals(FLOW_MAX_CONNECTION_REQUESTS, initialized.maxConnectionRequests)
        assertEquals(FLOW_MAX_TIMEOUT_MS, initialized.defaultTimeoutMs)
        val health = ProtocolJson.health(fixtures["healthResponse"].asJsonObject["result"].asJsonObject)
        assertEquals(ProjectId("project-fixture"), health.projectId)
        val event = fixtures["durableEvent"].asJsonObject
        assertEquals(event, ProtocolJson.event(ProtocolJson.event(event)))
        assertEquals("18446744073709551615", ProtocolJson.event(event).cursor.sequence)
        assertEquals("future.event", ProtocolJson.event(event).kind)
        assertTrue(ProtocolJson.receipt(fixtures["mutationReceipt"].asJsonObject).isTerminalSuccess)

        // Manual Kotlin DTOs are tied to Rust's exported schema and shared values.
        val defs = resource("schema.json")["\$defs"].asJsonObject
        val fields = mapOf(
            "InitializeParams" to initialize,
            "InitializeResult" to fixtures["initializeResponse"].asJsonObject["result"].asJsonObject,
            "HealthResult" to fixtures["healthResponse"].asJsonObject["result"].asJsonObject,
            "HostCapabilities" to initialize["capabilities"].asJsonObject,
            "EngineCapabilities" to fixtures["initializeResponse"].asJsonObject["result"].asJsonObject["capabilities"].asJsonObject,
            "DurableEvent" to event,
            "MutationReceipt" to fixtures["mutationReceipt"].asJsonObject,
        )
        fields.forEach { (name, value) -> assertEquals(name, value.keySet(), defs[name].asJsonObject["properties"].asJsonObject.keySet()) }
    }

    @Test
    fun malformedInputsAndFutureValuesAreHandled() {
        for (text in listOf("{bad}", "{'jsonrpc':'2.0'}", "[]", "{} {}")) {
            assertThrows(IllegalArgumentException::class.java) { ProtocolJson.parse(text) }
        }
        for (text in listOf(
            """{"jsonrpc":"2.0","id":1,"result":null,"error":{"code":1,"message":"bad"}}""",
            """{"jsonrpc":"2.0","id":1}""",
            """{"jsonrpc":"2.0","id":9007199254740992,"result":null}""",
            """{"jsonrpc":"2.0","id":1.5,"result":null}""",
            """{"jsonrpc":"1.0","id":1,"result":null}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { ProtocolJson.response(ProtocolJson.parse(text)) }
        }
        val malformed = ProtocolJson.parse("""{"jsonrpc":"2.0","id":1,"error":"private-fixture-token"}""")
        val rejected = assertThrows(IllegalArgumentException::class.java) { ProtocolJson.response(malformed) }
        assertFalse(rejected.message.orEmpty().contains("private-fixture-token"))
        val receipt = resource("golden.json")["mutationReceipt"].asJsonObject
        receipt.addProperty("status", "newStatus")
        assertFalse(ProtocolJson.receipt(receipt).isTerminalSuccess)
        val reply = resource("golden.json")["healthResponse"].asJsonObject
        reply.addProperty("futureField", true)
        assertEquals(RequestId.Number(21), ProtocolJson.response(reply).id)
        val oversized = JsonObject().apply { addProperty("value", "λ".repeat(FLOW_MAX_FRAME_BYTES)) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolJson.frame(oversized) }
    }

    @Test
    fun correlationRejectsLateDuplicateAndStaleReplies() {
        val epoch = EngineEpoch("epoch-current")
        val tracker = RequestTracker(epoch)
        val id = RequestId.Number(1)
        tracker.register(id, 1, 0)
        val reply = RpcResponse(id, epoch, RpcOutcome.Success(com.google.gson.JsonNull.INSTANCE))
        assertFalse(tracker.accept(reply.copy(epoch = EngineEpoch("epoch-old")), 0))
        assertFalse(tracker.accept(reply, 1_000_000))
        assertEquals(listOf(id), tracker.expire(1_000_000))
        assertFalse(tracker.accept(reply, 1_000_001))
        assertThrows(IllegalStateException::class.java) { tracker.register(id, 1, 1_000_001) }
        val other = RequestId.Text("other")
        tracker.register(other, 100, 2_000_000)
        assertTrue(tracker.accept(reply.copy(id = other), 2_000_001))
        assertFalse(tracker.accept(reply.copy(id = other), 2_000_002))
        tracker.register(RequestId.Number(2), 100, 3_000_000)
        assertEquals(listOf(RequestId.Number(2)), tracker.disconnect())
        assertThrows(IllegalStateException::class.java) { tracker.register(RequestId.Number(3), 100) }
    }

    @Test
    fun kotlinClientTalksToRealRustEngine() {
        val engine = requireNotNull(System.getProperty("flow.test.engine")) { "Gradle must build the test engine" }
        val process = ProcessBuilder(engine).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val input = process.inputStream.bufferedReader(Charsets.UTF_8)
            fun receive(): RpcResponse = executor.submit<RpcResponse> {
                ProtocolJson.response(ProtocolJson.parse(requireNotNull(input.readLine())))
            }.get(5, TimeUnit.SECONDS)
            fun send(request: RpcRequest) {
                process.outputStream.write(ProtocolJson.frame(ProtocolJson.request(request)))
                process.outputStream.flush()
            }
            val initId = RequestId.Text("init")
            send(
                RpcRequest(
                    initId,
                    "engine.initialize",
                    ProtocolJson.initialize(
                        InitializeParams(1, "JetBrains 日本語", "test", ProjectId("project-test")),
                    ),
                ),
            )
            val reply = receive()
            assertEquals(initId, reply.id)
            val initialized = ProtocolJson.initialized((reply.outcome as RpcOutcome.Success).result.asJsonObject)
            assertEquals(reply.epoch, initialized.epoch)
            val epoch = initialized.epoch
            val tracker = RequestTracker(epoch)
            val healthId = RequestId.Number(1)
            tracker.register(healthId, 5000)
            send(RpcRequest(healthId, "engine.health", epoch = epoch))
            val health = receive()
            assertTrue(tracker.accept(health))
            assertEquals(ProjectId("project-test"), ProtocolJson.health((health.outcome as RpcOutcome.Success).result.asJsonObject).projectId)
            send(RpcRequest(RequestId.Number(2), "engine.health", JsonObject().apply { addProperty("waitMs", 30000) }, epoch, 5))
            assertEquals(-32801, (receive().outcome as RpcOutcome.Failure).error.code)
            send(RpcRequest(RequestId.Number(3), "engine.shutdown", epoch = epoch))
            assertTrue((receive().outcome as RpcOutcome.Success).result.isJsonNull)
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }
}
