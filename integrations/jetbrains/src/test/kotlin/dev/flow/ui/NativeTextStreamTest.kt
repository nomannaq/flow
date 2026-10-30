package dev.flow.ui

import com.google.gson.JsonObject
import junit.framework.TestCase

class NativeTextStreamTest : TestCase() {
    private fun line(type: String, text: String = ""): JsonObject = JsonObject().apply {
        addProperty("type", type)
        addProperty("text", text)
    }

    fun testCodexChunkedSnapshotsIgnoreReasoningAndCommentary() {
        val stream = NativeTextStream("codex", 65536)
        fun item(type: String, text: String, phase: String? = null) = line("item.updated").apply {
            add("item", line(type, text).apply { addProperty("phase", phase) })
        }
        val message = "feat: support longer drafts\n\n- Keep Unicode: café, 日本語 and 🦀.\n- Preserve paths such as C:\\new\\file."
        val events = listOf(
            item("reasoning", "private reasoning"),
            item("command_execution", "tool output"),
            item("agent_message", "I will inspect files", "commentary"),
            item("agent_message", "feat: support"),
            item("agent_message", message),
        ).joinToString("\n")
        events.chunked(7).forEach(stream::accept)
        stream.finish()
        stream.check()
        assertEquals(message, stream.preview())
        assertNull("Codex's final result is read from --output-last-message", stream.finalResult())
    }

    fun testClaudeDeltasSnapshotsAndResultAreNotDuplicated() {
        val stream = NativeTextStream("claude", 65536)
        fun delta(type: String, text: String) = line("stream_event").apply {
            addProperty("parent_tool_use_id", null as String?)
            add("event", line("content_block_delta").apply { add("delta", line(type, text)) })
        }
        val text = "feat: generate detailed messages\n\n- Explain both the editor and CLI changes."
        val child = delta("text_delta", "child output").apply { addProperty("parent_tool_use_id", "nested-tool") }
        val assistant = line("assistant").apply {
            add(
                "message",
                JsonObject().apply {
                    add(
                        "content",
                        com.google.gson.JsonArray().apply {
                            add(line("thinking", "private reasoning"))
                            add(line("text", text))
                            add(line("tool_use", "tool input"))
                        },
                    )
                },
            )
        }
        val result = line("result").apply {
            addProperty("is_error", false)
            addProperty("subtype", "success")
            addProperty("result", text)
        }
        val events = listOf(delta("thinking_delta", "private reasoning"), child) + text.chunked(11).map { delta("text_delta", it) } + listOf(assistant, result)
        (events.joinToString("\n") + "\n").chunked(13).forEach(stream::accept)
        stream.finish()
        stream.check()
        assertEquals(text, stream.preview())
        assertEquals(text, stream.finalResult())
    }

    fun testMalformedFailedAndTruncatedStreamsNeverExposeRawDiagnostics() {
        val errors = listOf(
            "codex" to "private credentials are not JSON",
            "codex" to """{"type":"turn.failed","error":{"message":"private credentials"}}""",
            "claude" to """{"type":"result","is_error":true,"result":"private credentials"}""",
            "claude" to """{"type":"result","subtype":"error_max_turns","result":"private credentials"}""",
            "claude" to """{"type":"result","is_error":false,"stop_reason":"max_tokens","result":"incomplete"}""",
            "claude" to """{"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}}""",
        )
        for ((profile, event) in errors) {
            val stream = NativeTextStream(profile, 65536)
            stream.accept(event)
            stream.finish()
            try {
                stream.check()
                fail("Failed or malformed streams must be rejected")
            } catch (error: IllegalStateException) {
                assertFalse(error.message!!.contains("private credentials"))
            }
        }
        val incomplete = NativeTextStream("claude", 65536)
        incomplete.accept("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"partial"}}}""")
        incomplete.finish()
        incomplete.check()
        assertNull("A preview alone is not a completed Claude result", incomplete.finalResult())
    }

    fun testStreamBudgetsCountUtf8AndRejectNulAndUnboundedFrames() {
        val inputs = listOf(
            """{"type":"result","result":"${"é".repeat(600)}"}""",
            """{"type":"result","result":"bad\u0000message"}""",
            "x".repeat(1024 * 1024 + 1),
            "\n".repeat(16 * 1024 * 1024 + 1),
        )
        for (input in inputs) {
            val stream = NativeTextStream("claude", 16)
            stream.accept(input)
            stream.finish()
            try {
                stream.check()
                fail("Output must remain bounded and contain no NUL")
            } catch (_: IllegalStateException) { }
        }
    }

    fun testLongCommitBodyAndLiteralBackslashesArePreserved() {
        val message = "feat: describe the complete selection\n\n" + "- Preserve C:\\new\\file and explain the affected module.\n".repeat(600)
        assertTrue(message.toByteArray().size > 16384)
        assertEquals(message.trim(), NativeTextGenerator.message(message))
        assertEquals(message.trim(), NativeTextGenerator.message("```text\r\n${message.replace("\n", "\r\n")}```"))
        assertEquals(message.trim(), NativeTextGenerator.message("<commit>\n$message</commit>"))
        assertEquals("feat: partial", NativeTextGenerator.preview("<commit>feat: partial"))
        for (invalid in listOf("x".repeat(65537), "bad\u0000message", "<commit>incomplete", "</commit>wrong order<commit>", "```text\nunfinished")) {
            try {
                NativeTextGenerator.message(invalid)
                fail("Invalid commit output must not be inserted")
            } catch (_: IllegalArgumentException) { }
        }
        val prompt = NativeTextGenerator.prompt("exact selected patch")
        assertTrue(prompt.contains("substantive body"))
        assertTrue(prompt.contains("without padding"))
        assertTrue(prompt.endsWith("<selected_changes>\nexact selected patch\n</selected_changes>"))
    }
}
