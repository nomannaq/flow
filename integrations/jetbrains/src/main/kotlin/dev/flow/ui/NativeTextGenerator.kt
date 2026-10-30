package dev.flow.ui

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import dev.flow.bridge.LocalAgent
import dev.flow.bridge.LocalAgents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A separate, bounded native CLI request. No chat history, repository tools, or Git execution. */
internal object NativeTextGenerator {
    suspend fun generateCancellable(agent: LocalAgent, input: String, indicator: ProgressIndicator, settings: UtilityAgent = UtilityAgent(agent.profile), purpose: String = "commit", onPartial: ((String) -> Unit)? = null): String {
        // IDE waitFor may consume a thread interrupt. A child job forwards
        // cancellation to the polled indicator and joins process cleanup.
        val cancellation = Job(currentCoroutineContext()[Job])
        val listener = cancellation.invokeOnCompletion { error -> if (error != null) indicator.cancel() }
        return try {
            withContext(Dispatchers.IO) { generate(agent, input, indicator, settings = settings, purpose = purpose, onPartial = onPartial) }
        } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) {
            throw kotlinx.coroutines.CancellationException("Native text generation cancelled", cancelled)
        } finally {
            listener.dispose()
            cancellation.complete()
        }
    }

    fun command(agent: LocalAgent, output: Path, settings: UtilityAgent = UtilityAgent(agent.profile), purpose: String = "commit"): List<String> {
        require(settings.model?.let(EnhancementPreferences::validModel) != false)
        val selectedEffort = if (purpose == "enhance") {
            if (agent.profile == "codex") "low" else null
        } else {
            settings.effort
        }
        require(selectedEffort?.let { it in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max") } != false) { "This agent's effort value is not supported by native text generation. Choose a standard effort level." }
        val model = settings.model?.let { listOf("--model", it) }.orEmpty()
        val effort = selectedEffort?.let { if (agent.profile == "codex") listOf("--config", "model_reasoning_effort=\"$it\"") else listOf("--effort", it) }.orEmpty()
        return listOf(agent.executable) + agent.arguments + when (agent.profile) {
            "codex" -> listOf("exec", "--json", "--ephemeral", "--ignore-user-config", "--ignore-rules", "--skip-git-repo-check", "--sandbox", "read-only", "--config", "approval_policy=\"never\"", "--config", "features.shell_tool=false", "--config", "web_search=\"disabled\"", "--color", "never", "--output-last-message", output.toString(), "-")
            "claude" -> listOf("--print", "--output-format", "stream-json", "--verbose", "--include-partial-messages", "--tools", "", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}", "--setting-sources", "user", "--settings", "{\"disableAllHooks\":true}", "--no-session-persistence", "--max-turns", "1")
            else -> error("Select an installed Codex or Claude Code CLI in Flow's agent settings.")
        } + model + effort
    }

    fun generate(agent: LocalAgent, diff: String, indicator: ProgressIndicator, timeoutMillis: Long? = null, settings: UtilityAgent = UtilityAgent(agent.profile), purpose: String = "commit", onPartial: ((String) -> Unit)? = null): String {
        require(purpose in listOf("commit", "enhance"))
        val limit = if (purpose == "commit") 64 * 1024 else 16 * 1024
        val directory = Files.createTempDirectory("flow text ")
        try {
            val output = directory.resolve("message.txt")
            val line = GeneralCommandLine(command(agent, output, settings, purpose)).withWorkDirectory(directory.toFile()).withCharset(Charsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE).withEnvironment(LocalAgents.launchEnvironment())
            if (purpose == "enhance" && agent.profile == "claude") line.withEnvironment("CLAUDE_CODE_DISABLE_THINKING", "1")
            val process = try {
                KillableProcessHandler(line).apply { setShouldKillProcessSoftly(false) }
            } catch (_: Exception) {
                error("Could not start ${agent.profile}. Check its installation and terminal login, then retry.")
            }
            val stream = NativeTextStream(agent.profile, limit)
            process.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType != ProcessOutputTypes.STDOUT) return
                    stream.accept(event.text)
                }
            })
            process.startNotify()
            val input = CompletableFuture.runAsync({
                process.processInput?.bufferedWriter(Charsets.UTF_8)?.use { it.write(prompt(diff, purpose)) }
            }, AppExecutorUtil.getAppExecutorService())
            try {
                val started = System.nanoTime()
                val deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis ?: if (purpose == "commit") 300_000 else 120_000)
                var previousRaw = ""
                var previous = ""
                var bytes = 0
                var nextPreview = 0L
                while (!process.waitFor(25)) {
                    if (Thread.currentThread().isInterrupted) throw com.intellij.openapi.progress.ProcessCanceledException()
                    indicator.checkCanceled()
                    check(System.nanoTime() < deadline) { "Text generation timed out. Check your CLI login and connection, then retry." }
                    stream.check()
                    val raw = stream.preview()
                    if (raw !== previousRaw && System.nanoTime() >= nextPreview) {
                        previousRaw = raw
                        val partial = if (purpose == "enhance") enhancementPreview(raw) else preview(raw)
                        if (partial.isNotBlank() && partial != previous) {
                            previous = partial
                            bytes = partial.toByteArray().size
                            onPartial?.invoke(partial)
                            nextPreview = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)
                        }
                    }
                    val elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                    val status = if (previous.isBlank()) "Waiting for response · ${elapsed}s" else "${if (purpose == "enhance") "Enhancing prompt" else "Generating message"} · $bytes bytes received · ${elapsed}s"
                    if (indicator.text2 != status) indicator.text2 = status
                }
                indicator.checkCanceled()
                check(process.exitCode == 0 && runCatching { input.get(1, TimeUnit.SECONDS) }.isSuccess) { "${agent.profile} could not generate a message. Run it in a terminal to check your login, quota and CLI version, then retry." }
                stream.finish()
                stream.check()
                val text = if (agent.profile == "codex") {
                    check(Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) && Files.size(output) <= limit + 1024) { "Codex did not return a bounded text result. Existing message retained." }
                    Files.readString(output)
                } else {
                    checkNotNull(stream.finalResult()) { "Claude Code did not return a complete result. Check its CLI version, terminal login and quota, then retry." }
                }
                return message(text, purpose)
            } finally {
                if (!process.isProcessTerminated) {
                    process.destroyProcess()
                    if (!process.waitFor(1000)) process.killProcess()
                    process.waitFor(1000)
                }
                input.cancel(true)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    internal fun prompt(diff: String, purpose: String = "commit"): String {
        val resource = if (purpose == "enhance") "enhance-task.txt" else "commit-message.txt"
        val instructions = requireNotNull(NativeTextGenerator::class.java.getResourceAsStream("/prompts/$resource")) { "Text generation instructions are missing. Rebuild the plugin." }.bufferedReader().use { it.readText() }
        val tag = if (purpose == "enhance") "reviewed_input" else "selected_changes"
        return "$instructions\n\n<$tag>\n$diff\n</$tag>"
    }

    internal fun preview(raw: String): String {
        val text = raw.replace("\r\n", "\n").trim()
        return when {
            text.contains("<commit>") -> text.substringAfter("<commit>").substringBefore("</commit>").trim()
            text.startsWith("```") -> if ('\n' in text) text.substringAfter('\n').removeSuffix("```").trim() else ""
            "<commit>".startsWith(text) -> ""
            else -> text
        }
    }

    /** Only the prompt string is previewable; partial JSON, metadata and reasoning stay out of the editor. */
    internal fun enhancementPreview(raw: String): String {
        val text = raw.trimStart().let { if (it.startsWith("```json\n")) it.substringAfter('\n') else it }
        val opening = Regex("^\\{\\s*\"prompt\"\\s*:\\s*\"").find(text) ?: return ""
        val start = opening.range.last + 1
        var end = start
        while (end < text.length && text[end] != '"') {
            if (text[end] == '\\') {
                if (end + 1 >= text.length) break
                val count = if (text[end + 1] == 'u') 6 else 2
                if (end + count > text.length) break
                end += count
            } else {
                end++
            }
        }
        return runCatching { JsonParser.parseString("\"${text.substring(start, end)}\"").asString }
            .getOrDefault("").dropLastWhile { it.isHighSurrogate() }.takeIf { '\u0000' !in it && it.toByteArray().size <= 8192 }.orEmpty()
    }

    internal fun message(raw: String, purpose: String = "commit"): String {
        val limit = if (purpose == "commit") 64 * 1024 else 16 * 1024
        require(raw.toByteArray().size <= limit + 1024) { "The agent returned an oversized message. Existing message retained." }
        val text = if (purpose == "commit") {
            val opening = raw.indexOf("<commit>")
            val closing = raw.indexOf("</commit>")
            require(opening == -1 && closing == -1 || opening >= 0 && closing > opening) { "The agent returned an incomplete message. Existing message retained." }
            require(!raw.trim().startsWith("```") || raw.trim().endsWith("```")) { "The agent returned an incomplete message. Existing message retained." }
            preview(raw)
        } else {
            raw.trim().let { if (it.startsWith("```") && it.endsWith("```") && it.contains('\n')) it.substringAfter('\n').removeSuffix("```").trim() else it }
        }
        require(text.isNotBlank() && '\u0000' !in text && text.toByteArray().size <= limit) { "The agent returned an empty or oversized message. Existing message retained." }
        return text
    }

    internal fun enhancement(raw: String): com.google.gson.JsonObject {
        val result = runCatching { JsonParser.parseString(message(raw, "enhance")).asJsonObject }.getOrNull()
        require(result != null && result["prompt"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() && it.asString.toByteArray().size <= 8192 } == true) { "The agent returned an invalid enhancement. Original prompt retained." }
        require(result.keySet() == setOf("prompt", "assumptions", "sources") && '\u0000' !in result["prompt"].asString) { "The agent returned an invalid enhancement. Original prompt retained." }
        require(listOf("assumptions", "sources").all { key -> result[key]?.let { it.isJsonArray && it.asJsonArray.size() <= 32 && it.asJsonArray.all { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= 2048 } } == true }) { "The agent returned invalid assumptions or sources. Original prompt retained." }
        return result
    }
}
