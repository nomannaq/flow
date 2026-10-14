package dev.flow.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

internal data class AgentPermission(val id: String, val title: String, val tool: String, val options: List<Pair<String, String>>)
internal data class AgentView(
    val id: String? = null,
    val status: String = "No active session",
    val workspace: String? = null,
    val activity: List<String> = emptyList(),
    val permissions: List<AgentPermission> = emptyList(),
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
                "${event["kind"].asString}: $text"
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
                )
            },
        )
    }
    fun start(executable: String, arguments: String, commit: String, prompt: String, authMethod: String): JsonObject {
        require(runCatching { executable.length <= 4096 && Path.of(executable).isAbsolute }.getOrDefault(false)) {
            "Enter the absolute path to your ACP adapter executable."
        }
        require(Regex("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})").matches(commit)) {
            "Choose a baseline with Use current commit, or paste a full Git commit hash. A filename is not a commit."
        }
        require(prompt.isNotBlank()) { "Enter a task in the prompt box before starting a session." }
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
        require(authMethod.length <= 256) { "Authentication method must be at most 256 characters." }
        return JsonObject().apply {
            addProperty("executable", executable)
            add("arguments", JsonArray().apply { args.forEach { add(it.asString) } })
            addProperty("commit", commit)
            addProperty("prompt", prompt)
            if (authMethod.isNotBlank()) addProperty("authMethod", authMethod)
        }
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
