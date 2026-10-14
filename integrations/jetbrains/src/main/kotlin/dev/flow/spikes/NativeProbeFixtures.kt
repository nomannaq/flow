package dev.flow.spikes

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project

internal object NativeProbeFixtures {
    fun historicalDiffs(project: Project): List<SimpleDiffRequest> {
        val snapshots = listOf("flow-probe-0\n", "flow-probe-1\n", "flow-probe-2\n")
        val factory = DiffContentFactory.getInstance()
        return snapshots.zipWithNext().mapIndexed { index, (before, after) ->
            SimpleDiffRequest(
                "Flow historical operation ${index + 1}",
                factory.create(project, before, PlainTextFileType.INSTANCE),
                factory.create(project, after, PlainTextFileType.INSTANCE),
                "Checkpoint $index",
                "Checkpoint ${index + 1}",
            )
        }
    }

    fun merge(project: Project, onFinished: (MergeResult, String?) -> Unit): MergeRequest {
        val base = "setting=base\n"
        val output = EditorFactory.getInstance().createDocument(base)
        return DiffRequestFactory.getInstance().createMergeRequest(
            project,
            PlainTextFileType.INSTANCE,
            output,
            listOf("setting=developer\n", base, "setting=agent\n"),
            "Flow compatibility merge — in-memory fixture",
            listOf("Current project", "Session baseline", "Agent candidate"),
        ) { result ->
            onFinished(result, if (result == MergeResult.CANCEL) null else output.text)
        }
    }
}
