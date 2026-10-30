package dev.flow.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.flow.bridge.AgentEvent
import dev.flow.bridge.AgentView

/** Native delegation calls and parent annotations; no new agent runner or inferred success. */
internal object AgentProgress {
    fun state(agent: AgentView): JsonObject {
        val projected = ConversationProjection.messages(agent.events, includeDelegations = true)
        val calls = if (agent.subagents.isNotEmpty()) {
            agent.subagents
        } else {
            projected.filter {
                it.tool?.name?.lowercase() in setOf("task", "agent", "spawn_agent")
            }.takeLast(16)
        }
        val gson = Gson()
        return JsonObject().apply {
            add("plan", gson.toJsonTree(agent.plan))
            add(
                "subagents",
                gson.toJsonTree(
                    calls.mapNotNull { call ->
                        val tool = call.tool ?: return@mapNotNull null
                        fun json(text: String?) = runCatching { JsonParser.parseString(text.orEmpty()).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
                        val input = json(tool.input)
                        val result = json(tool.output)
                        fun field(value: JsonObject?, vararg keys: String) = keys.firstNotNullOfOrNull { key -> value?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
                        val background = tool.name == "spawn_agent" || input?.get("run_in_background")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
                        val reported = field(result, "status")
                        val status = when {
                            tool.status == "failed" || reported == "failed" -> "failed"
                            background && tool.status == "completed" && reported != "completed" -> "unknown"
                            tool.status == "completed" || reported == "completed" -> "completed"
                            agent.status in setOf("closed", "failed", "interrupted") -> "interrupted"
                            tool.status in setOf("pending", "in_progress") -> tool.status
                            else -> "unknown"
                        }
                        mapOf(
                            "id" to "${call.turnId}:${call.toolCallId}",
                            "name" to (field(input, "subagent_type", "agent_type", "name") ?: tool.name ?: "Subagent").take(128),
                            "description" to (field(input, "description") ?: tool.title ?: call.text).take(512),
                            "prompt" to field(input, "prompt", "message").orEmpty().take(2048),
                            "status" to status,
                            "background" to background,
                            "truncated" to tool.truncated,
                            "result" to tool.output.orEmpty().take(4096),
                            "tools" to projected.filter { it.tool?.parentToolCallId == call.toolCallId && call.toolCallId != null }.takeLast(20).map { child ->
                                mapOf("id" to child.toolCallId, "title" to (child.tool?.title ?: child.text), "status" to child.tool?.status, "output" to child.tool?.output.orEmpty().take(2048))
                            },
                        )
                    },
                ),
            )
        }
    }
}
