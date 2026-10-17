package dev.flow.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

internal data class AgentPermission(val id: String, val title: String, val tool: String, val options: List<Pair<String, String>>, val allowOnce: Set<String> = emptySet())
internal data class AgentEvent(val kind: String, val text: String)
internal data class AgentConfigChoice(val value: String, val name: String, val description: String = "", val group: String = "")
internal data class AgentConfigOption(val id: String, val name: String, val description: String, val category: String, val kind: String, val currentValue: com.google.gson.JsonElement, val options: List<AgentConfigChoice>) {
    val currentLabel: String get() = if (kind == "boolean") {
        if (currentValue.asBoolean) "On" else "Off"
    } else {
        options.firstOrNull { it.value == currentValue.asString }?.name ?: currentValue.asString
    }
}
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
                val text = event["text"].asString.also { require(it.length <= 8192) }
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
            activity.map { AgentEvent(it.asJsonObject["kind"].asString, it.asJsonObject["text"].asString) },
            configOptions(value.getAsJsonArray("configOptions") ?: JsonArray()),
            optional("configurationError").orEmpty().also { require(it.length <= 4096) },
            value["agentInfo"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty().also { require(it.length <= 256) },
        )
    }
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
    fun configure(id: String, option: AgentConfigOption, value: com.google.gson.JsonElement) = command(id).apply {
        require(if (option.kind == "boolean") value.isJsonPrimitive && value.asJsonPrimitive.isBoolean else value.isJsonPrimitive && value.asJsonPrimitive.isString && option.options.any { it.value == value.asString })
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
