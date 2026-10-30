package dev.flow.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import dev.flow.bridge.EngineService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

internal object OverlayImport {
    fun choose(project: Project, accept: (JsonObject, String) -> Unit) {
        val service = project.service<EngineService>()
        service.scope.launch(Dispatchers.EDT) {
            try {
                val preview = service.controller.productivityRequest("overlay.preview")
                val choices = preview.getAsJsonArray("files").map { it.asJsonObject }.associateWith { file ->
                    JComboBox((listOf("Exclude") + file.getAsJsonArray("sources").map { it.asString }).toTypedArray())
                }
                val unsaved = FileDocumentManager.getInstance().unsavedDocuments.filter { document ->
                    FileDocumentManager.getInstance().getFile(document)?.path?.startsWith("${project.basePath}/") == true
                }
                val policy = JComboBox(arrayOf("Exclude files with unsaved buffers", "Save only selected files with unsaved buffers"))
                val dialog = object : DialogWrapper(project) {
                    init {
                        title = "Import selected local changes as baseline"
                        init()
                        setOKButtonText("Use selected overlay")
                    }
                    override fun createCenterPanel(): JComponent = JBScrollPane(
                        JPanel(VerticalLayout(6)).apply {
                            preferredSize = Dimension(620, (choices.size * 60 + 120).coerceAtMost(550))
                            add(JBLabel("index = staged bytes; disk = staged + unstaged/untracked bytes. Nothing is selected by default."))
                            add(JBLabel("${unsaved.size} unsaved buffers in this project"))
                            add(policy)
                            choices.forEach { (file, choice) ->
                                val label = JBLabel(file["path"].asString).apply { putClientProperty("html.disable", true) }
                                choice.accessibleContext.accessibleName = "Import source for ${file["path"].asString}"
                                add(label)
                                add(choice)
                            }
                        },
                    )
                }
                if (!dialog.showAndGet()) return@launch
                val selected = choices.filterValues { it.selectedItem != "Exclude" }.map { (file, choice) ->
                    JsonObject().apply {
                        addProperty("path", file["path"].asString)
                        addProperty("source", choice.selectedItem as String)
                    }
                }.toMutableList()
                val manager = FileDocumentManager.getInstance()
                for (document in manager.unsavedDocuments) {
                    val relative = manager.getFile(document)?.path?.removePrefix("${project.basePath}/") ?: continue
                    if (selected.none { it["path"].asString == relative }) continue
                    if (policy.selectedIndex == 0) {
                        selected.removeAll { it["path"].asString == relative }
                    } else {
                        manager.saveDocument(document)
                        check(!manager.isDocumentUnsaved(document)) { "Saving a selected buffer was cancelled. Overlay not selected." }
                    }
                }
                check(selected.isNotEmpty()) { "No local files selected" }
                // Recapture after explicit saves, then freeze this exact scope.
                val current = service.controller.productivityRequest("overlay.preview")
                check(current["head"] == preview["head"]) { "HEAD changed during selection. Review the overlay again." }
                if (policy.selectedIndex == 0) check(current["fingerprint"] == preview["fingerprint"]) { "Local files changed during preview. Review the overlay again." }
                accept(
                    JsonObject().apply {
                        add("fingerprint", current["fingerprint"])
                        add("files", JsonArray().apply { selected.forEach { add(it) } })
                    },
                    current["head"].asString,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                service.controller.showActionError(error.message ?: "Overlay unavailable")
            }
        }
    }
}
