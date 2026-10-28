package dev.flow.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

internal data class AgentPermission(val id: String, val title: String, val tool: String, val options: List<Pair<String, String>>, val allowOnce: Set<String> = emptySet())
internal data class AgentImageRef(val id: String, val name: String, val mimeType: String, val size: Int)
internal data class AgentAttachmentRef(val id: String, val name: String, val mimeType: String, val size: Int) {
    fun json() = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("mimeType", mimeType)
        addProperty("size", size)
    }
}
internal data class QueuedPrompt(val id: String, val preview: String, val status: String, val imageCount: Int, val attachmentCount: Int = 0)
internal data class AgentEvent(val kind: String, val text: String, val toolCallId: String? = null, val turnId: String? = null, val images: List<AgentImageRef> = emptyList(), val attachments: List<AgentAttachmentRef> = emptyList(), val tool: AgentToolCall? = null)
internal data class AgentToolLocation(val path: String, val line: Long? = null)
internal data class AgentToolDiff(val path: String, val before: String, val after: String)
internal data class AgentToolCall(val title: String? = null, val name: String? = null, val kind: String? = null, val status: String? = null, val input: String? = null, val output: String? = null, val locations: List<AgentToolLocation>? = null, val diffs: List<AgentToolDiff>? = null, val truncated: Boolean = false, val parentToolCallId: String? = null) {
    fun merge(next: AgentToolCall) = AgentToolCall(next.title ?: title, next.name ?: name, next.kind ?: kind, next.status ?: status, next.input ?: input, next.output ?: output, next.locations ?: locations, next.diffs ?: diffs, truncated || next.truncated, next.parentToolCallId ?: parentToolCallId)
}
internal data class AgentCommandDefinition(val name: String, val description: String, val inputHint: String)
internal data class AgentUsage(val used: String, val size: String)
internal data class AgentConfigChoice(val value: String, val name: String, val description: String = "", val group: String = "")
internal data class AgentConfigOption(val id: String, val name: String, val description: String, val category: String, val kind: String, val currentValue: com.google.gson.JsonElement, val options: List<AgentConfigChoice>) {
    val isMode: Boolean get() = kind == "mode" || category in setOf("mode", "collaboration_mode") || id in setOf("mode", "collaboration_mode")
    val currentLabel: String get() = if (kind == "boolean") {
        if (currentValue.asBoolean) "On" else "Off"
    } else {
        options.firstOrNull { it.value == currentValue.asString }?.name ?: currentValue.asString
    }
}
internal data class AgentPlanEntry(val content: String, val status: String, val priority: String)
internal data class AgentPlan(val entries: List<AgentPlanEntry>, val truncated: Boolean = false)
internal data class AgentView(
    val id: String? = null,
    val status: String = "No active session",
    val workspace: String? = null,
    val activity: List<String> = emptyList(),
    val permissions: List<AgentPermission> = emptyList(),
    val authMethods: List<String> = emptyList(),
    val compatibility: String = "",
    val recordedOperations: Long = 0,
    val events: List<AgentEvent> = emptyList(),
    val configOptions: List<AgentConfigOption> = emptyList(),
    val configurationError: String = "",
    val agentName: String = "",
    val availableCommands: List<AgentCommandDefinition> = emptyList(),
    val usage: AgentUsage? = null,
    val supportsImages: Boolean = false,
    val queue: List<QueuedPrompt> = emptyList(),
    val queuePaused: Boolean = false,
    val connectionStage: String = "",
    val startupElapsedMs: Long? = null,
    val startupTimings: Map<String, Long> = emptyMap(),
    val plan: AgentPlan? = null,
    val subagents: List<AgentEvent> = emptyList(),
)

internal object AgentProtocol {
    fun view(value: JsonObject): AgentView {
        fun optional(name: String) = value[name]?.takeUnless { it.isJsonNull }?.asString
        val id = optional("sessionId")?.also { require(Regex("[A-Za-z0-9._:-]{1,128}").matches(it)) }
        val activity = value.getAsJsonArray("activity")
        val permissions = value.getAsJsonArray("permissions")
        require(activity.size() <= 100 && permissions.size() <= 8)
        return AgentView(
            id,
            value["status"].asString,
            optional("workspace"),
            activity.map { item ->
                val event = item.asJsonObject
                val text = event["text"].asString.also { require(it.length <= 96 * 1024) }
                when (event["kind"].asString) {
                    "message.delta" -> text
                    "turn.started" -> "You: $text"
                    else -> text
                }
            },
            permissions.map { item ->
                val permission = item.asJsonObject
                val options = permission.getAsJsonArray("options")
                require(options.size() <= 16)
                AgentPermission(
                    permission["requestId"].asString,
                    permission["title"].asString,
                    permission["toolCallId"].asString,
                    options.map { it.asJsonObject.let { option -> option["id"].asString to option["name"].asString } },
                    options.map { it.asJsonObject }.filter { it["allowOnce"]?.asBoolean == true }.map { it["id"].asString }.toSet(),
                )
            },
            value.getAsJsonArray("authMethods")?.map { it.asString.also { id -> require(id.length <= 256) } }?.also { require(it.size <= 32) }.orEmpty(),
            optional("compatibility").orEmpty().also { require(it.length <= 4096) },
            value["recordedOperations"]?.asLong?.also { require(it >= 0) } ?: 0,
            activity.map { event(it.asJsonObject) },
            configOptions(value.getAsJsonArray("configOptions") ?: JsonArray()),
            optional("configurationError").orEmpty().also { require(it.length <= 4096) },
            value["agentInfo"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty().also { require(it.length <= 256) },
            value.getAsJsonArray("availableCommands")?.also { require(it.size() <= 128) }?.map { command ->
                val item = command.asJsonObject
                AgentCommandDefinition(
                    item["name"].asString.also { require(it.length in 1..128 && it.none { ch -> ch.isWhitespace() || ch.isISOControl() }) },
                    item["description"].asString.also { require(it.length <= 1024) },
                    item["inputHint"].asString.also { require(it.length <= 512) },
                )
            }.orEmpty().also { require(it.map { command -> command.name }.distinct().size == it.size) },
            value["usage"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { usage ->
                fun counter(name: String) = usage[name].asString.also {
                    require(Regex("[0-9]{1,20}").matches(it) && it.toBigInteger() <= "18446744073709551615".toBigInteger())
                }
                AgentUsage(counter("used"), counter("size"))
            },
            value["capabilities"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("promptCapabilities")?.takeIf { it.isJsonObject }?.asJsonObject?.get("image")?.asBoolean == true,
            value.getAsJsonArray("queue")?.also { require(it.size() <= 20) }?.map { item ->
                val row = item.asJsonObject
                QueuedPrompt(row["id"].asString.also { require(Regex("[A-Za-z0-9._:-]{1,128}").matches(it)) }, row["preview"].asString.also { require(it.length <= 320) }, row["status"].asString.also { require(it in setOf("pending", "dispatching", "uncertain")) }, row["imageCount"].asInt.also { require(it in 0..4) }, row["attachmentCount"]?.asInt?.also { require(it in 0..32) } ?: 0)
            }.orEmpty(),
            value["queuePaused"]?.asBoolean ?: false,
            optional("connectionStage").orEmpty().also { require(it in setOf("", "workspace", "history", "adapter", "handshake", "session", "ready")) },
            value["startupElapsedMs"]?.takeUnless { it.isJsonNull }?.asLong?.also { require(it >= 0) },
            value["startupTimingsMs"]?.asJsonObject?.entrySet()?.also { require(it.size <= 6) }?.associate { (stage, elapsed) ->
                require(stage in setOf("workspace", "history", "adapter", "handshake", "session", "ready"))
                stage to elapsed.asLong.also { require(it >= 0) }
            }.orEmpty(),
            value["plan"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { plan ->
                AgentPlan(
                    plan.getAsJsonArray("entries").also { require(it.size() <= 32) }.map { row ->
                        val entry = row.asJsonObject
                        AgentPlanEntry(entry["content"].asString.also { require(it.toByteArray().size <= 1024) }, entry["status"].asString.also { require(it in setOf("pending", "in_progress", "completed")) }, entry["priority"].asString.also { require(it in setOf("low", "medium", "high")) })
                    },
                    plan["truncated"]?.asBoolean ?: false,
                )
            },
            value["subagents"]?.takeUnless { it.isJsonNull }?.asJsonArray?.also { require(it.size() <= 16) }?.map { event(it.asJsonObject) }.orEmpty(),
        )
    }
    fun event(row: JsonObject) = AgentEvent(row["kind"].asString, row["text"].asString, row["toolCallId"]?.takeUnless { it.isJsonNull }?.asString?.also { require(it.isNotEmpty() && it.toByteArray().size <= 256) }, row["turnId"]?.takeUnless { it.isJsonNull }?.asString?.also { require(Regex("[A-Za-z0-9._:-]{1,128}").matches(it)) }, imageRefs(row["images"]), attachmentRefs(row["attachments"]), tool(row["tool"]))
    private fun tool(value: com.google.gson.JsonElement?): AgentToolCall? {
        val item = value?.takeUnless { it.isJsonNull }?.asJsonObject ?: return null
        require(item.toString().toByteArray().size <= 32 * 1024)
        fun text(row: JsonObject, key: String, limit: Int): String? = row[key]?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.toByteArray().size <= limit)
            it.asString
        }
        fun array(key: String, limit: Int) = item[key]?.takeUnless { it.isJsonNull }?.asJsonArray?.also { require(it.size() <= limit) }
        return AgentToolCall(
            text(item, "title", 512), text(item, "name", 128), text(item, "kind", 32), text(item, "status", 32), text(item, "input", 4096), text(item, "output", 4096),
            array("locations", 8)?.map { row -> row.asJsonObject.let { AgentToolLocation(requireNotNull(text(it, "path", 512)), it["line"]?.takeUnless { v -> v.isJsonNull }?.asLong?.also { line -> require(line in 0..4294967295L) }) } },
            array("diffs", 2)?.map { row -> row.asJsonObject.let { AgentToolDiff(requireNotNull(text(it, "path", 512)), requireNotNull(text(it, "before", 4096)), requireNotNull(text(it, "after", 4096))) } },
            item["truncated"]?.asBoolean ?: false,
            text(item, "parentToolCallId", 256)?.also { require(it.isNotBlank() && it.none(Char::isISOControl)) },
        )
    }
    fun attachmentRefs(value: com.google.gson.JsonElement?): List<AgentAttachmentRef> = value?.takeUnless { it.isJsonNull }?.asJsonArray?.also { require(it.size() <= 32) }?.map { value ->
        val row = value.asJsonObject
        AgentAttachmentRef(
            row["id"].asString.also { require(Regex("[a-f0-9]{64}").matches(it)) },
            row["name"].asString.also { require(it.isNotEmpty() && it.toByteArray().size <= 1024 && it.none(Char::isISOControl)) },
            row["mimeType"].asString.also { require(it.length <= 128 && it.contains('/') && it.none(Char::isISOControl)) },
            row["size"].asInt.also { require(it in 0..32 * 1024 * 1024) },
        )
    }?.also { require(it.sumOf { r -> r.size.toLong() } <= 64L * 1024 * 1024) }.orEmpty()
    fun imageRefs(value: com.google.gson.JsonElement?): List<AgentImageRef> = value?.takeUnless { it.isJsonNull }?.asJsonArray?.also { require(it.size() <= 4) }?.map { item ->
        val image = item.asJsonObject
        AgentImageRef(
            image["id"].asString.also { require(Regex("[a-f0-9]{64}").matches(it)) },
            image["name"].asString.also { require(it.length <= 256 && it.none(Char::isISOControl)) },
            image["mimeType"].asString.also { require(it in setOf("image/png", "image/jpeg")) },
            image["size"].asInt.also { require(it in 1..128 * 1024) },
        )
    }.orEmpty()
    fun configOptions(items: JsonArray): List<AgentConfigOption> {
        require(items.size() <= 65 && items.toString().toByteArray().size <= 200 * 1024)
        return items.map { item ->
            val option = item.asJsonObject
            fun text(key: String, limit: Int = 256) = option[key].asString.also { require(it.length <= limit) }
            val kind = text("kind")
            require(kind in setOf("select", "boolean", "mode"))
            val current = option["currentValue"]
            require(current.isJsonPrimitive && if (kind == "boolean") current.asJsonPrimitive.isBoolean else current.asJsonPrimitive.isString && current.asString.length <= 256)
            val options = option.getAsJsonArray("options")
            require(options.size() <= 256)
            AgentConfigOption(
                text("id"),
                text("name"),
                text("description", 2048),
                text("category", 128),
                kind,
                current,
                options.map { choice ->
                    val value = choice.asJsonObject
                    fun field(key: String, limit: Int = 256) = value[key].asString.also { require(it.length <= limit) }
                    AgentConfigChoice(field("value"), field("name"), field("description", 2048), field("group"))
                },
            )
        }.also { options -> require(options.map { it.id }.distinct().size == options.size) }
    }
    fun start(executable: String, arguments: String, commit: String, prompt: String, authMethod: String, sourceSessionId: String? = null, writersStopped: Boolean = false, model: String = "", connectOnly: Boolean = false): JsonObject {
        require(runCatching { executable.length <= 4096 && Path.of(executable).isAbsolute }.getOrDefault(false)) {
            "Enter the absolute path to your ACP adapter executable."
        }
        require(sourceSessionId != null || Regex("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})").matches(commit)) {
            "Choose a baseline with Use current commit, or paste a full Git commit hash. A filename is not a commit."
        }
        require(sourceSessionId == null || (writersStopped && Regex("[A-Za-z0-9._:-]{1,128}").matches(sourceSessionId))) { "Confirm all previous session writers have stopped." }
        require(if (connectOnly) prompt.isEmpty() else prompt.isNotBlank()) { "Enter a task in the prompt box before starting a session." }
        require(prompt.toByteArray().size <= 32768) { "The prompt must be at most 32 KiB." }
        val args = try {
            ProtocolJson.parse("{\"arguments\":$arguments}").getAsJsonArray("arguments").also { args ->
                require(
                    args != null && args.size() <= 32 && args.all {
                        it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length <= 4096 && !it.asString.contains('\u0000')
                    },
                )
            }
        } catch (_: RuntimeException) {
            // Do not display parser exceptions containing adapter arguments or secrets.
            throw IllegalArgumentException("Adapter arguments must be a JSON array of up to 32 strings; use [] for none.")
        }
        require(model.length <= 256 && !model.any { it.isISOControl() }) { "Model must be at most 256 characters without control characters." }
        require(model.isBlank() || args.size() <= 30) { "Leave room for the model override in adapter arguments." }
        require(authMethod.length <= 256) { "Authentication method must be at most 256 characters." }
        return JsonObject().apply {
            addProperty("executable", executable)
            if (connectOnly) addProperty("connectOnly", true)
            add(
                "arguments",
                JsonArray().apply {
                    args.forEach { add(it.asString) }
                    if (model.isNotBlank()) {
                        add("-c")
                        // JSON string syntax is also valid for this TOML string value.
                        add("model=${com.google.gson.JsonPrimitive(model)}")
                    }
                },
            )
            addProperty("commit", if (sourceSessionId == null) commit else "")
            if (sourceSessionId != null) {
                addProperty("sourceSessionId", sourceSessionId)
                addProperty("writersStopped", writersStopped)
            }
            addProperty("prompt", prompt)
            if (authMethod.isNotBlank()) addProperty("authMethod", authMethod)
        }
    }
    fun configure(id: String, option: AgentConfigOption, value: com.google.gson.JsonElement) = JsonObject().apply {
        require(if (option.kind == "boolean") value.isJsonPrimitive && value.asJsonPrimitive.isBoolean else value.isJsonPrimitive && value.asJsonPrimitive.isString && option.options.any { it.value == value.asString })
        addProperty("sessionId", id)
        addProperty("configId", option.id)
        add("value", value)
    }
    fun command(id: String, prompt: String = "") = JsonObject().apply {
        addProperty("sessionId", id)
        addProperty("prompt", prompt)
    }
    fun permission(id: String, request: String, option: String?) = command(id).apply {
        addProperty("requestId", request)
        addProperty("optionId", option)
    }
}
