package dev.flow.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Machine-local launch preferences only. Never persist arguments, prompts or credentials. */
@Service(Service.Level.APP)
@State(name = "FlowCodexSettings", storages = [Storage(value = "flow-codex.xml", roamingType = RoamingType.DISABLED)])
internal class CodexSettings : PersistentStateComponent<CodexSettings.Preferences> {
    class Preferences {
        var executable: String = ""
        var model: String = ""
    }
    private var preferences = Preferences()
    override fun getState() = preferences
    override fun loadState(state: Preferences) {
        preferences = state
    }
    fun save(executable: String, model: String) {
        preferences = Preferences().also {
            it.executable = executable
            it.model = model
        }
    }
}
