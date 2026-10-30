package dev.flow.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import dev.flow.bridge.EngineController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JPanel

internal data class PromptAttachment(val name: String, val mimeType: String, val data: String, val thumbnail: BufferedImage? = null, val file: dev.flow.bridge.AgentAttachmentRef? = null) {
    fun json() = JsonObject().apply {
        addProperty("name", name)
        addProperty("mimeType", mimeType)
        addProperty("data", data)
    }
}

/** Exact sent bytes are frozen at selection; original source paths are never sent to an agent. */
internal class PromptAttachments(private val project: Project?, private val controller: EngineController, private val request: suspend (String, JsonObject) -> com.google.gson.JsonElement = controller::historyRequest) :
    JPanel(),
    Disposable {
    internal data class Frozen(val revision: Long, val items: List<PromptAttachment>) {
        fun json() = JsonArray().apply { items.filter { it.file == null }.forEach { add(it.json()) } }
        fun files() = JsonArray().apply { items.mapNotNull { it.file }.forEach { add(it.json()) } }
        val hasInlineImages get() = items.any { it.file == null }
    }
    private val scope = CoroutineScope(controller.scope.coroutineContext + kotlinx.coroutines.SupervisorJob(controller.scope.coroutineContext[Job]) + Dispatchers.EDT)
    private val pictures = mutableListOf<PromptAttachment>()
    var revision = 0L
        private set
    private var disposed = false
    private var loading = 0

    init {
        layout = FlowUi.actions().layout
        isOpaque = false
        isVisible = false
        getAccessibleContext().accessibleName = "Attached files and images"
    }

    val hasInlineImages get() = pictures.any { it.file == null }
    fun snapshot(): Frozen {
        check(loading == 0) { "Wait for the attachments to finish importing before sending." }
        return Frozen(revision, pictures.toList())
    }
    fun acknowledge(frozen: Frozen) {
        if (frozen.revision == revision) clear()
    }
    fun clear() {
        pictures.clear()
        revision++
        render()
    }

    internal fun webState() = JsonObject().apply {
        addProperty("revision", revision)
        addProperty("loading", loading > 0)
        add(
            "items",
            JsonArray().apply {
                pictures.forEach { picture ->
                    add(
                        JsonObject().apply {
                            addProperty("name", picture.name)
                            addProperty("mimeType", picture.mimeType)
                        },
                    )
                }
            },
        )
    }

    internal fun webAction(index: Int, version: Long, remove: Boolean) {
        check(version == revision && index in pictures.indices) { "Attachments changed. Try again." }
        // Reuse the exact native preview/removal handlers, including immutable content validation.
        (getComponent(index * 2 + if (remove) 1 else 0) as JButton).doClick(0)
    }

    internal suspend fun upload(name: String, data: String) {
        require(name.length in 1..255 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid attachment name." }
        val version = revision
        val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("flow-drop-") }
        try {
            val path = withContext(Dispatchers.IO) {
                val bytes = Base64.getDecoder().decode(data)
                require(bytes.size <= 8 * 1024 * 1024) { "Use Attach files for files larger than 8 MiB." }
                directory.resolve(name).also { Files.write(it, bytes) }
            }
            check(!disposed && revision == version) { "The draft changed. Attach the file again." }
            importFiles(listOf(path.toString()))?.join()
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                Files.deleteIfExists(directory.resolve(name))
                Files.deleteIfExists(directory)
            }
        }
    }

    fun choose() {
        val descriptor = FileChooserDescriptor(true, true, true, true, false, true)
            .withTitle("Attach files or folders")
            .withDescription("Any file type. Up to 32 files, 32 MiB each and 64 MiB total. Folder contents are frozen; agent tools and permissions determine how files are read.")
        FileChooser.chooseFiles(descriptor, project, null).takeIf { it.isNotEmpty() }?.let { selected -> importFiles(selected.map { it.toNioPath().toString() }) }
    }

    internal fun importFiles(paths: List<String>): Job? {
        if (paths.isEmpty()) return null
        loading++
        val version = revision
        return scope.launch {
            try {
                val response = request("attachments.import", JsonObject().apply { add("paths", JsonArray().apply { paths.forEach { add(it) } }) }).asJsonObject
                val refs = dev.flow.bridge.AgentProtocol.attachmentRefs(response["items"])
                check(!disposed && revision == version) { "The draft changed. Attach the files again." }
                val existing = pictures.mapNotNull { it.file }
                val files = (existing + refs).distinctBy { it.id }
                require(files.size <= 32 && files.sumOf { it.size.toLong() } <= 64L * 1024 * 1024) { "Attach up to 32 files and 64 MiB per message." }
                pictures.removeAll { it.file != null }
                pictures.addAll(files.map { PromptAttachment(it.name, it.mimeType, "", file = it) })
                revision++
                render()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.showActionError(error.message ?: "Files could not be attached. Choose fewer or smaller files.")
            } finally {
                loading--
            }
        }
    }

    fun pasteImage(): Boolean {
        val clipboard = com.intellij.openapi.ide.CopyPasteManager.getInstance()
        if (clipboard.areDataFlavorsAvailable(DataFlavor.javaFileListFlavor)) {
            val files = (clipboard.getContents(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<java.io.File>().orEmpty()
            if (files.isNotEmpty()) {
                importFiles(files.map { it.absolutePath })
                return true
            }
        }
        if (!clipboard.areDataFlavorsAvailable(DataFlavor.imageFlavor)) return false
        val image = clipboard.getContents(DataFlavor.imageFlavor) as? java.awt.Image ?: return false
        attachImage(image)
        return true
    }

    internal fun attachImage(image: java.awt.Image) {
        val version = revision
        loading++
        scope.launch {
            var folder: java.nio.file.Path? = null
            try {
                val width = image.getWidth(null)
                val height = image.getHeight(null)
                require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_000_000) { "Clipboard image is too large." }
                val path = withContext(Dispatchers.IO) {
                    val buffer = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    buffer.createGraphics().let { graphics ->
                        graphics.drawImage(image, 0, 0, null)
                        graphics.dispose()
                    }
                    val bytes = ByteArrayOutputStream().also { ImageIO.write(buffer, "png", it) }.toByteArray()
                    require(bytes.size <= 32 * 1024 * 1024) { "Image exceeds the 32 MiB attachment limit. Crop it before sending." }
                    val directory = Files.createTempDirectory("flow-clipboard-")
                    folder = directory
                    directory.resolve("Clipboard image.png").also { Files.write(it, bytes) }
                }
                if (!disposed && revision == version) importFiles(listOf(path.toString()))?.join()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.showActionError(error.message ?: "Clipboard image could not be attached.")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    folder?.let {
                        Files.deleteIfExists(it.resolve("Clipboard image.png"))
                        Files.deleteIfExists(it)
                    }
                }
                loading--
            }
        }
    }

    fun restore(values: JsonArray, files: JsonArray = JsonArray()) {
        val version = revision
        loading++
        scope.launch {
            try {
                require(values.size() <= 4)
                val loaded = withContext(Dispatchers.IO) {
                    values.map { value ->
                        val item = value.asJsonObject
                        val bytes = Base64.getDecoder().decode(item["data"].asString)
                        require(bytes.size <= 128 * 1024)
                        normalize(item["name"].asString, bytes)
                    }
                }
                val loadedFiles = dev.flow.bridge.AgentProtocol.attachmentRefs(files).map { PromptAttachment(it.name, it.mimeType, "", file = it) }
                if (!disposed && revision == version) {
                    pictures.clear()
                    pictures.addAll(loaded)
                    pictures.addAll(loadedFiles)
                    revision++
                    render()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                controller.showActionError("Recorded attachments could not be restored. The prompt remains a draft.")
            } finally {
                loading--
            }
        }
    }

    private fun render() {
        removeAll()
        pictures.forEachIndexed { index, picture ->
            add(
                JButton(picture.name.substringAfterLast('/').take(20), picture.thumbnail?.let(::ImageIcon) ?: picture.file?.let { com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(it.name).icon }).apply {
                    putClientProperty("html.disable", true)
                    toolTipText = "Open attachment · ${picture.name}"
                    addActionListener {
                        if (picture.file != null) {
                            openFile(picture.file)
                            return@addActionListener
                        }
                        scope.launch {
                            try {
                                val decoded = withContext(Dispatchers.IO) { decode(Base64.getDecoder().decode(picture.data)) }
                                if (!disposed) preview(project, decoded, picture.name)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                controller.showActionError("Image preview is unavailable.")
                            }
                        }
                    }
                },
            )
            add(
                FlowUi.iconButton(JButton("Remove ${picture.name}"), com.intellij.icons.AllIcons.Actions.Close, "Remove attachment").apply {
                    addActionListener {
                        pictures.removeAt(index)
                        revision++
                        render()
                    }
                },
            )
        }
        isVisible = pictures.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun openFile(file: dev.flow.bridge.AgentAttachmentRef) {
        scope.launch {
            try {
                val result = request("attachments.get", JsonObject().apply { add("attachment", file.json()) }).asJsonObject
                require(dev.flow.bridge.AgentProtocol.attachmentRefs(JsonArray().apply { add(result["attachment"]) }).single() == file)
                val virtual = withContext(Dispatchers.IO) { com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(result["path"].asString) }
                check(project != null && virtual != null) { "The recorded file cannot be opened in this project." }
                if (!disposed) com.intellij.openapi.fileEditor.OpenFileDescriptor(project, virtual).navigate(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.showActionError(error.message ?: "Attachment preview is unavailable.")
            }
        }
    }
    override fun dispose() {
        disposed = true
        scope.cancel()
    }

    companion object {
        fun decode(bytes: ByteArray): BufferedImage = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: error("Unsupported image encoding.")
            try {
                reader.setInput(stream, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_000_000) { "Image dimensions exceed the supported limit." }
                reader.read(0)
            } finally {
                reader.dispose()
            }
        }
        internal fun thumbnail(image: BufferedImage): BufferedImage {
            val scale = minOf(48.0 / image.width, 36.0 / image.height, 1.0)
            val result = BufferedImage(maxOf(1, (image.width * scale).toInt()), maxOf(1, (image.height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
            result.createGraphics().let {
                it.drawImage(image, 0, 0, result.width, result.height, null)
                it.dispose()
            }
            return result
        }
        fun normalize(name: String, bytes: ByteArray): PromptAttachment {
            val original = decode(bytes)
            val mime = when {
                bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
                bytes.size > 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
                else -> error("Attach a PNG or JPEG image.")
            }
            val cleanName = name.filterNot(Char::isISOControl).take(128).ifBlank { "Image" }
            if (bytes.size <= 128 * 1024) return PromptAttachment(cleanName, mime, Base64.getEncoder().encodeToString(bytes), thumbnail(original))
            var edge = minOf(1600, maxOf(original.width, original.height))
            repeat(10) {
                val scale = minOf(1.0, edge.toDouble() / maxOf(original.width, original.height))
                val resized = BufferedImage(maxOf(1, (original.width * scale).toInt()), maxOf(1, (original.height * scale).toInt()), BufferedImage.TYPE_INT_RGB)
                resized.createGraphics().let { graphics ->
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, resized.width, resized.height)
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    graphics.drawImage(original, 0, 0, resized.width, resized.height, null)
                    graphics.dispose()
                }
                val png = ByteArrayOutputStream().also { ImageIO.write(resized, "png", it) }.toByteArray()
                if (png.size <= 128 * 1024) return PromptAttachment("Optimized · $cleanName".take(128), "image/png", Base64.getEncoder().encodeToString(png), thumbnail(resized))
                val jpeg = ByteArrayOutputStream().also { ImageIO.write(resized, "jpeg", it) }.toByteArray()
                if (jpeg.size <= 128 * 1024) return PromptAttachment("Optimized · $cleanName".take(128), "image/jpeg", Base64.getEncoder().encodeToString(jpeg), thumbnail(decode(jpeg)))
                edge = (edge * 0.75).toInt()
            }
            error("Image is too detailed for this message. Crop it and attach again.")
        }
        fun preview(project: Project?, image: BufferedImage, name: String) {
            object : com.intellij.openapi.ui.DialogWrapper(project) {
                init {
                    title = name
                    init()
                    setOKButtonText("Close")
                }
                override fun createActions() = arrayOf(okAction)
                override fun createCenterPanel(): javax.swing.JComponent {
                    val scale = minOf(880.0 / image.width, 620.0 / image.height, 1.0)
                    val shown = image.getScaledInstance(maxOf(1, (image.width * scale).toInt()), maxOf(1, (image.height * scale).toInt()), java.awt.Image.SCALE_SMOOTH)
                    return javax.swing.JLabel(ImageIcon(shown))
                }
            }.show()
        }
    }
}
