package dev.flow.ui

import com.google.gson.JsonParser
import com.intellij.testFramework.LightPlatformTestCase
import dev.flow.bridge.AgentProtocol
import dev.flow.bridge.AgentView
import kotlinx.coroutines.runBlocking
import javax.swing.JTextArea

class PromptEnhancementTest : LightPlatformTestCase() {
    fun testAutoFollowsTheChatModelWithoutInheritingEffortAndManualPreferencesStaySeparate() {
        val preferences = EnhancementPreferences()
        val config = AgentProtocol.configOptions(
            JsonParser.parseString(
                """[
          {"id":"model","name":"Model","description":"","category":"model","kind":"select","currentValue":"chat-model","options":[]},
          {"id":"effort","name":"Effort","description":"","category":"thought_level","kind":"select","currentValue":"high","options":[]}
        ]""",
            ).asJsonArray,
        )
        val agent = AgentView(id = "active", status = "running", agentName = "codex-acp", configOptions = config)
        assertEquals(UtilityAgent("codex", "chat-model", "low"), preferences.target("claude", agent))
        preferences.save("claude", "rewrite-model")
        assertEquals(UtilityAgent("claude", "rewrite-model"), preferences.target("codex", agent))
        preferences.save("codex", "other-model")
        assertEquals(UtilityAgent("codex", "other-model", "low"), preferences.target("claude", agent))
        preferences.save("automatic", "")
        assertEquals(UtilityAgent("codex", "chat-model", "low"), preferences.target("claude", agent))
        assertEquals(UtilityAgent("claude", "rewrite-model"), preferences.target("claude", AgentView()))
        assertEquals(UtilityAgent("automatic"), preferences.target("cursor", agent.copy(agentName = "cursor-acp")))
        assertEquals(UtilityAgent("codex", "other-model", "low"), preferences.target("codex", agent.copy(configOptions = emptyList())))
        assertEquals("high", config[1].currentValue.asString)
        for (model in listOf("--help", "bad\nmodel", "x".repeat(257))) assertTrue(runCatching { preferences.save("codex", model) }.isFailure)
        assertTrue(runCatching { preferences.save("cursor", "model") }.isFailure)
        val restored = EnhancementPreferences().apply { loadState(preferences.state) }
        assertEquals(preferences.target("claude", AgentView()), restored.target("claude", AgentView()))
    }

    fun testOnlyDecodedPromptTextCanStreamAndFinalMetadataMustBeValid() {
        assertEquals("Explain\nthis \"API\"", NativeTextGenerator.enhancementPreview("""{"prompt":"Explain\nthis \"API\""""))
        assertEquals("Hi ", NativeTextGenerator.enhancementPreview("""{"prompt":"Hi \uD83D"""))
        assertEquals("Hi 😀", NativeTextGenerator.enhancementPreview("""{"prompt":"Hi \uD83D\uDE00","assumptions":["private"]}"""))
        assertEquals("Hi ", NativeTextGenerator.enhancementPreview("""{"prompt":"Hi \u12"""))
        assertEquals("", NativeTextGenerator.enhancementPreview("""{"assumptions":["do not display"],"prompt":"not first"}"""))
        assertEquals("", NativeTextGenerator.enhancementPreview("""{"prompt":"bad\q"""))
        assertEquals("", NativeTextGenerator.enhancementPreview("""{"prompt":"bad\u0000"""))
        for (result in listOf(
            """{"prompt":"partial""",
            """{"prompt":"valid","assumptions":[],"sources":[],"command":"execute"}""",
            """{"prompt":"bad\u0000","assumptions":[],"sources":[]}""",
            """{"prompt":"valid","assumptions":false,"sources":[]}""",
        )) {
            assertTrue(runCatching { NativeTextGenerator.enhancement(result) }.isFailure)
        }
    }

    fun testReviewedEnhancementCannotReplaceAnEditedUndoneOrClosedDraft() = runBlocking {
        val field = JTextArea("Explain this function")
        val revision = TextRevision(field)
        var provenance = ""
        val first = PromptEnhancement.capture(project, field, revision) { provenance = it }
        first.prepare()
        field.append(" please")
        field.text = first.original
        assertTrue(runCatching { first.accept("Explain the selected function", "hash") }.isFailure)
        assertEquals(first.original, field.text)
        assertEquals("", provenance)
        val second = PromptEnhancement.capture(project, field, revision) { provenance = it }
        second.prepare()
        assertTrue(runCatching { second.accept("Explain the selected function", "hash") { false } }.isFailure)
        assertEquals(first.original, field.text)
        second.accept("Explain the selected function", "hash")
        assertEquals("Explain the selected function", field.text)
        assertEquals("reviewed-enhancement:hash", provenance)
    }
}
