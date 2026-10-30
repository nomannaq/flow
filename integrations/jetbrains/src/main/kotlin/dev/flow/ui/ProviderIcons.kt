package dev.flow.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Official provider marks, bundled locally; IconLoader selects the native dark-theme variant. */
internal object ProviderIcons {
    private val openai = IconLoader.getIcon("/icons/providers/openai.svg", ProviderIcons::class.java)
    private val claude = IconLoader.getIcon("/icons/providers/claude.svg", ProviderIcons::class.java)

    fun forAgent(name: String): Icon? = when {
        name.equals("automatic", ignoreCase = true) -> com.intellij.icons.AllIcons.Toolwindows.ToolWindowAskAI
        name.contains("cursor", ignoreCase = true) -> com.intellij.icons.AllIcons.Nodes.Console
        name.contains("claude", ignoreCase = true) -> claude
        name.contains("codex", ignoreCase = true) || name.equals("openai", ignoreCase = true) -> openai
        else -> null
    }
}
