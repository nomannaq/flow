package dev.flow.bridge

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness

const val FLOW_PROTOCOL_VERSION = 1
const val FLOW_MAX_FRAME_BYTES = 1024 * 1024
const val FLOW_MAX_IN_FLIGHT = 32
const val FLOW_MAX_CONNECTION_REQUESTS = 4096
const val FLOW_MAX_TIMEOUT_MS = 30_000L

private val opaqueIdPattern = Regex("[A-Za-z0-9._:-]{1,128}")

@JvmInline
value class EngineEpoch(val value: String) {
    init {
        require(opaqueIdPattern.matches(value)) { "Invalid engine epoch" }
    }
}

@JvmInline
value class ProjectId(val value: String) {
    init {
        require(opaqueIdPattern.matches(value)) { "Invalid project ID" }
    }
}

sealed interface RequestId {
    data class Text(val value: String) : RequestId {
        init {
            require(opaqueIdPattern.matches(value)) { "Invalid request ID" }
        }
    }
    data class Number(val value: Long) : RequestId {
        init {
            require(value in -9_007_199_254_740_991L..9_007_199_254_740_991L) { "Invalid request ID" }
        }
    }
}

data class RpcRequest(
    val id: RequestId,
    val method: String,
    val params: JsonObject = JsonObject(),
    val epoch: EngineEpoch? = null,
    val timeoutMs: Long? = null,
)

data class RpcNotification(val method: String, val params: JsonObject, val epoch: EngineEpoch?)
data class RpcError(val code: Int, val message: String)
sealed interface RpcOutcome {
    data class Success(val result: JsonElement) : RpcOutcome
    data class Failure(val error: RpcError) : RpcOutcome
}
data class RpcResponse(val id: RequestId?, val epoch: EngineEpoch?, val outcome: RpcOutcome)

data class HostCapabilities(
    val editorContext: Boolean = false,
    val historicalDiff: Boolean = false,
    val nativeMerge: Boolean = false,
    val commitSelection: Boolean = false,
)
data class InitializeParams(
    val protocolVersion: Int,
    val clientName: String,
    val clientVersion: String,
    val projectId: ProjectId,
    val capabilities: HostCapabilities = HostCapabilities(),
)
data class EngineCapabilities(
    val cancellation: Boolean,
    val persistentEvents: Boolean,
    val idempotentMutations: Boolean,
    val agentSessions: Boolean,
)
data class InitializeResult(
    val protocolVersion: Int,
    val engineVersion: String,
    val epoch: EngineEpoch,
    val projectId: ProjectId,
    val capabilities: EngineCapabilities,
    val maxFrameBytes: Int,
    val maxInFlight: Int,
    val maxConnectionRequests: Int,
    val defaultTimeoutMs: Long,
    val maxTimeoutMs: Long,
)
data class HealthResult(val protocolVersion: Int, val engineVersion: String, val projectId: ProjectId)

/** Cursor sequence is a decimal string, including values beyond signed Long. */
data class EventCursor(val storeId: String, val sequence: String) {
    init {
        require(opaqueIdPattern.matches(storeId))
        require(sequence.matches(Regex("0|[1-9][0-9]{0,19}")) && sequence.toULongOrNull() != null)
    }
}
data class DurableEvent(
    val cursor: EventCursor,
    val schemaVersion: Int,
    val workspaceId: String,
    val sessionId: String?,
    val kind: String,
    val payload: JsonElement,
)
data class StateSnapshot(val highWater: EventCursor, val sessions: Long, val operations: Long)
data class ReplayPage(val events: List<DurableEvent>, val highWater: EventCursor, val hasMore: Boolean)
data class MutationReceipt(val key: String, val requestDigest: String, val status: String, val outcome: JsonElement?) {
    // An unrecognized future status must never be treated as successful.
    val isTerminalSuccess: Boolean get() = status == "succeeded"
}

/** Explicit adapters keep wire names/defaults independent of reflection or IDE types.
 * Gson is supplied by the pinned platform; no extra runtime is bundled.
 */
object ProtocolJson {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).serializeNulls().create()

    fun parse(text: String): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size <= FLOW_MAX_FRAME_BYTES) { "Frame too large" }
        return try {
            val value = gson.fromJson(text, JsonElement::class.java)
            require(value != null && value.isJsonObject)
            value.asJsonObject
        } catch (_: RuntimeException) {
            // Never expose parser errors containing repository text or credentials.
            throw IllegalArgumentException("Invalid protocol JSON")
        }
    }

    fun frame(value: JsonObject): ByteArray {
        val text = gson.toJson(value)
        require(text.toByteArray(Charsets.UTF_8).size <= FLOW_MAX_FRAME_BYTES) { "Frame too large" }
        return (text + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun version(o: JsonObject) {
        require(o.string("jsonrpc") == "2.0") { "Unsupported JSON-RPC version" }
    }
    private fun id(value: JsonElement): RequestId {
        require(value.isJsonPrimitive) { "Invalid request ID" }
        val primitive = value.asJsonPrimitive
        return if (primitive.isString) {
            RequestId.Text(primitive.asString)
        } else {
            require(primitive.isNumber && primitive.asString.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Invalid request ID" }
            RequestId.Number(requireNotNull(primitive.asString.toLongOrNull()) { "Invalid request ID" })
        }
    }
    private fun id(value: RequestId): JsonElement = when (value) {
        is RequestId.Text -> JsonPrimitive(value.value)
        is RequestId.Number -> JsonPrimitive(value.value)
    }
    private fun epoch(o: JsonObject): EngineEpoch? = o.get("epoch")?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
        EngineEpoch(it.asString)
    }
    private fun envelope(epoch: EngineEpoch?): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        epoch?.let { addProperty("epoch", it.value) }
    }
    fun request(o: JsonObject): RpcRequest {
        version(o)
        return RpcRequest(
            id(o.required("id")),
            o.string("method"),
            o.get("params")?.objectValue() ?: JsonObject(),
            epoch(o),
            o.get("timeoutMs")?.takeUnless { it.isJsonNull }?.let { o.integer("timeoutMs") },
        )
    }
    fun request(r: RpcRequest): JsonObject = envelope(r.epoch).apply {
        add("id", id(r.id))
        addProperty("method", r.method)
        add("params", r.params)
        r.timeoutMs?.let { addProperty("timeoutMs", it) }
    }
    fun notification(o: JsonObject): RpcNotification {
        version(o)
        require(!o.has("id"))
        return RpcNotification(o.string("method"), o.get("params")?.objectValue() ?: JsonObject(), epoch(o))
    }
    fun notification(n: RpcNotification): JsonObject = envelope(n.epoch).apply {
        addProperty("method", n.method)
        add("params", n.params)
    }
    fun cancel(id: RequestId, epoch: EngineEpoch): RpcNotification = RpcNotification("requests.cancel", JsonObject().apply { add("id", id(id)) }, epoch)

    fun response(o: JsonObject): RpcResponse {
        version(o)
        require(o.has("id") && o.has("result") != o.has("error")) { "Invalid response envelope" }
        val outcome = if (o.has("result")) {
            RpcOutcome.Success(o.required("result"))
        } else {
            val error = o.required("error").objectValue()
            val code = error.integer("code")
            require(code in Int.MIN_VALUE..Int.MAX_VALUE)
            RpcOutcome.Failure(RpcError(code.toInt(), error.string("message")))
        }
        return RpcResponse(o.required("id").takeUnless { it.isJsonNull }?.let { id(it) }, epoch(o), outcome)
    }
    fun response(r: RpcResponse): JsonObject = envelope(r.epoch).apply {
        add("id", r.id?.let { id(it) } ?: JsonNull.INSTANCE)
        when (val outcome = r.outcome) {
            is RpcOutcome.Success -> add("result", outcome.result)
            is RpcOutcome.Failure -> add(
                "error",
                JsonObject().apply {
                    addProperty("code", outcome.error.code)
                    addProperty("message", outcome.error.message)
                },
            )
        }
    }

    fun initialize(p: InitializeParams): JsonObject = JsonObject().apply {
        addProperty("protocolVersion", p.protocolVersion)
        addProperty("clientName", p.clientName)
        addProperty("clientVersion", p.clientVersion)
        addProperty("projectId", p.projectId.value)
        add(
            "capabilities",
            JsonObject().apply {
                addProperty("editorContext", p.capabilities.editorContext)
                addProperty("historicalDiff", p.capabilities.historicalDiff)
                addProperty("nativeMerge", p.capabilities.nativeMerge)
                addProperty("commitSelection", p.capabilities.commitSelection)
            },
        )
    }
    fun initialized(o: JsonObject): InitializeResult {
        val c = o.required("capabilities").objectValue()
        val result = InitializeResult(
            o.int("protocolVersion"), o.string("engineVersion"), EngineEpoch(o.string("epoch")),
            ProjectId(o.string("projectId")),
            EngineCapabilities(
                c.bool("cancellation"),
                c.bool("persistentEvents"),
                c.bool("idempotentMutations"),
                c.bool("agentSessions"),
            ),
            o.int("maxFrameBytes"), o.int("maxInFlight"),
            o.int("maxConnectionRequests"), o.integer("defaultTimeoutMs"), o.integer("maxTimeoutMs"),
        )
        require(result.protocolVersion == FLOW_PROTOCOL_VERSION) { "Unsupported Flow protocol version" }
        require(result.maxFrameBytes in 1..FLOW_MAX_FRAME_BYTES && result.maxInFlight in 1..FLOW_MAX_IN_FLIGHT)
        require(result.maxConnectionRequests in 1..FLOW_MAX_CONNECTION_REQUESTS)
        require(result.defaultTimeoutMs in 1..result.maxTimeoutMs && result.maxTimeoutMs <= FLOW_MAX_TIMEOUT_MS)
        return result
    }
    fun health(o: JsonObject): HealthResult = HealthResult(o.int("protocolVersion"), o.string("engineVersion"), ProjectId(o.string("projectId")))
    fun cursor(o: JsonObject): EventCursor = EventCursor(o.string("storeId"), o.string("sequence"))
    fun snapshot(o: JsonObject): StateSnapshot {
        val state = o.required("state").objectValue()
        val sessions = state.integer("sessions")
        val operations = state.integer("operations")
        require(sessions >= 0 && operations >= 0)
        return StateSnapshot(cursor(o.required("highWater").objectValue()), sessions, operations)
    }
    fun replayParams(cursor: EventCursor): JsonObject = JsonObject().apply {
        add(
            "after",
            JsonObject().apply {
                addProperty("storeId", cursor.storeId)
                addProperty("sequence", cursor.sequence)
            },
        )
        addProperty("limit", 100)
    }
    fun replay(o: JsonObject): ReplayPage {
        val events = o.required("events")
        require(events.isJsonArray && events.asJsonArray.size() <= 100)
        return ReplayPage(events.asJsonArray.map { event(it.objectValue()) }, cursor(o.required("highWater").objectValue()), o.bool("hasMore"))
    }
    fun event(o: JsonObject): DurableEvent = DurableEvent(
        cursor(o.required("cursor").objectValue()),
        o.int("schemaVersion"),
        o.opaque("workspaceId"),
        o.get("sessionId")?.takeUnless { it.isJsonNull }?.let { o.opaque("sessionId") },
        o.string("kind"),
        o.required("payload"),
    )
    fun event(e: DurableEvent): JsonObject = JsonObject().apply {
        add(
            "cursor",
            JsonObject().apply {
                addProperty("storeId", e.cursor.storeId)
                addProperty("sequence", e.cursor.sequence)
            },
        )
        addProperty("schemaVersion", e.schemaVersion)
        addProperty("workspaceId", e.workspaceId)
        e.sessionId?.let { addProperty("sessionId", it) }
        addProperty("kind", e.kind)
        add("payload", e.payload)
    }
    fun receipt(o: JsonObject): MutationReceipt = MutationReceipt(
        o.opaque("key"),
        o.string("requestDigest"),
        o.string("status"),
        o.get("outcome")?.takeUnless { it.isJsonNull },
    )
}

private fun JsonObject.required(name: String): JsonElement = requireNotNull(get(name)) { "Missing protocol field" }
private fun JsonElement.objectValue(): JsonObject {
    require(isJsonObject) { "Expected protocol object" }
    return asJsonObject
}
private fun JsonObject.string(name: String): String {
    val value = required(name)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Expected protocol string" }
    return value.asString
}
private fun JsonObject.opaque(name: String): String = string(name).also { require(opaqueIdPattern.matches(it)) }
private fun JsonObject.integer(name: String): Long {
    val value = required(name)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("-?(0|[1-9][0-9]*)")))
    return requireNotNull(value.asString.toLongOrNull()) { "Invalid protocol integer" }
}
private fun JsonObject.int(name: String): Int = integer(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
private fun JsonObject.bool(name: String): Boolean {
    val value = required(name)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
    return value.asBoolean
}
