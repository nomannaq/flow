package dev.flow.ui

import com.google.gson.JsonObject
import com.intellij.testFramework.LightPlatformTestCase
import dev.flow.bridge.AgentEvent
import dev.flow.bridge.AgentPlan
import dev.flow.bridge.AgentPlanEntry
import dev.flow.bridge.AgentToolCall
import dev.flow.bridge.AgentView
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class ProgressMentionsTest : LightPlatformTestCase() {
    fun testNativePlansDelegationAndChildrenRemainSeparateFromMessages() {
        val launch = AgentEvent("operation.started", "Inspect", "delegate", "t1", tool = AgentToolCall(title = "Inspect project", name = "Agent", status = "in_progress", input = """{"subagent_type":"explorer","prompt":"Inspect the API"}"""))
        val child = AgentEvent("operation.started", "Read", "child", "t1", tool = AgentToolCall(title = "Read source", name = "Read", status = "in_progress", parentToolCallId = "delegate"))
        val result = child.copy(kind = "operation.finished", tool = AgentToolCall(status = "completed", output = "File contents"))
        val events = listOf(AgentEvent("turn.started", "Inspect"), launch, child, result, AgentEvent("message.delta", "I checked the API."))
        val plan = AgentPlan(listOf(AgentPlanEntry("Inspect API", "in_progress", "high")))
        val view = AgentView(id = "s", status = "running", events = events, plan = plan, subagents = listOf(launch))
        val state = AgentProgress.state(view)
        assertEquals("Inspect API", state.getAsJsonObject("plan").getAsJsonArray("entries")[0].asJsonObject["content"].asString)
        val agent = state.getAsJsonArray("subagents").single().asJsonObject
        assertEquals("explorer", agent["name"].asString)
        assertEquals("in_progress", agent["status"].asString)
        assertEquals("File contents", agent.getAsJsonArray("tools").single().asJsonObject["output"].asString)
        assertEquals(listOf("turn.started", "message.delta"), ConversationProjection.messages(events).map { it.kind })
        assertEquals("delegate", ConversationProjection.messages(events, includeDelegations = true).first { it.toolCallId == "child" }.tool!!.parentToolCallId)
        val closed = AgentProgress.state(view.copy(status = "closed"))
        assertEquals("interrupted", closed.getAsJsonArray("subagents").single().asJsonObject["status"].asString)
        val ack = launch.copy(tool = launch.tool!!.copy(name = "spawn_agent", status = "completed", output = """{"agent_id":"child-agent"}"""))
        val background = AgentProgress.state(view.copy(status = "ready", subagents = listOf(ack)))
        assertEquals("unknown", background.getAsJsonArray("subagents").single().asJsonObject["status"].asString)
        assertTrue(background.getAsJsonArray("subagents").single().asJsonObject["background"].asBoolean)
    }

    fun testNativeCommandsAndAgentSuggestionsAreDraftTextOnly() {
        val completion = ComposerCompletions(project)
        val commands = listOf(dev.flow.bridge.AgentCommandDefinition("review", "Review changes", "Scope"))
        val rows = runBlocking { completion.complete("/", "rev", "codex", null, commands) }
        assertEquals("/review ", rows.single().asJsonObject["insert"].asString)
        assertEquals("command", rows.single().asJsonObject["kind"].asString)
        val agents = runBlocking { completion.complete("#", "explorer", "codex", null, emptyList()) }
        assertTrue(agents.any { it.asJsonObject["insert"].asString == "Use the explorer subagent to " })
        val native = runBlocking { completion.complete("@", "agent-Explore", "claude", null, emptyList()) }
        assertTrue(native.any { it.asJsonObject["insert"].asString == "@agent-Explore " })
        assertEquals(0, runBlocking { completion.complete("$", "anything", "claude", null, emptyList()) }.size())
    }

    fun testNativeSkillsAndCustomAgentMetadataAreDiscoveredWithoutInjectingTheirBodies() {
        val workspace = Files.createTempDirectory("flow-native-inventory-")
        try {
            val skill = Files.createDirectories(workspace.resolve(".agents/skills/flow-check"))
            Files.writeString(skill.resolve("SKILL.md"), "---\nname: flow-check\ndescription: Check the API\n---\nPrivate instructions must stay with the native agent")
            val roles = Files.createDirectories(workspace.resolve(".codex/agents"))
            Files.writeString(roles.resolve("flow-auditor.toml"), "name = \"flow-auditor\"\ndescription = \"Audit API\"\ndeveloper_instructions = \"Private body\"\n")
            val completion = ComposerCompletions(project)
            val skills = runBlocking { completion.complete("$", "flow-check", "codex", workspace.toString(), emptyList()) }
            assertEquals("$" + "flow-check ", skills.single().asJsonObject["insert"].asString)
            assertFalse(skills.toString().contains("Private instructions"))
            val agents = runBlocking { completion.complete("#", "flow-auditor", "codex", workspace.toString(), emptyList()) }
            assertEquals("Use the flow-auditor subagent to ", agents.single().asJsonObject["insert"].asString)
            assertFalse(agents.toString().contains("Private body"))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    fun testFileLinksAreLocalLineAwareAndRejectTraversalAndEscapingSymlinks() {
        val root = Files.createTempDirectory("flow-file-links-").toRealPath()
        val outside = Files.createTempDirectory("flow-file-links-outside-").toRealPath()
        try {
            val file = Files.writeString(root.resolve("file with space.kt"), "one\ntwo\n")
            assertEquals(file to 2, ComposerCompletions.resolve("file with space.kt#L2-3", listOf(root)))
            assertEquals(file to 2, ComposerCompletions.resolve(file.toUri().toString() + "#L2", listOf(root)))
            assertEquals(file to 2, ComposerCompletions.resolve("file%20with%20space.kt:2", listOf(root)))
            assertEquals(file to 1, ComposerCompletions.resolve(file.toString(), listOf(root)))
            val foreign = Files.writeString(outside.resolve("private.kt"), "private")
            for (invalid in listOf("../private.kt", foreign.toString(), "https://example.com/file.kt", "file with space.kt:99999999999999999999")) {
                assertTrue("Must reject $invalid", runCatching { ComposerCompletions.resolve(invalid, listOf(root)) }.isFailure)
            }
            Files.createSymbolicLink(root.resolve("link.kt"), foreign)
            assertTrue(runCatching { ComposerCompletions.resolve("link.kt", listOf(root)) }.isFailure)
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
