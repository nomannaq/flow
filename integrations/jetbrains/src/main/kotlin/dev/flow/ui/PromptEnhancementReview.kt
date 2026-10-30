package dev.flow.ui

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import dev.flow.bridge.AgentView
import dev.flow.bridge.EngineService
import dev.flow.bridge.LocalAgent
import dev.flow.bridge.LocalAgents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.UUID
import javax.swing.text.JTextComponent

/** One isolated text request and a reviewed draft; never starts or reconfigures a coding session. */
internal class PromptEnhancementReview(
    private val project: Project,
    private val scope: CoroutineScope,
    private val profile: () -> String,
    private val agent: () -> AgentView,
    private val changed: (JsonObject?) -> Unit,
    private val prepare: suspend (String) -> String = { input -> project.service<EngineService>().controller.productivityRequest("utilities.prepare", JsonObject().apply { addProperty("input", input) })["inputHash"].asString },
    private val agents: () -> List<LocalAgent> = { LocalAgents.discover(nativeCli = true) },
) : Disposable {
    private val preferences = service<EnhancementPreferences>()
    private var captured: EnhancementInput? = null
    private var target: UtilityAgent? = null
    private var hash: String? = null
    private var job: Job? = null
    private var progress: EmptyProgressIndicator? = null
    private var generation = 0L
    private var disposed = false
    var state: JsonObject? = null
        private set

    private fun publish(edit: JsonObject.() -> Unit) {
        if (disposed) return
        state = (state?.deepCopy() ?: JsonObject()).apply(edit)
        changed(state)
    }

    fun open(prompt: JTextComponent, revision: TextRevision, accepted: (String) -> Unit) {
        check(state == null) { "Review or close the current enhancement first." }
        captured = PromptEnhancement.capture(project, prompt, revision, accepted)
        publish {
            addProperty("id", UUID.randomUUID().toString())
            addProperty("generation", generation)
            addProperty("status", "preparing")
            addProperty("selectedProvider", preferences.state.provider)
            addProperty("provider", "automatic")
            addProperty("model", "")
            addProperty("output", "")
            addProperty("error", "")
            addProperty("original", captured!!.original)
            addProperty("chatProvider", AgentSelection.provider(agent().agentName) ?: profile())
            addProperty("claudeModel", preferences.state.claudeModel)
            addProperty("codexModel", preferences.state.codexModel)
        }
        generate(
            preferences.state.provider,
            when (preferences.state.provider) {
                "claude" -> preferences.state.claudeModel
                "codex" -> preferences.state.codexModel
                else -> ""
            },
        )
    }

    private fun generate(provider: String, model: String) {
        val input = requireNotNull(captured)
        check(input.unchanged()) { "Your draft or editor changed. Close this review and enhance the current draft." }
        preferences.save(provider, model.trim())
        val settings = preferences.target(profile(), agent())
        target = settings
        hash = null
        val previous = job
        progress?.cancel()
        previous?.cancel()
        val run = ++generation
        fun current() = !disposed && state != null && generation == run
        publish {
            addProperty("generation", run)
            addProperty("status", "preparing")
            addProperty("selectedProvider", provider)
            addProperty("claudeModel", preferences.state.claudeModel)
            addProperty("codexModel", preferences.state.codexModel)
            addProperty("output", "")
            addProperty("error", "")
            addProperty("provider", settings.profile)
            addProperty("model", settings.model ?: "")
            remove("assumptions")
            remove("sources")
        }
        job = scope.launch(Dispatchers.EDT) {
            try {
                previous?.join() // Native process cleanup completes before a replacement request can start.
                input.prepare()
                check(current() && preferences.target(profile(), agent()) == settings) { "The enhancement model changed. Generate again with your current selection." }
                val json = input.input.toString()
                val inputHash = prepare(json)
                val indicator = EmptyProgressIndicator()
                progress = indicator
                val native = runInterruptible(Dispatchers.IO) { settings.resolve(agents()) }
                publish {
                    addProperty("status", "generating")
                    addProperty("provider", native.profile)
                    addProperty("context", json)
                }
                val text = NativeTextGenerator.generateCancellable(native, json, indicator, settings, "enhance") { partial ->
                    scope.launch(Dispatchers.EDT) {
                        if (current() && state?.get("status")?.asString == "generating") publish { addProperty("output", partial) }
                    }
                }
                check(current() && input.unchanged() && preferences.target(profile(), agent()) == settings) { "Your draft, editor or selected model changed. Original prompt retained; generate a new enhancement." }
                val result = NativeTextGenerator.enhancement(text)
                hash = inputHash
                publish {
                    addProperty("status", "ready")
                    addProperty("output", result["prompt"].asString)
                    add("assumptions", result["assumptions"])
                    add("sources", result["sources"])
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) {
                    publish {
                        addProperty("status", "failed")
                        addProperty("error", error.message?.take(512) ?: "Enhancement unavailable. Your draft is unchanged.")
                    }
                }
            } finally {
                if (current()) progress = null
            }
        }
    }

    suspend fun action(value: JsonObject) {
        val review = requireNotNull(state) { "This enhancement is closed." }
        check(value["reviewId"]?.asString == review["id"].asString && value["generation"]?.asLong == generation) { "This enhancement was replaced. Review the current proposal." }
        when (value["operation"]?.asString) {
            "close" -> close()
            "cancel" -> {
                progress?.cancel()
                job?.cancel()
                generation++
                hash = null
                publish {
                    addProperty("generation", generation)
                    addProperty("status", "cancelled")
                }
            }
            "generate" -> generate(value["provider"]?.asString.orEmpty(), value["model"]?.asString.orEmpty())
            "accept" -> {
                check(review["status"].asString == "ready") { "Wait for a complete enhancement before accepting it." }
                val run = generation
                val input = requireNotNull(captured)
                val inputHash = requireNotNull(hash)
                fun current() = !disposed && state?.get("status")?.asString == "applying" && generation == run && preferences.target(profile(), agent()) == target
                publish { addProperty("status", "applying") }
                try {
                    input.accept(value["output"]?.asString.orEmpty(), inputHash, ::current)
                    close()
                } catch (error: Exception) {
                    if (generation == run && state != null) {
                        publish {
                            addProperty("status", "failed")
                            addProperty("error", error.message?.take(512) ?: "Your draft changed. Original text retained.")
                        }
                    }
                    if (error is CancellationException) throw error
                }
            }
            else -> error("Unknown enhancement action.")
        }
    }

    private fun close() {
        generation++
        progress?.cancel()
        job?.cancel()
        hash = null
        captured = null
        state = null
        if (!disposed) changed(null)
    }

    override fun dispose() {
        disposed = true
        close()
    }
}
