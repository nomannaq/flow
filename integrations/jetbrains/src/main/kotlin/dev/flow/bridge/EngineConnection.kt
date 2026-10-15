package dev.flow.bridge

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

class EngineRpcException(val code: Int) : Exception("Engine request failed ($code)")
internal class EngineReconnect : Exception()

/** One sequential caller; bounded reader/writer queues keep pipe I/O off Swing.
 * The owner must close in finally, even when its service scope is cancelled.
 */
internal class EngineConnection(private val process: Process, scope: CoroutineScope) {
    private val incoming = Channel<JsonObject>(8)
    private val outgoing = Channel<ByteArray>(8)
    private var serial = 0L
    private var tracker: RequestTracker? = null
    private var limits: InitializeResult? = null
    val pid: Long get() = process.pid()

    private val reader = scope.launch(Dispatchers.IO) {
        try {
            val input = process.inputStream.buffered()
            while (true) incoming.send(ProtocolJson.parse(readFrame(input) ?: break))
        } catch (_: Exception) {
            // Input and stderr may contain secrets; surface only a fixed transport error.
        } finally {
            incoming.close()
        }
    }
    private val writer = scope.launch(Dispatchers.IO) {
        try {
            for (frame in outgoing) {
                process.outputStream.write(frame)
                process.outputStream.flush()
            }
        } catch (_: Exception) {
            incoming.close()
        }
    }
    private val stderr = scope.launch(Dispatchers.IO) {
        try {
            val buffer = ByteArray(4096)
            while (process.errorStream.read(buffer) >= 0) { /* Drain without retaining sensitive logs. */ }
        } catch (_: Exception) { /* Process termination closes the pipe. */ }
    }

    suspend fun initialize(projectId: ProjectId): InitializeResult {
        val reply = request(
            "engine.initialize",
            ProtocolJson.initialize(InitializeParams(1, "Flow JetBrains", "0.1.0", projectId)),
        )
        val result = ProtocolJson.initialized(reply.asJsonObject)
        require(result.projectId == projectId)
        require(result.capabilities.persistentEvents) { "Engine lacks durable state support" }
        require(result.maxConnectionRequests >= 4) { "Engine request budget cannot support synchronization" }
        limits = result
        tracker = RequestTracker(result.epoch)
        return result
    }

    suspend fun request(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = 5000): JsonElement {
        require(timeoutMs in 1..FLOW_MAX_TIMEOUT_MS)
        if (serial >= (limits?.maxConnectionRequests ?: FLOW_MAX_CONNECTION_REQUESTS) - 1) throw EngineReconnect()
        val deadlineMs = minOf(timeoutMs, limits?.maxTimeoutMs ?: FLOW_MAX_TIMEOUT_MS)
        val id = RequestId.Number(++serial)
        val epoch = tracker?.epoch
        tracker?.register(id, deadlineMs)
        val bytes = ProtocolJson.frame(ProtocolJson.request(RpcRequest(id, method, params, epoch, deadlineMs)))
        require(bytes.size - 1 <= (limits?.maxFrameBytes ?: FLOW_MAX_FRAME_BYTES))
        try {
            return withTimeout(deadlineMs) {
                outgoing.send(bytes)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val frame = incoming.receive()
                    if (!frame.has("id")) {
                        // Durable data is pulled through replay; notifications are only hints.
                        val note = ProtocolJson.notification(frame)
                        require(note.epoch == epoch)
                        continue
                    }
                    val reply = ProtocolJson.response(frame)
                    require(reply.id != null) { "Engine rejected the connection" }
                    if (reply.id != id) continue
                    if (tracker != null) {
                        if (!tracker!!.accept(reply)) continue
                    } else {
                        require(reply.id == id)
                        if (reply.outcome is RpcOutcome.Success) {
                            require(reply.epoch == ProtocolJson.initialized(reply.outcome.result.asJsonObject).epoch)
                        }
                    }
                    when (val result = reply.outcome) {
                        is RpcOutcome.Success -> return@withTimeout result.result
                        is RpcOutcome.Failure -> throw EngineRpcException(result.error.code)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (cancelled: CancellationException) {
            epoch?.let { outgoing.trySend(ProtocolJson.frame(ProtocolJson.notification(ProtocolJson.cancel(id, it)))) }
            throw cancelled
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        val graceful = if (process.isAlive && tracker != null && reader.isActive && writer.isActive) {
            runCatching { request("engine.shutdown", timeoutMs = 10000) }.isSuccess
        } else {
            false
        }
        // A shutdown acknowledgement precedes the engine's final process exit.
        // Give it a bounded exit window before falling back to termination.
        if (graceful) process.waitFor(1, TimeUnit.SECONDS)
        tracker?.disconnect()
        outgoing.close()
        // Stop the process before closing streams: a blocked writer may own a stream lock.
        // Some hosts restrict process enumeration. That must never prevent us
        // from terminating the child whose handle we already own.
        val descendants = runCatching { process.descendants().use { it.toList() } }.getOrDefault(emptyList())
        descendants.asReversed().forEach { it.destroy() }
        if (process.isAlive) process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        val exited = process.waitFor(2, TimeUnit.SECONDS)
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
        reader.cancel()
        writer.cancel()
        stderr.cancel()
        reader.join()
        writer.join()
        stderr.join()
        incoming.cancel()
        check(exited) { "Engine did not exit" }
    }

    companion object {
        internal fun readFrame(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte == -1) {
                    if (bytes.size() == 0) return null
                    break
                }
                if (byte == 10) break
                require(bytes.size() < FLOW_MAX_FRAME_BYTES) { "Frame too large" }
                bytes.write(byte)
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }
    }
}
