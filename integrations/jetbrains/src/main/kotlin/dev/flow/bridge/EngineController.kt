package dev.flow.bridge

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class EngineView(
    val status: String = "Engine not started",
    val connected: Boolean = false,
    val pid: Long? = null,
    val health: String = "",
    val sessions: Long = 0,
    val operations: Long = 0,
    val agentSupported: Boolean = false,
    val agent: AgentView = AgentView(),
    val baseline: String = "",
    val baselineRevision: Long = 0,
    val baselineLoading: Boolean = false,
    val actionError: String = "",
)

/** UI projection only. Durable history/cursors are authoritative in Rust. */
internal class EngineController(
    private val scope: CoroutineScope,
    private val projectId: ProjectId,
    private val launchProcess: () -> Process,
    private val pollMs: Long = 1000,
    private val retryMs: Long = 250,
) {
    private sealed interface Command {
        data object HEALTH : Command
        data object RESTART : Command
        data class Rpc(val method: String, val params: JsonObject) : Command
    }
    private class Restart : Exception()
    private val commands = Channel<Command>(32)
    private val mutableView = MutableStateFlow(EngineView())
    val view = mutableView.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                mutableView.value = EngineView(status = "Connecting to engine…")
                try {
                    coroutineScope {
                        // No suspension between process creation and ownership by finally.
                        val connection = EngineConnection(launchProcess(), this)
                        try {
                            val initialized = connection.initialize(projectId)
                            var snapshot = snapshot(connection)
                            var cursor = snapshot.first
                            mutableView.value = snapshot.second.copy(status = "Connected", connected = true, pid = connection.pid, agentSupported = initialized.capabilities.agentSessions)
                            while (isActive) {
                                when (val command = withTimeoutOrNull(pollMs) { commands.receive() }) {
                                    is Command.Rpc -> {
                                        val loadingBaseline = command.method == "sessions.baseline"
                                        mutableView.update { it.copy(actionError = "", baselineLoading = loadingBaseline) }
                                        try {
                                            val result = connection.request(command.method, command.params, timeoutMs = 30000)
                                            if (loadingBaseline) {
                                                mutableView.update { it.copy(baseline = result.asJsonObject["commit"].asString, baselineRevision = it.baselineRevision + 1) }
                                            }
                                        } catch (_: EngineRpcException) {
                                            showActionError(
                                                if (loadingBaseline) {
                                                    "Could not read the current commit. Open the Git repository root and run git rev-parse HEAD in its terminal. If it has no commits, create the first commit, then retry. Repository restrictions are documented in the B07 guide."
                                                } else {
                                                    "Request rejected. Check the selected commit, adapter settings and current session state."
                                                },
                                            )
                                        } finally {
                                            mutableView.update { it.copy(baselineLoading = false) }
                                        }
                                    }
                                    Command.RESTART -> throw Restart()
                                    Command.HEALTH -> {
                                        mutableView.value = mutableView.value.copy(health = "Checking engine…")
                                        val health = ProtocolJson.health(connection.request("engine.health").asJsonObject)
                                        require(health.projectId == projectId && health.protocolVersion == FLOW_PROTOCOL_VERSION)
                                        mutableView.value = mutableView.value.copy(health = "Engine ${health.engineVersion} is healthy")
                                    }
                                    null -> Unit
                                }
                                if (initialized.capabilities.agentSessions) {
                                    val agent = AgentProtocol.view(connection.request("sessions.active").asJsonObject)
                                    mutableView.value = mutableView.value.copy(agent = agent)
                                }
                                try {
                                    val page = ProtocolJson.replay(connection.request("events.replay", ProtocolJson.replayParams(cursor)).asJsonObject)
                                    val next = advance(cursor, page)
                                    if (next != cursor) {
                                        // B04 exposes a count summary. Refresh it instead of keeping a second history.
                                        snapshot = snapshot(connection)
                                        cursor = snapshot.first
                                        mutableView.value = mutableView.value.copy(sessions = snapshot.second.sessions, operations = snapshot.second.operations)
                                    }
                                } catch (error: EngineRpcException) {
                                    if (error.code != -32602) throw error
                                    snapshot = snapshot(connection)
                                    cursor = snapshot.first
                                    mutableView.value = mutableView.value.copy(sessions = snapshot.second.sessions, operations = snapshot.second.operations)
                                }
                            }
                        } finally {
                            connection.close()
                        }
                    }
                } catch (_: Restart) {
                    failures = 0
                } catch (_: EngineReconnect) {
                    failures = 0 // Planned rotation at the protocol's request-ID limit.
                } catch (cancelled: CancellationException) {
                    if (!isActive) throw cancelled
                    failures++ // A request deadline is a disconnect, never a successful operation.
                } catch (_: Exception) {
                    failures++
                } finally {
                    // Never replay a mutating command into a replacement engine/session.
                    while (commands.tryReceive().isSuccess) { /* discard stale commands */ }
                    mutableView.value = EngineView(status = "Disconnected; request outcome unknown")
                }
                if (failures > 3) {
                    mutableView.value = EngineView(status = "Disconnected. Check the engine executable and project access, then retry.")
                    break
                }
                if (failures > 0) {
                    mutableView.value = EngineView(status = "Disconnected — reconnecting ($failures/3)…")
                    delay(retryMs * (1L shl (failures - 1)))
                }
            }
        }
    }

    fun health() {
        start()
        commands.trySend(Command.HEALTH)
    }
    fun restart() {
        start()
        commands.trySend(Command.RESTART)
    }

    fun agentRequest(method: String, params: JsonObject = JsonObject()) {
        if (!mutableView.value.connected || !commands.trySend(Command.Rpc(method, params)).isSuccess) {
            showActionError("Engine unavailable or command queue full; retry explicitly.")
        }
    }

    fun showActionError(message: String) {
        mutableView.update { it.copy(actionError = message) }
    }

    private suspend fun snapshot(connection: EngineConnection): Pair<EventCursor, EngineView> {
        val value = ProtocolJson.snapshot(connection.request("state.snapshot").asJsonObject)
        return value.highWater to EngineView(sessions = value.sessions, operations = value.operations)
    }

    companion object {
        internal fun advance(cursor: EventCursor, page: ReplayPage): EventCursor {
            require(page.highWater.storeId == cursor.storeId && page.highWater.sequence.toULong() >= cursor.sequence.toULong())
            require(page.events.size <= 100)
            var last = cursor
            for (event in page.events) {
                require(event.cursor.storeId == cursor.storeId)
                require(last.sequence.toULong() < ULong.MAX_VALUE && event.cursor.sequence.toULong() == last.sequence.toULong() + 1u)
                require(event.cursor.sequence.toULong() <= page.highWater.sequence.toULong())
                last = event.cursor
            }
            require(page.hasMore == (last.sequence.toULong() < page.highWater.sequence.toULong()))
            require(!page.hasMore || page.events.isNotEmpty())
            return last
        }
    }
}
