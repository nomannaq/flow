package dev.flow.ui

import com.intellij.util.xmlb.XmlSerializer
import junit.framework.TestCase

class PromptHistoryTest : TestCase() {
    fun testAcknowledgedTextIsDeduplicatedRankedAndRestoredFromWorkspaceState() {
        val history = PromptHistory()
        history.record("  Explain this project  ")
        history.record("Fix the parser\nKeep its public API.")
        history.record("Explain this project")
        val snapshot = history.snapshot()
        assertEquals(listOf("Explain this project", "Fix the parser\nKeep its public API."), snapshot.items.map { it.text })
        assertEquals(2, snapshot.items.first().uses)
        assertTrue(snapshot.items.first().lastUsed > 0)
        snapshot.items.first().text = "Mutating a browser snapshot must not modify storage"
        val restored = PromptHistory()
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(history.state), PromptHistory.Preferences::class.java))
        assertEquals(history.snapshot().items, restored.snapshot().items)
        assertTrue(restored.snapshot().enabled)
        assertTrue("A different project starts with an empty local cache", PromptHistory().snapshot().items.isEmpty())
    }

    fun testBoundedCacheRejectsOversizedAndInvalidTextWithoutTruncatingIt() {
        val history = PromptHistory()
        listOf("", " \n\t", "bad\u0000text", "x".repeat(4097), "界".repeat(3000)).forEach(history::record)
        assertTrue(history.snapshot().items.isEmpty())
        repeat(205) { history.record("Prompt $it") }
        assertEquals(200, history.snapshot().items.size)
        assertEquals("Prompt 204", history.snapshot().items.first().text)
        assertEquals("Prompt 5", history.snapshot().items.last().text)
        repeat(200) { history.record("Long prompt $it " + "x".repeat(4000)) }
        assertTrue(history.snapshot().items.sumOf { it.text.toByteArray(Charsets.UTF_8).size } <= 128 * 1024)
        val recovered = PromptHistory()
        recovered.loadState(
            PromptHistory.Preferences().apply {
                items = mutableListOf(PromptHistory.Entry("duplicate", Int.MAX_VALUE, -1), PromptHistory.Entry("duplicate"), PromptHistory.Entry("\u0000"))
            },
        )
        recovered.record("duplicate")
        assertEquals(1, recovered.snapshot().items.size)
        assertEquals(1_000_000, recovered.snapshot().items.first().uses)
    }

    fun testCacheManagementRequiresCurrentRevisionAndDoesNotChangeTranscripts() {
        val history = PromptHistory()
        history.record("First task")
        history.record("Second task")
        val revision = history.revision
        history.change("enable", revision, enabled = false)
        assertFalse(history.snapshot().enabled)
        assertEquals(2, history.snapshot().items.size)
        assertTrue(runCatching { history.change("clear", revision) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { history.change("record", history.revision, "Injected text") }.exceptionOrNull() is IllegalArgumentException)
        history.change("remove", history.revision, "First task")
        assertEquals(listOf("Second task"), history.snapshot().items.map { it.text })
        history.change("clear", history.revision)
        assertTrue(history.snapshot().items.isEmpty())
        assertFalse(history.snapshot().enabled)
    }
}
