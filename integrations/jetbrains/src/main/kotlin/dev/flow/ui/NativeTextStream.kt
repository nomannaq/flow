package dev.flow.ui

import com.google.gson.JsonParser
import dev.flow.bridge.optionalText

/** Bounded JSONL reader. Only assistant text is retained; reasoning, tools and diagnostics are discarded. */
internal class NativeTextStream(private val profile: String, private val limit: Int) {
    private val pending = StringBuilder()
    private val text = StringBuilder()
    private var snapshot: String? = null
    private var textBytes = 0
    private var received = 0L
    private var result: String? = null
    private var invalid = false
    private var overflow = false

    @Synchronized fun accept(chunk: String) {
        received += chunk.length
        if (received > 16 * 1024 * 1024) overflow = true
        if (overflow || invalid) return
        var start = 0
        while (start < chunk.length) {
            val newline = chunk.indexOf('\n', start)
            val end = if (newline < 0) chunk.length else newline
            if (pending.length + end - start > 1024 * 1024) {
                overflow = true
                return
            }
            pending.append(chunk, start, end)
            if (newline < 0) return
            line(pending.toString())
            pending.setLength(0)
            if (overflow || invalid) return
            start = end + 1
        }
    }

    @Synchronized fun finish() {
        if (pending.isNotEmpty() && !overflow && !invalid) line(pending.toString())
        pending.setLength(0)
    }

    @Synchronized fun preview(): String = snapshot ?: text.toString().also { snapshot = it }

    @Synchronized fun finalResult(): String? = result

    @Synchronized fun check() {
        check(!overflow) { "The agent returned too much output. Existing message retained." }
        check(!invalid) { "The agent returned an invalid or failed text stream. Check its CLI version, login and quota, then retry." }
    }

    private fun line(raw: String) {
        if (raw.isBlank()) return
        try {
            val event = JsonParser.parseString(raw).asJsonObject
            when {
                profile == "codex" -> when (event.optionalText("type")) {
                    "item.started", "item.updated", "item.completed" -> {
                        val item = event.getAsJsonObject("item") ?: return
                        if (item.optionalText("type") == "agent_message" && item.optionalText("phase") != "commentary") {
                            item.optionalText("text")?.let { replace(it) }
                        }
                    }
                    "turn.failed", "error" -> invalid = true
                }
                event["parent_tool_use_id"]?.isJsonNull == false -> Unit
                else -> when (event.optionalText("type")) {
                    "stream_event" -> {
                        val eventBody = event.getAsJsonObject("event") ?: return
                        val delta = eventBody.getAsJsonObject("delta") ?: return
                        if (delta.optionalText("stop_reason") == "max_tokens") invalid = true
                        if (delta.optionalText("type") == "text_delta") delta.optionalText("text")?.let { append(it) }
                    }
                    "assistant" -> {
                        val content = event.getAsJsonObject("message")?.getAsJsonArray("content") ?: return
                        val blocks = content.map { it.asJsonObject }.filter { it.optionalText("type") == "text" }
                        if (blocks.isNotEmpty()) replace(blocks.joinToString("") { it.optionalText("text").orEmpty() })
                    }
                    "result" -> {
                        if (event["is_error"]?.takeUnless { it.isJsonNull }?.asBoolean == true || event.optionalText("subtype")?.startsWith("error") == true || event.optionalText("stop_reason") == "max_tokens") {
                            invalid = true
                        } else {
                            val final = event.optionalText("result").orEmpty()
                            replace(final)
                            result = final
                        }
                    }
                }
            }
        } catch (_: Exception) {
            invalid = true
        }
    }

    private fun append(value: String) {
        val bytes = value.toByteArray().size
        if ('\u0000' in value) {
            invalid = true
        } else if (textBytes + bytes > limit + 1024) {
            overflow = true
        } else {
            text.append(value)
            textBytes += bytes
            snapshot = null
        }
    }

    private fun replace(value: String) {
        text.setLength(0)
        textBytes = 0
        snapshot = null
        append(value)
    }
}
