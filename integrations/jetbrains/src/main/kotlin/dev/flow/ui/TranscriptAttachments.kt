package dev.flow.ui

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import dev.flow.bridge.AgentImageRef
import dev.flow.bridge.EngineController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.swing.JButton
import javax.swing.JPanel

internal class TranscriptAttachments(
    project: Project?,
    controller: EngineController,
    sessionId: String,
    turnId: String,
    images: List<AgentImageRef>,
    parentScope: CoroutineScope,
    files: List<dev.flow.bridge.AgentAttachmentRef> = emptyList(),
) : JPanel(),
    Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.EDT)
    private var disposed = false
    init {
        layout = FlowUi.actions().layout
        isOpaque = false
        files.forEach { file ->
            add(
                JButton(file.name.substringAfterLast('/').take(24), com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(file.name).icon).apply {
                    putClientProperty("html.disable", true)
                    toolTipText = "Open recorded attachment · ${file.name} · ${file.size} bytes"
                    addActionListener {
                        scope.launch {
                            try {
                                val result = controller.historyRequest(
                                    "history.attachment",
                                    JsonObject().apply {
                                        addProperty("sessionId", sessionId)
                                        addProperty("turnId", turnId)
                                        add("attachment", file.json())
                                    },
                                ).asJsonObject
                                require(dev.flow.bridge.AgentProtocol.attachmentRefs(com.google.gson.JsonArray().apply { add(result["attachment"]) }).single() == file)
                                val virtual = withContext(Dispatchers.IO) { com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(result["path"].asString) }
                                check(project != null && virtual != null)
                                if (!disposed) com.intellij.openapi.fileEditor.OpenFileDescriptor(project, virtual).navigate(true)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                controller.showActionError("The recorded attachment cannot be opened. It may be missing, corrupt or unsupported by the IDE's file viewers.")
                            }
                        }
                    }
                },
            )
        }
        images.forEach { image ->
            val button = JButton(image.name.take(18)).apply {
                putClientProperty("html.disable", true)
                toolTipText = "Open recorded image · ${image.name}"
                isEnabled = false
            }
            add(button)
            scope.launch {
                try {
                    val response = controller.historyRequest(
                        "history.image",
                        JsonObject().apply {
                            addProperty("sessionId", sessionId)
                            addProperty("turnId", turnId)
                            addProperty("imageId", image.id)
                        },
                    ).asJsonObject
                    val decoded = withContext(Dispatchers.IO) {
                        decoding.withPermit {
                            val bytes = Base64.getDecoder().decode(response["data"].asString)
                            check(bytes.size == image.size)
                            PromptAttachments.thumbnail(PromptAttachments.decode(bytes))
                        }
                    }
                    if (!disposed) {
                        val factor = minOf(80.0 / decoded.width, 56.0 / decoded.height, 1.0)
                        button.icon = javax.swing.ImageIcon(decoded.getScaledInstance(maxOf(1, (decoded.width * factor).toInt()), maxOf(1, (decoded.height * factor).toInt()), java.awt.Image.SCALE_SMOOTH))
                        button.isEnabled = true
                        button.addActionListener {
                            scope.launch {
                                try {
                                    val full = withContext(Dispatchers.IO) { decoding.withPermit { PromptAttachments.decode(Base64.getDecoder().decode(response["data"].asString)) } }
                                    if (!disposed) PromptAttachments.preview(project, full, image.name)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    controller.showActionError("Recorded image preview is unavailable.")
                                }
                            }
                        }
                        revalidate()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (!disposed) {
                        button.text = "Image unavailable"
                        button.toolTipText = "Recorded image is missing, corrupt, or the engine is unavailable."
                    }
                }
            }
        }
    }
    companion object {
        private val decoding = kotlinx.coroutines.sync.Semaphore(2)
    }
    override fun dispose() {
        disposed = true
        scope.cancel()
    }
}
