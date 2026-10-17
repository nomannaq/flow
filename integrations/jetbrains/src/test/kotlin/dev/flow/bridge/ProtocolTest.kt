package dev.flow.bridge

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProtocolTest {
    @Test
    fun connectionOnlyAndConfigSelectionNeverInventValuesOrSendAPrompt() {
        val request = AgentProtocol.start(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "adapter").toAbsolutePath().toString(), "[]", "a".repeat(40), "", "", connectOnly = true)
        assertTrue(request["connectOnly"].asBoolean)
        assertEquals("", request["prompt"].asString)
        val option = AgentConfigOption("speed", "Speed", "", "", "select", com.google.gson.JsonPrimitive("standard"), listOf(AgentConfigChoice("standard", "Standard"), AgentConfigChoice("fast", "Fast")))
        assertEquals("fast", AgentProtocol.configure("session", option, com.google.gson.JsonPrimitive("fast"))["value"].asString)
        assertThrows(IllegalArgumentException::class.java) { AgentProtocol.configure("session", option, com.google.gson.JsonPrimitive("invented")) }
        assertThrows(IllegalArgumentException::class.java) { AgentProtocol.configure("session", option, com.google.gson.JsonPrimitive(true)) }
    }

    @Test
    fun oneTimePermissionSemanticsDoNotDependOnDisplayLabels() {
        val input = resource("golden.json").getAsJsonObject("agentView").deepCopy()
        val option = input.getAsJsonArray("permissions")[0].asJsonObject.getAsJsonArray("options")[0].asJsonObject
        option.addProperty("name", "Proceed just this time — 日本語")
        option.addProperty("allowOnce", true)
        assertEquals(setOf("once"), AgentProtocol.view(input).permissions.single().allowOnce)
        option.addProperty("name", "Once")
        option.remove("allowOnce")
        assertTrue(AgentProtocol.view(input).permissions.single().allowOnce.isEmpty())
    }
    private fun resource(name: String): JsonObject = javaClass.getResourceAsStream("/$name")!!.bufferedReader().use {
        ProtocolJson.parse(it.readText())
    }

    @Test
    fun historicalDiffIdentityRejectsWrongContentAndKeepsAbsentSidesDistinct() {
        val file = ProtocolJson.parse("""{"index":0,"oldPath":"old.txt","newPath":"new.txt","contentIds":{"before":"${"a".repeat(64)}","after":"${"b".repeat(64)}"}}""")
        val selection = HistorySelection.from("session", "operation", file)
        val result = file.deepCopy().apply {
            addProperty("sessionId", "session")
            addProperty("operationId", "operation")
            addProperty("fileIndex", 0)
            addProperty("before", "one\r\n")
            addProperty("after", "two\n")
        }
        val spec = selection.diff(result)!!
        assertEquals("one\r\n", spec.before)
        assertTrue(spec.identity.contains("a".repeat(64)))
        assertTrue(spec.beforeTitle.contains("old.txt"))
        assertTrue(spec.afterTitle.contains("new.txt"))
        assertEquals(file["contentIds"], selection.query()["contentIds"])
        val wrong = result.deepCopy().apply { getAsJsonObject("contentIds").addProperty("after", "c".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { selection.diff(wrong) }
        val stale = result.deepCopy().apply { addProperty("operationId", "other") }
        assertThrows(IllegalArgumentException::class.java) { selection.diff(stale) }
        result.add("after", com.google.gson.JsonNull.INSTANCE)
        assertNull(selection.diff(result)) // Unsupported content is not an empty/deleted file.
        result.addProperty("after", "")
        assertEquals("", selection.diff(result)!!.after)
    }

    @Test
    fun codexModelOverrideIsOneArgumentAndViewRendersJoinedMessages() {
        val request = AgentProtocol.start("/tmp/codex-acp", "[]", "a".repeat(40), "hello", "", model = "test-model")
        assertEquals(listOf("-c", "model=\"test-model\""), request.getAsJsonArray("arguments").map { it.asString })
        assertThrows(IllegalArgumentException::class.java) {
            AgentProtocol.start("/tmp/codex-acp", "[]", "a".repeat(40), "hello", "", model = "bad\nvalue")
        }
        val value = resource("golden.json")["agentView"].asJsonObject
        value.addProperty("recordedOperations", 3)
        value.addProperty("compatibility", "Unverified adapter")
        value.add("agentInfo", JsonObject().apply { addProperty("name", "claude-agent-acp") })
        value.add(
            "activity",
            com.google.gson.JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("kind", "message.delta")
                        addProperty("text", "Hello world")
                    },
                )
            },
        )
        val view = AgentProtocol.view(value)
        assertEquals(listOf("Hello world"), view.activity)
        assertEquals(3L, view.recordedOperations)
        assertEquals("Unverified adapter", view.compatibility)
        assertEquals("claude-agent-acp", view.agentName)
        value.add("agentInfo", com.google.gson.JsonNull.INSTANCE)
        assertEquals("", AgentProtocol.view(value).agentName)
    }

    @Test
    fun historyDamageIsVisibleWithoutChangingRecordedCounts() {
        val snapshot = resource("golden.json")["stateSnapshot"].asJsonObject
        snapshot["state"].asJsonObject.add(
            "history",
            ProtocolJson.parse("""{"bytes":1000,"budgetBytes":2000,"writable":false,"reason":"history content failed verification"}"""),
        )
        val value = ProtocolJson.snapshot(snapshot)
        assertEquals(2L, value.sessions)
        assertTrue(value.historyStatus.contains("1000 / 2000"))
        assertTrue(value.historyStatus.contains("history content failed verification"))
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
        val snapshot = ProtocolJson.snapshot(fixtures["stateSnapshot"].asJsonObject)
        assertEquals(2L, snapshot.sessions)
        assertEquals(3L, snapshot.operations)
        assertEquals("18446744073709551615", snapshot.highWater.sequence)
        val replay = ProtocolJson.replay(fixtures["replayPage"].asJsonObject)
        assertEquals(snapshot.highWater, replay.highWater)
        assertEquals(1, replay.events.size)
        assertFalse(replay.hasMore)
        assertEquals(fixtures["replayParams"], ProtocolJson.replayParams(EventCursor("store-fixture", "9007199254740993")))

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

    @org.junit.Test
    fun cancelledRequestsFreeCapacityWithoutReusingIds() {
        val tracker = RequestTracker(EngineEpoch("cancel-fixture"))
        repeat(100) { value ->
            val id = RequestId.Number(value.toLong())
            tracker.register(id, 1000)
            tracker.abandon(id)
        }
        tracker.register(RequestId.Number(101), 1000)
        org.junit.Assert.assertEquals(1, tracker.disconnect().size)
    }
}
