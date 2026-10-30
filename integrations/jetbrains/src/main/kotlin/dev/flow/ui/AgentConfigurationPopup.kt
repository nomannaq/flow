package dev.flow.ui

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import dev.flow.bridge.AgentConfigChoice
import dev.flow.bridge.AgentConfigOption
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/** Selectors are rendered exclusively from the connected agent's advertised values. */
internal object AgentConfigurationPopup {
    fun show(anchor: JComponent, options: List<AgentConfigOption>, provider: String = "", modeOnly: Boolean = false, openSettings: (() -> Unit)? = null, select: (AgentConfigOption, JsonElement) -> Unit) {
        lateinit var popup: JBPopup
        val panel = content(
            options,
            provider,
            modeOnly,
            openSettings?.let { action ->
                {
                    popup.cancel()
                    action()
                }
            },
        ) { option, value ->
            popup.cancel()
            select(option, value)
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, com.intellij.util.ui.UIUtil.findComponentOfType(panel, JBTextField::class.java))
            .setRequestFocus(true)
            .setFocusable(true)
            .createPopup()
        popup.showUnderneathOf(anchor)
    }

    internal fun content(advertised: List<AgentConfigOption>, provider: String = "", modeOnly: Boolean = false, openSettings: (() -> Unit)? = null, select: (AgentConfigOption, JsonElement) -> Unit): JPanel {
        val options = advertised.filter { it.isMode == modeOnly }
        val controls = mutableListOf<JComponent>()
        val model = options.firstOrNull { (it.category == "model" || it.id == "model") && it.kind != "boolean" }
        if (!modeOnly && model?.options.isNullOrEmpty()) {
            controls.add(FlowUi.heading("Model catalogue unavailable", "This adapter has not supplied selectable models. The session keeps its current model."))
            controls.add(FlowUi.note("Close this session, then use Detect installed agents in Agent settings and reconnect with a current Codex or Claude Code adapter. Model, speed and effort choices come from that adapter."))
            if (openSettings != null) controls.add(FlowUi.actions(JButton("Agent settings", AllIcons.General.Settings).apply { addActionListener { openSettings() } }))
        }
        if (model != null && model.options.isNotEmpty()) {
            val search = JBTextField().apply {
                emptyText.text = "Search models"
                accessibleContext.accessibleName = "Search models"
            }
            val choices = DefaultListModel<AgentConfigChoice>()
            val list = JBList(choices).apply {
                accessibleContext.accessibleName = "Available agent models"
                selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
                cellRenderer = object : com.intellij.ui.ColoredListCellRenderer<AgentConfigChoice>() {
                    override fun customizeCellRenderer(owner: javax.swing.JList<out AgentConfigChoice>, value: AgentConfigChoice, index: Int, selected: Boolean, focus: Boolean) {
                        icon = ProviderIcons.forAgent(provider)
                        append(value.name + if (value.value == model.currentValue.asString) "  ✓" else "", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val detail = listOf(value.group, value.description).filter(String::isNotBlank).joinToString(" · ")
                        if (detail.isNotEmpty()) append("  $detail", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        toolTipText = "Model: ${value.name} · $detail"
                    }
                }
            }
            fun filter() {
                val query = search.text.trim()
                choices.clear()
                model.options.filter { listOf(it.name, it.value, it.description, it.group).any { text -> text.contains(query, ignoreCase = true) } }.forEach(choices::addElement)
                list.selectedIndex = (0 until choices.size()).firstOrNull { choices[it].value == model.currentValue.asString } ?: if (choices.isEmpty) -1 else 0
            }
            search.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(event: javax.swing.event.DocumentEvent) = filter()
            })
            filter()
            fun choose() = list.selectedValue?.let { select(model, JsonPrimitive(it.value)) }
            search.addActionListener { choose() }
            list.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(event: java.awt.event.MouseEvent) {
                    val index = list.locationToIndex(event.point)
                    if (javax.swing.SwingUtilities.isLeftMouseButton(event) && index >= 0 && list.getCellBounds(index, index).contains(event.point)) choose()
                }
            })
            list.inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "flow.chooseModel")
            list.actionMap.put(
                "flow.chooseModel",
                object : javax.swing.AbstractAction() {
                    override fun actionPerformed(event: java.awt.event.ActionEvent) {
                        choose()
                    }
                },
            )
            controls.add(search)
            controls.add(FlowUi.scroll(list, 210))
        }
        for (option in options.filter { it !== model }) {
            if (option.kind == "boolean") {
                controls.add(
                    JCheckBox(option.name, option.currentValue.asBoolean).apply {
                        putClientProperty("html.disable", true)
                        accessibleContext.accessibleName = option.name
                        toolTipText = "Agent setting: ${option.description}"
                        addActionListener { select(option, JsonPrimitive(isSelected)) }
                    },
                )
            } else {
                val selector = JComboBox(option.options.toTypedArray()).apply {
                    accessibleContext.accessibleName = option.name
                    renderer = object : javax.swing.DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                            super.getListCellRendererComponent(list, value, index, selected, focus)
                            val choice = value as? AgentConfigChoice
                            putClientProperty("html.disable", true)
                            text = choice?.name.orEmpty()
                            toolTipText = "Agent setting: ${choice?.description.orEmpty()}"
                            return this
                        }
                    }
                    selectedItem = option.options.firstOrNull { it.value == option.currentValue.asString }
                    addActionListener { (selectedItem as? AgentConfigChoice)?.let { select(option, JsonPrimitive(it.value)) } }
                }
                controls.add(FlowUi.field(option.name, selector, option.description.takeIf(String::isNotBlank)))
            }
        }
        if (modeOnly && options.isEmpty()) controls.add(FlowUi.note("This adapter does not advertise permission modes. Its native default is in use."))
        return JPanel(BorderLayout()).apply {
            preferredSize = com.intellij.util.ui.JBUI.size(380, if (model == null) 220 else 470)
            border = com.intellij.util.ui.JBUI.Borders.empty(8)
            add(FlowUi.scroll(FlowUi.column(*controls.toTypedArray())), BorderLayout.CENTER)
        }
    }
}
