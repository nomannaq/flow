package dev.flow.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import dev.flow.bridge.AgentView
import dev.flow.bridge.EngineService
import dev.flow.bridge.LocalAgent
import dev.flow.bridge.LocalAgents

/** Project choice and confirmed session settings; credentials remain with the native CLI. */
@Service(Service.Level.PROJECT)
internal class AgentSelection(private val project: Project) {
    var profile: String = service<CodexSettings>().state.adapterProfile
        private set

    fun select(profile: String) {
        require(profile in listOf("automatic", "codex", "claude", "cursor"))
        this.profile = profile
        service<CodexSettings>().selectProfile(profile)
    }

    fun snapshot(): UtilityAgent = snapshot(profile, project.getServiceIfCreated(EngineService::class.java)?.controller?.view?.value?.agent)

    companion object {
        fun provider(name: String): String? = when {
            name.contains("claude", ignoreCase = true) -> "claude"
            name.contains("codex", ignoreCase = true) -> "codex"
            name.contains("cursor", ignoreCase = true) -> "cursor"
            else -> null
        }

        internal fun snapshot(profile: String, agent: AgentView?): UtilityAgent {
            val active = agent?.id != null && agent.status !in listOf("closed", "failed", "No active session", "")
            if (!active) return UtilityAgent(profile)
            check(agent!!.status == "ready") { "Wait for the current agent turn or configuration change to finish before generating text." }
            val provider = provider(agent.agentName)
            check(provider in listOf("codex", "claude")) { "The connected adapter does not identify Codex or Claude Code. Choose a supported provider before generating text." }
            fun value(category: String): String? = agent.configOptions.firstOrNull { it.category == category || it.id == category }
                ?.takeIf { it.kind != "boolean" }?.currentValue?.asString?.takeUnless { it.isBlank() || it == "default" }
            return UtilityAgent(requireNotNull(provider), value("model"), value("thought_level"))
        }
    }
}

internal data class UtilityAgent(val profile: String, val model: String? = null, val effort: String? = null) {
    val description: String get() = "${if (profile == "automatic") "Auto-detect Codex / Claude" else profile.replaceFirstChar { it.uppercase() }} · ${model ?: "native default model"}${effort?.let { " · effort $it" }.orEmpty()} · tools disabled"

    fun resolve(agents: List<LocalAgent>): LocalAgent {
        check(profile != "cursor") { "Cursor chat uses ACP. Native text utilities currently require Codex or Claude Code; choose one in Flow settings." }
        val agent = if (profile == "automatic") agents.firstOrNull() else agents.firstOrNull { it.profile == profile }
        check(agent != null) {
            "Install ${if (profile == "claude") {
                "Claude Code"
            } else if (profile == "codex") {
                "Codex"
            } else {
                "Codex or Claude Code"
            }}, sign in from a terminal, then retry. No separate Flow API key is required."
        }
        return agent
    }

    fun generate(input: String, purpose: String, progress: ProgressIndicator, onPartial: ((String) -> Unit)? = null): String {
        val agent = resolve(LocalAgents.discover(nativeCli = true))
        progress.text = "$description · using ${agent.profile}"
        return NativeTextGenerator.generate(agent, input, progress, settings = this, purpose = purpose, onPartial = onPartial)
    }
}
