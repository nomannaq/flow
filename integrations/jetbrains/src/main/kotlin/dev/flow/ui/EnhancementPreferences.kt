package dev.flow.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.flow.bridge.AgentView

/** Local model preferences only; native CLIs retain credentials and authentication. */
@Service(Service.Level.APP)
@State(name = "FlowEnhancementPreferences", storages = [Storage(value = "flow-enhancement.xml", roamingType = RoamingType.DISABLED)])
internal class EnhancementPreferences : PersistentStateComponent<EnhancementPreferences.Preferences> {
    class Preferences {
        var provider = "automatic"
        var claudeModel = ""
        var codexModel = ""
    }
    private var preferences = Preferences()
    override fun getState() = preferences
    override fun loadState(state: Preferences) {
        preferences = state.apply {
            if (provider !in listOf("automatic", "claude", "codex")) provider = "automatic"
            if (!validModel(claudeModel)) claudeModel = ""
            if (!validModel(codexModel)) codexModel = ""
        }
    }
    fun save(provider: String, model: String) {
        require(provider in listOf("automatic", "claude", "codex"))
        require(validModel(model)) { "Enter a model ID of at most 256 characters, without control characters or a leading dash." }
        preferences.provider = provider
        when (provider) {
            "claude" -> preferences.claudeModel = model
            "codex" -> preferences.codexModel = model
        }
    }
    fun target(profile: String, agent: AgentView): UtilityAgent {
        val active = agent.id != null && agent.status !in listOf("closed", "failed", "interrupted", "No active session", "")
        val chatProvider = if (active) AgentSelection.provider(agent.agentName) ?: profile else profile
        val provider = if (preferences.provider == "automatic") chatProvider?.takeIf { it in listOf("claude", "codex") } ?: "automatic" else preferences.provider
        val chatModel = if (preferences.provider == "automatic" && active && provider == chatProvider) {
            agent.configOptions.firstOrNull { it.category == "model" || it.id == "model" }?.currentValue?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeUnless { it.isBlank() || it == "default" }
        } else {
            null
        }
        val model = chatModel ?: when (provider) {
            "claude" -> preferences.claudeModel
            "codex" -> preferences.codexModel
            else -> ""
        }.takeIf(String::isNotBlank)
        return UtilityAgent(provider, model, if (provider == "codex") "low" else null)
    }
    companion object {
        internal fun validModel(model: String) = model.length <= 256 && !model.startsWith('-') && !model.any(Char::isISOControl)
    }
}
