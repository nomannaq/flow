package dev.flow.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/** Bounded project-local input recall. Only acknowledged user text enters this cache. */
@Service(Service.Level.PROJECT)
@State(name = "FlowPromptHistory", storages = [Storage(value = StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED)])
internal class PromptHistory : PersistentStateComponent<PromptHistory.Preferences> {
    data class Entry(var text: String = "", var uses: Int = 1, var lastUsed: Long = 0)
    class Preferences {
        var enabled: Boolean = true
        var items: MutableList<Entry> = mutableListOf()
    }
    data class Snapshot(val revision: Long, val enabled: Boolean, val items: List<Entry>)
    private var preferences = Preferences()
    var revision: Long = 0
        private set

    override fun getState() = preferences
    override fun loadState(state: Preferences) {
        preferences = Preferences().apply {
            enabled = state.enabled
            items = bounded(state.items)
        }
        revision++
    }
    fun snapshot() = Snapshot(revision, preferences.enabled, preferences.items.map { it.copy() })

    fun record(text: String) {
        val value = text.trim()
        if (!valid(value)) return
        val previous = preferences.items.firstOrNull { it.text == value }
        preferences.items = bounded(listOf(Entry(value, ((previous?.uses ?: 0) + 1).coerceAtMost(1_000_000), System.currentTimeMillis())) + preferences.items.filterNot { it.text == value })
        revision++
    }
    fun change(operation: String, expectedRevision: Long, text: String = "", enabled: Boolean = preferences.enabled) {
        check(expectedRevision == revision) { "Prompt history changed. Open it again before editing." }
        when (operation) {
            "remove" -> preferences.items.removeIf { it.text == text }
            "clear" -> preferences.items.clear()
            "enable" -> preferences.enabled = enabled
            else -> throw IllegalArgumentException("Unknown prompt history action.")
        }
        revision++
    }
    companion object {
        private fun valid(text: String) = text.isNotBlank() && text.length <= 4096 && text.toByteArray(Charsets.UTF_8).size <= 8192 && text.none { it.isISOControl() && it !in "\n\r\t" }
        private fun bounded(items: List<Entry>): MutableList<Entry> {
            var bytes = 0
            return items.asSequence().take(200).filter { valid(it.text) }.distinctBy { it.text }.map {
                it.copy(uses = it.uses.coerceIn(1, 1_000_000), lastUsed = it.lastUsed.coerceAtLeast(0))
            }.takeWhile {
                bytes += it.text.toByteArray(Charsets.UTF_8).size
                bytes <= 128 * 1024
            }.toMutableList()
        }
    }
}
