package dev.flow.bridge

import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal fun JsonObject.optionalText(name: String) = get(name)?.takeUnless(JsonElement::isJsonNull)?.asString

/** Identity survives later edits and page changes; no live document enters a diff. */
internal data class HistorySelection(
    val session: String,
    val operation: String,
    val index: Int,
    val oldPath: String?,
    val newPath: String?,
    val beforeId: String?,
    val afterId: String?,
) {
    companion object {
        fun from(session: String, operation: String, file: JsonObject): HistorySelection {
            val ids = file.getAsJsonObject("contentIds")
            return HistorySelection(session, operation, file["index"].asInt, file.optionalText("oldPath"), file.optionalText("newPath"), ids.optionalText("before"), ids.optionalText("after")).also {
                require(it.index >= 0)
                listOfNotNull(it.beforeId, it.afterId).forEach { id -> require(Regex("[a-f0-9]{64}").matches(id)) }
            }
        }
    }
    fun query() = AgentProtocol.command(session).apply {
        addProperty("operationId", operation)
        addProperty("fileIndex", index)
        add(
            "contentIds",
            JsonObject().apply {
                addProperty("before", beforeId)
                addProperty("after", afterId)
            },
        )
    }
    fun diff(result: JsonObject): HistoricalDiff? {
        val ids = result.getAsJsonObject("contentIds")
        require(result["sessionId"].asString == session && result["operationId"].asString == operation && result["fileIndex"].asInt == index)
        require(ids.optionalText("before") == beforeId && ids.optionalText("after") == afterId)
        require(result.optionalText("oldPath") == oldPath && result.optionalText("newPath") == newPath)
        val before = result.optionalText("before") ?: return null
        val after = result.optionalText("after") ?: return null
        require(before.toByteArray().size <= 65536 && after.toByteArray().size <= 65536)
        return HistoricalDiff(
            "Flow historical: ${newPath ?: oldPath} — $operation",
            "Before: ${oldPath ?: "absent"} [${beforeId?.take(12) ?: "absent"}]",
            "After: ${newPath ?: "absent"} [${afterId?.take(12) ?: "absent"}]",
            before,
            after,
            "$session/$operation/$index/${beforeId ?: "absent"}/${afterId ?: "absent"}",
        )
    }
}
internal data class HistoricalDiff(val title: String, val beforeTitle: String, val afterTitle: String, val before: String, val after: String, val identity: String)
