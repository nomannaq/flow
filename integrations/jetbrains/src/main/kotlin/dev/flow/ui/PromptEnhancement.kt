package dev.flow.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.flow.bridge.EngineService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

internal class TextRevision(private val field: JTextComponent) : DocumentListener {
    var version = 0L
        private set
    init {
        field.document.addDocumentListener(this)
    }
    override fun insertUpdate(e: DocumentEvent) {
        version++
    }
    override fun removeUpdate(e: DocumentEvent) {
        version++
    }
    override fun changedUpdate(e: DocumentEvent) {
        version++
    }
}

internal class EnhancementInput(val original: String, val input: JsonObject, val unchanged: () -> Boolean, private val collect: suspend () -> Unit, private val apply: suspend (String, String, () -> Boolean) -> Unit) {
    suspend fun prepare() {
        collect()
        check(unchanged()) { "Prompt or editor changed while collecting context. Retry enhancement." }
    }
    suspend fun accept(text: String, hash: String, stillCurrent: () -> Boolean = { true }) {
        require(text.isNotBlank() && text.toByteArray().size <= 8192 && '\u0000' !in text) { "Keep the enhanced prompt within 8 KiB." }
        apply(text, hash, stillCurrent)
    }
}

internal object PromptEnhancement {
    /** A small cursor-centred excerpt; never split a UTF-16 surrogate pair. */
    internal fun cursorContext(text: String, offset: Int): JsonObject {
        val caret = offset.coerceIn(0, text.length)
        var start = (caret - 1000).coerceAtLeast(0)
        var end = (caret + 1000).coerceAtMost(text.length)
        if (start > 0 && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) start++
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        val snippet = text.substring(start, end)
        val startLine = text.take(start).count { it == '\n' } + 1
        return JsonObject().apply {
            addProperty("text", snippet)
            addProperty("startLine", startLine)
            addProperty("endLine", startLine + snippet.count { it == '\n' })
            addProperty("truncated", start > 0 || end < text.length)
        }
    }

    fun capture(project: Project, prompt: JTextComponent, revision: TextRevision, accepted: (String) -> Unit): EnhancementInput {
        val service = project.service<EngineService>()
        val original = prompt.text
        val promptVersion = revision.version
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val document = editor?.document
        val stamp = document?.modificationStamp
        val selection = editor?.selectionModel?.let { it.selectionStart to it.selectionEnd }
        val caret = editor?.caretModel?.offset
        val file = document?.let { FileDocumentManager.getInstance().getFile(it) }
        val path = file?.path?.removePrefix("${project.basePath}/")
        val editorText = document?.text
        val selected = editor?.selectionModel?.selectedText.orEmpty()
        require(original.isNotBlank() && original.toByteArray().size <= 8192 && selected.toByteArray().size <= 8192) { "Enter a prompt and select at most 8 KiB of editor text for enhancement." }
        val session = service.controller.view.value.agent.id
        fun unchanged() = !project.isDisposed && revision.version == promptVersion && prompt.text == original &&
            document?.modificationStamp == stamp && editor?.isDisposed != true && editor?.caretModel?.offset == caret &&
            editor?.selectionModel?.let { it.selectionStart to it.selectionEnd } == selection && service.controller.view.value.agent.id == session
        val input = JsonObject().apply {
            addProperty("userTask", original)
            addProperty("selectedText", selected)
            addProperty("file", path)
            addProperty("language", file?.fileType?.name)
            addProperty("caretOffset", caret)
            if (selected.isBlank() && editorText != null) add("cursorContext", cursorContext(editorText, caret ?: 0))
            if (selected.isNotBlank() && document != null && selection != null) {
                addProperty("selectionStartLine", document.getLineNumber(selection.first) + 1)
                addProperty("selectionEndLine", document.getLineNumber((selection.second - 1).coerceAtLeast(selection.first)) + 1)
            }
            add(
                "diagnostics",
                JsonArray().apply {
                    editor?.markupModel?.allHighlighters?.filter { h -> caret != null && h.startOffset <= caret && h.endOffset >= caret }
                        ?.take(8)?.forEach { h -> (h.errorStripeTooltip as? String)?.let { add(it.take(512)) } }
                },
            )
            addProperty("contextNote", "Editor context is from the current project. Worktree syntax is included only when file hashes agree. No PSI semantic facts are inferred.")
        }
        return EnhancementInput(
            original,
            input,
            ::unchanged,
            collect = {
                input.remove("workspaceContext")
                input.remove("contextStatus")
                try {
                    if (session != null && path != null && file?.extension == "rs" && editorText != null && editorText.toByteArray().size <= 65536) {
                        val hash = service.controller.productivityRequest("utilities.prepare", JsonObject().apply { addProperty("input", editorText) })["inputHash"].asString
                        val context = service.controller.productivityRequest(
                            "context.symbol",
                            JsonObject().apply {
                                addProperty("sessionId", session)
                                addProperty("path", path)
                                addProperty("contentHash", hash)
                                addProperty("line", document.getLineNumber(caret ?: 0) + 1)
                                addProperty("byteBudget", 8192)
                            },
                        )
                        if (context["freshness"].asString != "stale") input.add("workspaceContext", context)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    input.addProperty("contextStatus", "Worktree context unavailable; review uses the captured editor context only")
                }
            },
            apply = { text, hash, stillCurrent ->
                check(unchanged()) { "Prompt, selection, caret or document changed. Original text retained; generate a new proposal." }
                if (input.has("workspaceContext")) {
                    val context = input.getAsJsonObject("workspaceContext")
                    val fact = context.getAsJsonArray("results").firstOrNull()?.asJsonObject
                    if (fact != null) {
                        val fresh = service.controller.productivityRequest(
                            "context.freshness",
                            JsonObject().apply {
                                addProperty("sessionId", session)
                                add("path", fact["path"])
                                add("contentHash", fact["contentHash"])
                            },
                        )
                        check(fresh["freshness"].asString != "stale" && fresh["generation"] == context["generation"] && unchanged()) { "Workspace context changed during generation. Review a fresh enhancement." }
                    }
                }
                check(unchanged() && stillCurrent()) { "This enhancement is no longer current. Original prompt retained." }
                prompt.text = text
                accepted("reviewed-enhancement:$hash")
            },
        )
    }

    fun open(project: Project, prompt: JTextComponent, revision: TextRevision, changed: (String) -> Unit = {}, accepted: (String) -> Unit) {
        val service = project.service<EngineService>()
        val captured = try {
            capture(project, prompt, revision, accepted)
        } catch (error: IllegalArgumentException) {
            service.controller.showActionError(error.message.orEmpty())
            return
        }
        changed("preparing")
        service.scope.launch(Dispatchers.EDT) {
            try {
                captured.prepare()
                TextUtilityDialog(project, "enhance", captured.input.toString(), captured.original, changed) { text, hash -> captured.accept(text, hash) }.show()
            } catch (cancelled: CancellationException) {
                changed("")
                throw cancelled
            } catch (error: Exception) {
                changed("")
                service.controller.showActionError(error.message.orEmpty())
            }
        }
    }
}
