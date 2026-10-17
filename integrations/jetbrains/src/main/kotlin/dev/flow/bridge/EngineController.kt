package dev.flow.bridge

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

internal data class EngineView(
    val status: String = "Engine not started",
    val connected: Boolean = false,
    val epoch: String? = null,
    val pid: Long? = null,
    val health: String = "",
    val contextStatus: String = "Project context not initialized",
    val historyStatus: String = "",
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
    val scope: CoroutineScope,
    private val projectId: ProjectId,
    private val launchProcess: () -> Process,
    private val pollMs: Long = 1000,
    private val retryMs: Long = 250,
) {
    private sealed interface Command {
        data object HEALTH : Command
        data object RESTART : Command
        data class Rpc(val method: String, val params: JsonObject, val reply: CompletableDeferred<JsonElement>? = null) : Command
    }
    private class Restart : Exception()
    private val commands = Channel<Command>(32, onUndeliveredElement = { command ->
        if (command is Command.Rpc) command.reply?.completeExceptionally(IllegalStateException("Connection closed before command delivery; outcome unknown"))
    })
    private val mutableView = MutableStateFlow(EngineView())
    val view = mutableView.asStateFlow()
    private var job: Job? = null

    @Volatile private var liveConnection: EngineConnection? = null
    fun applicationEpochIsLive(epoch: String): Boolean = mutableView.value.epoch == epoch && liveConnection?.isAlive == true

    @Synchronized
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var failures = 0
            var launchFailure: String? = null
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
                            liveConnection = connection
                            mutableView.value = snapshot.second.copy(status = "Connected", connected = true, epoch = initialized.epoch.value, pid = connection.pid, agentSupported = initialized.capabilities.agentSessions)
                            while (isActive) {
                                when (
                                    val command = select<Command?> {
                                        commands.onReceive { it }
                                        onTimeout(pollMs) { null }
                                    }
                                ) {
                                    is Command.Rpc -> {
                                        val loadingBaseline = command.method == "sessions.baseline"
                                        mutableView.update { it.copy(actionError = "", baselineLoading = loadingBaseline) }
                                        try {
                                            if (command.reply?.isCancelled == true) continue
                                            val result = coroutineScope {
                                                val work = async { connection.request(command.method, command.params, timeoutMs = 30000) }
                                                val cancellation = command.reply?.invokeOnCompletion { if (command.reply.isCancelled) work.cancel() }
                                                try {
                                                    work.await()
                                                } finally {
                                                    cancellation?.dispose()
                                                }
                                            }
                                            command.reply?.complete(result)
                                            if (loadingBaseline) {
                                                mutableView.update { it.copy(baseline = result.asJsonObject["commit"].asString, baselineRevision = it.baselineRevision + 1) }
                                            }
                                        } catch (cancelled: CancellationException) {
                                            if (command.reply?.isCancelled != true) throw cancelled
                                        } catch (error: EngineRpcException) {
                                            command.reply?.completeExceptionally(error)
                                            showActionError(
                                                if (loadingBaseline) {
                                                    "Could not read the current commit. Open the Git repository root and run git rev-parse HEAD in its terminal. If it has no commits, create the first commit, then retry. Repository restrictions are documented in the B07 guide."
                                                } else {
                                                    "Request rejected. Check the selected commit, adapter settings and current session state."
                                                },
                                            )
                                        } catch (error: Exception) {
                                            command.reply?.completeExceptionally(error)
                                            throw error
                                        } finally {
                                            mutableView.update { it.copy(baselineLoading = false) }
                                        }
                                    }
                                    Command.RESTART -> throw Restart()
                                    Command.HEALTH -> {
                                        mutableView.value = mutableView.value.copy(health = "Checking engine…")
                                        val health = ProtocolJson.health(connection.request("engine.health").asJsonObject)
                                        require(health.projectId == projectId && health.protocolVersion == FLOW_PROTOCOL_VERSION)
                                        mutableView.value = mutableView.value.copy(health = "Engine ${health.engineVersion} is responding")
                                    }
                                    null -> Unit
                                }
                                if (initialized.capabilities.agentSessions) {
                                    val agent = AgentProtocol.view(connection.request("sessions.active").asJsonObject)
                                    mutableView.value = mutableView.value.copy(agent = agent)
                                }
                                try {
                                    val page = ProtocolJson.replay(connection.request("events.replay", ProtocolJson.replayParams(cursor)).asJsonObject)
                                    advance(cursor, page)
                                    // A rejected history write can change storage health without an event.
                                    snapshot = snapshot(connection)
                                    cursor = snapshot.first
                                    mutableView.value = mutableView.value.copy(sessions = snapshot.second.sessions, operations = snapshot.second.operations, historyStatus = snapshot.second.historyStatus)
                                } catch (error: EngineRpcException) {
                                    if (error.code != -32602) throw error
                                    snapshot = snapshot(connection)
                                    cursor = snapshot.first
                                    mutableView.value = mutableView.value.copy(sessions = snapshot.second.sessions, operations = snapshot.second.operations, historyStatus = snapshot.second.historyStatus)
                                }
                            }
                        } finally {
                            liveConnection = null
                            connection.close()
                        }
                    }
                } catch (error: EngineLaunchException) {
                    launchFailure = error.message
                    failures = 4 // Configuration/integrity failures need an explicit retry.
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
                    while (true) {
                        val stale = commands.tryReceive().getOrNull() ?: break
                        if (stale is Command.Rpc) stale.reply?.completeExceptionally(IllegalStateException("Engine disconnected; outcome unknown"))
                    }
                    mutableView.value = EngineView(status = "Disconnected; request outcome unknown")
                }
                if (failures > 3) {
                    mutableView.value = EngineView(status = "Disconnected. ${launchFailure ?: "Check the engine executable and project access, then retry."}")
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

    suspend fun historyRequest(method: String, params: JsonObject = JsonObject()): JsonElement {
        require(method in setOf("sessions.list", "sessions.archive", "history.turns", "history.location", "history.operations", "history.files", "history.diff", "candidates.prepare", "candidates.get", "candidates.files", "candidates.diff", "candidates.decide", "candidates.check", "apply.plan", "apply.get", "apply.list", "apply.preview", "apply.resolve", "apply.begin", "apply.authorize", "apply.receipt", "apply.finish", "apply.cancel", "apply.reconcile", "apply.rollback"))
        val reply = CompletableDeferred<JsonElement>()
        check(mutableView.value.connected && commands.trySend(Command.Rpc(method, params, reply)).isSuccess) { "Engine unavailable" }
        return reply.await()
    }

    suspend fun productivityRequest(method: String, params: JsonObject = JsonObject()): JsonObject {
        require(method in setOf("context.initialize", "context.preview", "context.overview", "context.search", "context.symbol", "context.freshness", "overlay.preview", "commit.selection", "utilities.prepare", "utilities.generate"))
        val reply = CompletableDeferred<JsonElement>()
        check(mutableView.value.connected && commands.trySend(Command.Rpc(method, params, reply)).isSuccess) { "Engine unavailable" }
        try {
            val value = reply.await().asJsonObject
            check(!value.has("error")) { value["error"]?.asString ?: "Request failed" }
            return value
        } catch (cancelled: CancellationException) {
            reply.cancel()
            throw cancelled
        }
    }

    fun showActionError(message: String) {
        mutableView.update { it.copy(actionError = message) }
    }

    fun showContextStatus(message: String) {
        mutableView.update { it.copy(contextStatus = message) }
    }

    private suspend fun snapshot(connection: EngineConnection): Pair<EventCursor, EngineView> {
        val value = ProtocolJson.snapshot(connection.request("state.snapshot").asJsonObject)
        return value.highWater to EngineView(sessions = value.sessions, operations = value.operations, historyStatus = value.historyStatus)
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
