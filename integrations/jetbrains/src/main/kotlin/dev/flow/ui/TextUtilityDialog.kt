package dev.flow.ui

import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import dev.flow.bridge.EngineService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** All network input is visible before Generate. Accept only inserts editable
 * text after the caller revalidates its own document and selection versions. */
internal class TextUtilityDialog(
    private val project: Project,
    private val purpose: String,
    private val input: String,
    private val original: String,
    private val changed: (String) -> Unit = {},
    private val accept: suspend (String, String) -> Unit,
) : DialogWrapper(project, false) {
    private val service = project.service<EngineService>()
    private val output = JBTextArea(10, 70).apply {
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "Editable proposed text"
    }
    private val outputRevision = TextRevision(output)
    private val status = JBTextArea(3, 70).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "Generation status and assumptions"
    }
    private val selection = project.service<AgentSelection>()
    private fun selectedTarget() = if (purpose == "enhance") service<EnhancementPreferences>().target(selection.profile, service.controller.view.value.agent) else selection.snapshot()
    private val target = runCatching { selectedTarget() }
    private val targetDescription = FlowUi.note(target.getOrNull()?.description ?: target.exceptionOrNull()?.message.orEmpty())
    private var progress: com.intellij.openapi.progress.EmptyProgressIndicator? = null
    private val generate = JButton("Generate draft")
    private var job: Job? = null
    private var hash: String? = null
    init {
        title = if (purpose == "enhance") "Review prompt enhancement" else "Review commit message"
        isModal = false
        init()
        setOKButtonText("Accept edited draft")
        isOKActionEnabled = false
        Disposer.register(disposable) {
            progress?.cancel()
            job?.cancel()
            changed("")
        }
        generate.isEnabled = target.isSuccess
        generate.addActionListener {
            if (job?.isActive == true) return@addActionListener
            isOKActionEnabled = false
            generate.isEnabled = false
            changed("generating")
            status.text = "Generating… Cancel closes this dialog and cancels the request."
            val outputVersion = outputRevision.version
            job = service.scope.launch(Dispatchers.EDT) {
                try {
                    val settings = target.getOrThrow()
                    check(selectedTarget() == settings) { "The provider or model changed. Reopen the enhancement review." }
                    val prepared = service.controller.productivityRequest("utilities.prepare", JsonObject().apply { addProperty("input", input) })
                    val inputHash = prepared["inputHash"].asString
                    val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
                    progress = indicator
                    val agent = runInterruptible(Dispatchers.IO) { settings.resolve(dev.flow.bridge.LocalAgents.discover(nativeCli = true)) }
                    targetDescription.text = settings.copy(profile = agent.profile).description
                    status.text = "Generating with ${agent.profile} · ${settings.model ?: "native default model"}"
                    val text = NativeTextGenerator.generateCancellable(agent, input, indicator, settings = settings, purpose = purpose)
                    check(selectedTarget() == settings) { "The provider or model changed. Original text retained; reopen the review." }
                    check(outputRevision.version == outputVersion) { "Draft changed during generation. Your edits were retained; generate again to review a new proposal." }
                    if (purpose == "enhance") {
                        val proposed = NativeTextGenerator.enhancement(text)
                        output.text = proposed["prompt"].asString
                        status.text = "Assumptions: ${proposed["assumptions"]}\nSources: ${proposed["sources"]}\nReview intent and constraints before accepting."
                    } else {
                        output.text = text
                        status.text = "Editable draft from the complete staged diff. Accept inserts text; use the IDE to commit."
                    }
                    hash = inputHash
                    isOKActionEnabled = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    status.text = error.message?.take(512) ?: "Generation unavailable; original text retained."
                } finally {
                    progress = null
                    generate.isEnabled = target.isSuccess
                    if (!Disposer.isDisposed(disposable)) changed("reviewing")
                }
            }
        }
        changed("reviewing")
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(6, 6)).apply {
        preferredSize = Dimension(700, 650)
        val preview = JBTextArea("Original:\n$original\n\nExact transmitted input:\n$input").apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            accessibleContext.accessibleName = "Original and transmitted context"
        }
        add(JBScrollPane(preview).apply { preferredSize = Dimension(680, 230) }, BorderLayout.NORTH)
        add(JBScrollPane(output), BorderLayout.CENTER)
        add(
            JPanel(BorderLayout(4, 4)).apply {
                add(targetDescription, BorderLayout.NORTH)
                add(
                    JPanel(BorderLayout()).apply {
                        add(generate, BorderLayout.EAST)
                    },
                    BorderLayout.CENTER,
                )
                add(JBScrollPane(status), BorderLayout.SOUTH)
            },
            BorderLayout.SOUTH,
        )
    }
    override fun doOKAction() {
        val inputHash = hash ?: return
        if (job?.isActive == true || output.text.isBlank()) return
        val edited = output.text
        isOKActionEnabled = false
        job = service.scope.launch(Dispatchers.EDT) {
            try {
                check(selectedTarget() == target.getOrThrow()) { "The provider or model changed. Original text retained; reopen the review." }
                accept(edited, inputHash)
                close(OK_EXIT_CODE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = error.message ?: "Input changed; original text retained."
            } finally {
                isOKActionEnabled = true
            }
        }
    }
}
