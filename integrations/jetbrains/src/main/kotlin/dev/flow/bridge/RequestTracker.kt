package dev.flow.bridge

/** Connection-local correlation only. B06 supplies I/O, timers and Swing projection.
 * Timeouts/disconnects mean an unknown mutation outcome: query its durable key,
 * never infer failure or retry a mutation from this class's expired IDs.
 * Owned by one background coroutine; no method blocks or touches Swing.
 */
class RequestTracker(val epoch: EngineEpoch) {
    private val used = mutableSetOf<RequestId>()
    private val deadlines = mutableMapOf<RequestId, Long>()
    private var connected = true

    fun register(id: RequestId, timeoutMs: Long, nowNanos: Long = System.nanoTime()) {
        check(connected) { "Connection closed" }
        require(timeoutMs in 1..FLOW_MAX_TIMEOUT_MS)
        check(deadlines.size < FLOW_MAX_IN_FLIGHT && used.size < FLOW_MAX_CONNECTION_REQUESTS)
        check(used.add(id)) { "Request ID already used on this connection" }
        deadlines[id] = nowNanos + timeoutMs * 1_000_000
    }

    /** Discard stale-epoch, duplicate, unmatched and late responses. */
    fun accept(response: RpcResponse, nowNanos: Long = System.nanoTime()): Boolean {
        if (!connected || response.epoch != epoch) return false
        val deadline = deadlines[response.id] ?: return false
        // Leave expired IDs for expire() so their callers still learn the outcome is unknown.
        if (nowNanos - deadline >= 0) return false
        deadlines.remove(response.id)
        return true
    }

    fun expire(nowNanos: Long = System.nanoTime()): List<RequestId> {
        val expired = deadlines.filterValues { nowNanos - it >= 0 }.keys.toList()
        expired.forEach { deadlines.remove(it) }
        return expired
    }

    fun disconnect(): List<RequestId> = deadlines.keys.toList().also {
        connected = false
        deadlines.clear()
    }
}
