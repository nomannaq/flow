package dev.flow.spikes

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.LightPlatformTestCase

class NativeCompatibilityTest : LightPlatformTestCase() {
    fun testHistoricalInputsSurviveLaterComparison() {
        val requests = NativeProbeFixtures.historicalDiffs(project)
        val firstBefore = requests[0].contents[0] as DocumentContent
        val firstAfter = requests[0].contents[1] as DocumentContent
        val secondAfter = requests[1].contents[1] as DocumentContent
        assertEquals("flow-probe-0\n", firstBefore.document.text)
        assertEquals("flow-probe-1\n", firstAfter.document.text)
        assertEquals("flow-probe-2\n", secondAfter.document.text)
        assertEquals("flow-probe-1\n", firstAfter.document.text)
    }

    fun testResolvedMergeReturnsExactOutput() {
        var result: MergeResult? = null
        var accepted: String? = null
        val request = NativeProbeFixtures.merge(project) { outcome, text ->
            result = outcome
            accepted = text
        } as TextMergeRequest
        WriteCommandAction.runWriteCommandAction(project) {
            request.outputContent.document.setText("setting=resolved\n")
        }
        request.applyResult(MergeResult.RESOLVED)
        assertEquals(MergeResult.RESOLVED, result)
        assertEquals("setting=resolved\n", accepted)
    }

    fun testCancelledMergeReturnsNoAcceptedContent() {
        var result: MergeResult? = null
        var accepted: String? = "not-called"
        val request = NativeProbeFixtures.merge(project) { outcome, text ->
            result = outcome
            accepted = text
        }
        request.applyResult(MergeResult.CANCEL)
        assertEquals(MergeResult.CANCEL, result)
        assertNull(accepted)
    }
}
