package dev.flow.ui

import com.google.gson.JsonPrimitive
import com.intellij.openapi.components.service
import com.intellij.testFramework.LightPlatformTestCase
import dev.flow.bridge.AgentConfigChoice
import dev.flow.bridge.AgentConfigOption
import dev.flow.bridge.AgentView
import dev.flow.bridge.LocalAgent
import java.nio.file.Path

class AgentSelectionTest : LightPlatformTestCase() {
    fun testHeaderChoiceIsImmediatelySharedAndConfirmedSessionModelWins() {
        val preferences = service<CodexSettings>()
        val previous = preferences.state
        try {
            val selection = project.service<AgentSelection>()
            selection.select("claude")
            assertEquals("claude", selection.snapshot().profile)
            assertEquals("claude", preferences.state.adapterProfile)
            val codex = LocalAgent("codex", "codex", emptyList(), "fixture")
            val claude = LocalAgent("claude", "claude", emptyList(), "fixture")
            assertEquals(claude, selection.snapshot().resolve(listOf(codex, claude)))
            val model = AgentConfigOption("provider-model", "Model", "", "model", "select", JsonPrimitive("gpt-native"), listOf(AgentConfigChoice("gpt-native", "Native GPT")))
            val effort = AgentConfigOption("provider-effort", "Effort", "", "thought_level", "select", JsonPrimitive("high"), listOf(AgentConfigChoice("high", "High")))
            val agent = AgentView(id = "confirmed", status = "ready", agentName = "codex-acp", configOptions = listOf(model, effort))
            val target = AgentSelection.snapshot("claude", agent)
            assertEquals("codex", target.profile)
            assertEquals("gpt-native", target.model)
            assertEquals("high", target.effort)
            val command = NativeTextGenerator.command(codex, Path.of("result.txt"), target)
            assertEquals("gpt-native", command[command.indexOf("--model") + 1])
            assertTrue(command.contains("model_reasoning_effort=\"high\""))
            assertTrue(command.contains("read-only"))
            try {
                selection.snapshot().resolve(listOf(codex))
                fail("An explicit Claude selection must never fall back to Codex")
            } catch (_: IllegalStateException) { }
            try {
                AgentSelection.snapshot("codex", agent.copy(status = "configuring"))
                fail("Unconfirmed configuration must not start a utility")
            } catch (_: IllegalStateException) { }
        } finally {
            preferences.loadState(previous)
        }
    }

    fun testNativeEnhancementPreservesStructuredReviewAndRejectsInvalidResponses() {
        val result = NativeTextGenerator.enhancement("""{"prompt":"Keep the existing API and add a focused test.","assumptions":[],"sources":["src/lib.rs:12"]}""")
        assertEquals("Keep the existing API and add a focused test.", result["prompt"].asString)
        for (invalid in listOf("not JSON", """{"prompt":"run tools"}""", """{"prompt":"","assumptions":[],"sources":[]}""")) {
            try {
                NativeTextGenerator.enhancement(invalid)
                fail("Invalid enhancements must not replace the user's prompt")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
