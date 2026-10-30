package dev.flow.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.vcs.commit.AmendCommitHandler
import com.intellij.vcs.commit.ChangesViewCommitPanel
import com.intellij.vcs.commit.CommitWorkflowHandler
import com.intellij.vcsUtil.VcsUtil
import dev.flow.bridge.LocalAgent
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.tree.DefaultTreeModel

class NativeCommitTest : LightPlatformTestCase() {
    override fun setUp() {
        super.setUp()
        // Headless tests otherwise run Backgroundable tasks synchronously, hiding live previews.
        val previous = System.setProperty("intellij.progress.task.ignoreHeadless", "true")
        Disposer.register(
            testRootDisposable,
            com.intellij.openapi.Disposable {
                if (previous == null) System.clearProperty("intellij.progress.task.ignoreHeadless") else System.setProperty("intellij.progress.task.ignoreHeadless", previous)
            },
        )
    }

    private fun stop(service: NativeCommitMessageService) {
        Disposer.dispose(service)
        PlatformTestUtil.waitWithEventsDispatching("Cancelled generation must clean up before disposing the fixture", { !service.running }, 10)
    }

    fun testUnversionedSelectionUsesCurrentFileContents() {
        val directory = Files.createTempDirectory("flow unversioned commit ")
        val tree = object : ChangesListView(project, true) {}
        val panel = ChangesViewCommitPanel(project, tree)
        try {
            val file = Files.writeString(directory.resolve("new file.txt"), "unversioned selected content\n")
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
            val path = VcsUtil.getFilePath(virtualFile)
            val root = ChangesBrowserNode.createRoot()
            val unversioned = object : ChangesBrowserNode<Any>(ChangesBrowserNode.UNVERSIONED_FILES_TAG) {}
            unversioned.add(ChangesBrowserNode.createFilePath(path))
            root.add(unversioned)
            tree.updateTreeModel(DefaultTreeModel(root), ChangesTree.ALWAYS_RESET)
            tree.inclusionModel.setInclusion(listOf(path))
            assertEquals(listOf(path), panel.getIncludedUnversionedFiles())
            val selection = NativeCommitSelection(project, panel, panel.commitMessage.editorField.document) { true }
            try {
                assertTrue(selection.diff().contains("+unversioned selected content"))
            } finally {
                Disposer.dispose(selection)
            }
        } finally {
            Disposer.dispose(panel)
            directory.toFile().deleteRecursively()
        }
    }

    fun testClosingNativeCommitUiCancelsPendingGeneration() {
        val checked = change("chosen.txt", "old\n", "new\n")
        val tree = object : ChangesListView(project, true) {}
        val root = ChangesBrowserNode.createRoot()
        root.add(ChangesBrowserNode.createChange(project, checked))
        tree.updateTreeModel(DefaultTreeModel(root), ChangesTree.ALWAYS_RESET)
        tree.inclusionModel.setInclusion(listOf(checked))
        val panel = ChangesViewCommitPanel(project, tree)
        val service = NativeCommitMessageService(project)
        val document = panel.commitMessage.editorField.document
        panel.commitMessageUi.text = "keep my draft"
        try {
            service.generate(NativeCommitSelection(project, panel, document) { true }, panel.commitMessageUi) { _, progress, _ ->
                ApplicationManager.getApplication().invokeAndWait { Disposer.dispose(panel) }
                progress.checkCanceled()
                "must not be inserted"
            }
            PlatformTestUtil.waitWithEventsDispatching("Disposed UI must cancel generation", { !service.running }, 10)
            assertEquals("keep my draft", document.text.trim())
        } finally {
            stop(service)
            Disposer.dispose(panel)
        }
    }

    fun testBackgroundGenerationInsertsIntoNativeMessageAndRetainsConcurrentUserEdit() {
        val checked = change("chosen.txt", "old\n", "new\n")
        val tree = object : ChangesListView(project, true) {}
        val root = ChangesBrowserNode.createRoot()
        root.add(ChangesBrowserNode.createChange(project, checked))
        tree.updateTreeModel(DefaultTreeModel(root), ChangesTree.ALWAYS_RESET)
        tree.inclusionModel.setInclusion(listOf(checked))
        val panel = ChangesViewCommitPanel(project, tree)
        val service = NativeCommitMessageService(project)
        try {
            panel.commitMessageUi.text = "original draft"
            val document = panel.commitMessage.editorField.document
            service.generate(NativeCommitSelection(project, panel, document) { true }, panel.commitMessageUi) { diff, _, _ ->
                assertFalse(ApplicationManager.getApplication().isDispatchThread)
                assertTrue(diff.contains("+new"))
                "feat: generated draft"
            }
            PlatformTestUtil.waitWithEventsDispatching("Generation must finish", { !service.running }, 10)
            assertEquals("feat: generated draft", panel.commitMessageUi.text)

            service.generate(NativeCommitSelection(project, panel, document) { true }, panel.commitMessageUi) { _, _, _ ->
                ApplicationManager.getApplication().invokeAndWait {
                    assertTrue("The native commit editor stays editable during generation", panel.commitMessage.editorField.isEnabled)
                    WriteCommandAction.runWriteCommandAction(project) { document.setText("my concurrent edit") }
                }
                "do not overwrite the user"
            }
            PlatformTestUtil.waitWithEventsDispatching("Stale generation must finish", { !service.running }, 10)
            assertEquals("my concurrent edit", panel.commitMessageUi.text)
        } finally {
            stop(service)
            Disposer.dispose(panel)
        }
    }

    private fun change(name: String, before: String, after: String): Change {
        val path = VcsUtil.getFilePath("${project.basePath}/$name", false)
        return Change(SimpleContentRevision(before, path, "HEAD"), SimpleContentRevision(after, path, "WORKING"))
    }

    private fun commitPanel(tree: ChangesListView = object : ChangesListView(project, true) {}): ChangesViewCommitPanel {
        val checked = change("chosen.txt", "old\n", "new\n")
        val root = ChangesBrowserNode.createRoot()
        root.add(ChangesBrowserNode.createChange(project, checked))
        tree.updateTreeModel(DefaultTreeModel(root), ChangesTree.ALWAYS_RESET)
        tree.inclusionModel.setInclusion(listOf(checked))
        return ChangesViewCommitPanel(project, tree)
    }

    fun testSelectionDoesNotLoadDocumentsAndDetectsEditsAfterOpeningASelectedFile() {
        val directory = Files.createTempDirectory("flow cold selection ")
        val tree = object : ChangesListView(project, true) {}
        val panel = ChangesViewCommitPanel(project, tree)
        try {
            val disk = Files.writeString(directory.resolve("selected.txt"), "original content\n")
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(disk)!!
            val path = VcsUtil.getFilePath(file)
            val checked = Change(SimpleContentRevision("old\n", path, "HEAD"), com.intellij.openapi.vcs.changes.CurrentContentRevision.create(path))
            val root = ChangesBrowserNode.createRoot()
            root.add(ChangesBrowserNode.createChange(project, checked))
            tree.updateTreeModel(DefaultTreeModel(root), ChangesTree.ALWAYS_RESET)
            tree.inclusionModel.setInclusion(listOf(checked))
            val manager = FileDocumentManager.getInstance()
            assertNull(manager.getCachedDocument(file))
            val selection = NativeCommitSelection(project, panel, panel.commitMessage.editorField.document) { true }
            try {
                assertNull("Clicking Generate must not load selected files on the EDT", manager.getCachedDocument(file))
                val unrelated = EditorFactory.getInstance().createDocument("unrelated content")
                WriteCommandAction.runWriteCommandAction(project) { unrelated.setText("unrelated edit") }
                assertTrue(selection.unchanged())
                val selected = manager.getDocument(file)!!
                assertTrue("Opening a selected file without editing is allowed", selection.unchanged())
                WriteCommandAction.runWriteCommandAction(project) { selected.setText("edited after opening") }
                assertFalse(selection.unchanged())
                WriteCommandAction.runWriteCommandAction(project) { selected.setText("original content\n") }
                assertFalse("Reverting a selected file must not revive a pending generation", selection.unchanged())
            } finally {
                Disposer.dispose(selection)
            }
        } finally {
            Disposer.dispose(panel)
            directory.toFile().deleteRecursively()
        }
    }

    fun testBothProvidersStreamIntoNativeEditorAndPreserveLongFinalBody() {
        val root = Files.createTempDirectory("flow streaming commit ")
        try {
            for (profile in listOf("codex", "claude")) {
                Files.deleteIfExists(root.resolve("continue"))
                val panel = commitPanel()
                val service = NativeCommitMessageService(project)
                try {
                    panel.commitMessageUi.text = "original draft"
                    val document = panel.commitMessage.editorField.document
                    val agent = fixture(root, "stream", profile)
                    service.generate(NativeCommitSelection(project, panel, document) { true }, panel.commitMessageUi) { diff, progress, partial ->
                        NativeTextGenerator.generate(agent, diff, progress, onPartial = partial)
                    }
                    PlatformTestUtil.waitWithEventsDispatching("A draft must appear before the CLI finishes", { panel.commitMessageUi.text == "feat: draft in progress" }, 10)
                    assertTrue(service.running)
                    assertTrue(panel.commitMessage.editorField.isEnabled)
                    Files.writeString(root.resolve("continue"), "finish")
                    PlatformTestUtil.waitWithEventsDispatching("The long draft must finish", { !service.running }, 10)
                    val expected = "feat: describe checked changes\n\n" + "- Explain a selected module and its resulting behavior.\n".repeat(600)
                    assertEquals(expected.trim(), panel.commitMessageUi.text)
                    assertTrue(document.text.toByteArray().size > 16384)
                    assertFalse(document.text.contains("private reasoning"))
                    val record = com.google.gson.JsonParser.parseString(Files.readString(root.resolve("record.json"))).asJsonObject
                    assertFalse(Files.exists(Path.of(record["cwd"].asString)))
                } finally {
                    stop(service)
                    Disposer.dispose(panel)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testCancelledFailedAndStalePreviewsRestoreOnlyFlowOwnedText() {
        for (mode in listOf("cancel", "failure", "selection", "close", "typing", "typing-and-undo")) {
            val tree = object : ChangesListView(project, true) {}
            val panel = commitPanel(tree)
            val service = NativeCommitMessageService(project)
            val release = CompletableFuture<Unit>()
            val document = panel.commitMessage.editorField.document
            panel.commitMessageUi.text = "keep my original draft"
            val original = document.text
            val selection = NativeCommitSelection(project, panel, document) { true }
            try {
                service.generate(selection, panel.commitMessageUi) { _, progress, partial ->
                    partial("feat: partial preview")
                    release.get(10, TimeUnit.SECONDS)
                    progress.checkCanceled()
                    if (mode == "failure") error("Fixture generation failed")
                    partial("feat: late preview")
                    "feat: completed message"
                }
                PlatformTestUtil.waitWithEventsDispatching("Preview must be editable before completing", { panel.commitMessageUi.text == "feat: partial preview" }, 10)
                val partial = document.text
                when (mode) {
                    "cancel" -> service.cancel()
                    "close" -> Disposer.dispose(panel)
                    "selection" -> {
                        val included = panel.getIncludedChanges()
                        tree.inclusionModel.clearInclusion()
                        tree.inclusionModel.setInclusion(included)
                    }
                    "typing", "typing-and-undo" -> {
                        WriteCommandAction.runWriteCommandAction(project) { document.setText("my concurrent edit") }
                        if (mode == "typing-and-undo") WriteCommandAction.runWriteCommandAction(project) { document.setText(partial) }
                    }
                }
                release.complete(Unit)
                PlatformTestUtil.waitWithEventsDispatching("Generation must stop safely", { !service.running }, 10)
                val expected = when (mode) {
                    "typing" -> "my concurrent edit"
                    "typing-and-undo" -> partial
                    else -> original
                }
                assertEquals("Mode: $mode", expected, document.text)
            } finally {
                release.complete(Unit)
                stop(service)
                Disposer.dispose(panel)
            }
        }
    }

    fun testStreamedDraftIsOneUndoableNativeEdit() {
        val panel = commitPanel()
        val service = NativeCommitMessageService(project)
        val release = CompletableFuture<Unit>()
        panel.commitMessageUi.text = "original draft"
        val document = panel.commitMessage.editorField.document
        val original = document.text
        val editor = EditorFactory.getInstance().createEditor(document, project)
        try {
            service.generate(NativeCommitSelection(project, panel, document) { true }, panel.commitMessageUi) { _, _, partial ->
                partial("feat: preview")
                release.get(10, TimeUnit.SECONDS)
                "feat: complete draft\n\n- Describe the selected changes."
            }
            PlatformTestUtil.waitWithEventsDispatching("Preview must be visible", { panel.commitMessageUi.text == "feat: preview" }, 10)
            release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("Final draft must complete", { !service.running }, 10)
            val complete = document.text
            val undo = UndoManager.getInstance(project)
            val fileEditor = TextEditorProvider.getInstance().getTextEditor(editor)
            assertTrue(undo.isUndoAvailable(fileEditor))
            undo.undo(fileEditor)
            assertEquals("One undo must restore the original, not a partial preview", original, document.text)
            undo.redo(fileEditor)
            assertEquals(complete, document.text)
        } finally {
            release.complete(Unit)
            stop(service)
            EditorFactory.getInstance().releaseEditor(editor)
            Disposer.dispose(panel)
        }
    }

    fun testTypingAndNativeUndoDuringStreamingPreservesUserRevision() {
        val panel = commitPanel()
        val service = NativeCommitMessageService(project)
        val release = CompletableFuture<Unit>()
        val document = panel.commitMessage.editorField.document
        val editor = EditorFactory.getInstance().createEditor(document, project)
        try {
            service.generate(NativeCommitSelection(project, panel, document) { true }, panel.commitMessageUi) { _, _, partial ->
                partial("feat: preview")
                release.get(10, TimeUnit.SECONDS)
                "feat: must not replace an edited draft"
            }
            PlatformTestUtil.waitWithEventsDispatching("Preview must be visible", { panel.commitMessageUi.text == "feat: preview" }, 10)
            val partial = document.text
            WriteCommandAction.runWriteCommandAction(project, "User edit", null, Runnable { document.setText("my own draft") })
            UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(editor))
            assertEquals(partial, document.text)
            release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("An edited generation must finish", { !service.running }, 10)
            assertEquals("Undoing a user edit must still invalidate generation", partial, document.text)
        } finally {
            release.complete(Unit)
            stop(service)
            EditorFactory.getInstance().releaseEditor(editor)
            Disposer.dispose(panel)
        }
    }

    fun testFailedIncompleteAndTimedOutNativeStreamsRestoreOriginalDraft() {
        val root = Files.createTempDirectory("flow interrupted commit stream ")
        try {
            for ((profile, mode) in listOf("codex" to "stream-fail", "claude" to "stream-incomplete", "claude" to "stream-wait")) {
                Files.deleteIfExists(root.resolve("continue"))
                val panel = commitPanel()
                val service = NativeCommitMessageService(project)
                try {
                    panel.commitMessageUi.text = "original draft"
                    val document = panel.commitMessage.editorField.document
                    val original = document.text
                    val agent = fixture(root, mode, profile)
                    service.generate(NativeCommitSelection(project, panel, document) { true }, panel.commitMessageUi) { diff, progress, partial ->
                        NativeTextGenerator.generate(agent, diff, progress, timeoutMillis = if (mode == "stream-wait") 2000 else 10_000, onPartial = partial)
                    }
                    PlatformTestUtil.waitWithEventsDispatching("Failure fixture must first produce a preview", { panel.commitMessageUi.text == "feat: draft in progress" }, 10)
                    if (mode != "stream-wait") Files.writeString(root.resolve("continue"), "finish")
                    PlatformTestUtil.waitWithEventsDispatching("Partial drafts must not survive failure", { !service.running }, 10)
                    assertEquals(original, document.text)
                    val record = com.google.gson.JsonParser.parseString(Files.readString(root.resolve("record.json"))).asJsonObject
                    assertFalse("Scratch storage must be cleaned after failure", Files.exists(Path.of(record["cwd"].asString)))
                } finally {
                    stop(service)
                    Disposer.dispose(panel)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testNormalCommitToolbarUsesCheckedChangesAndDetectsCheckboxUndo() {
        val checked = change("chosen.txt", "old\n", "chosen change\n")
        val excluded = change("excluded.txt", "old\n", "excluded secret\n")
        val tree = object : ChangesListView(project, true) {}
        val root = ChangesBrowserNode.createRoot()
        root.add(ChangesBrowserNode.createChange(project, checked))
        root.add(ChangesBrowserNode.createChange(project, excluded))
        tree.updateTreeModel(DefaultTreeModel(root), ChangesTree.ALWAYS_RESET)
        val panel = ChangesViewCommitPanel(project, tree)
        try {
            val inclusion = tree.inclusionModel
            inclusion.setInclusion(listOf(checked))
            assertEquals(listOf(checked), panel.getIncludedChanges())
            val document = panel.commitMessage.editorField.document
            val selection = NativeCommitSelection(project, panel, document) { true }
            try {
                val diff = selection.diff()
                assertTrue(diff.contains("chosen change"))
                assertFalse(diff.contains("excluded secret"))
                assertFalse(diff.contains("excluded.txt"))
                assertTrue(selection.unchanged())

                val amend = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AmendCommitHandler::class.java)) { _, method, _ -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null } as AmendCommitHandler
                val handler = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CommitWorkflowHandler::class.java)) { _, method, _ -> if (method.name == "getAmendCommitHandler") amend else null } as CommitWorkflowHandler
                val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(VcsDataKeys.COMMIT_WORKFLOW_UI, panel).add(VcsDataKeys.COMMIT_WORKFLOW_HANDLER, handler).build()
                val event = AnActionEvent.createFromAnAction(GenerateCommitMessageAction(), null, "Vcs.MessageActionGroup", data)
                GenerateCommitMessageAction().update(event)
                assertTrue("Normal checked-change commits must support generation", event.presentation.isEnabled)
                val registered = ActionManager.getInstance().getAction("Flow.GenerateCommitMessage")
                assertNotNull("Native toolbar icon", registered.templatePresentation.icon)
                assertTrue((ActionManager.getInstance().getAction("Vcs.MessageActionGroup") as DefaultActionGroup).getChildren(null).contains(registered))

                inclusion.clearInclusion()
                inclusion.setInclusion(listOf(checked))
                assertFalse("Unchecking and rechecking invalidates the pending generation", selection.unchanged())
            } finally {
                Disposer.dispose(selection)
            }
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testPatchIncludesAddedRemovedAndRenamedFilesAndRejectsOversize() {
        val oldPath = VcsUtil.getFilePath("${project.basePath}/old.txt", false)
        val newPath = VcsUtil.getFilePath("${project.basePath}/new.txt", false)
        val added = Change(null, SimpleContentRevision("new content\n", newPath, "WORKING"))
        val deleted = Change(SimpleContentRevision("deleted content\n", oldPath, "HEAD"), null)
        val renamed = Change(SimpleContentRevision("before rename\n", oldPath, "HEAD"), SimpleContentRevision("after rename\n", newPath, "WORKING"))
        val diff = NativeCommitSelection.diff(project, listOf(added, deleted, renamed))
        assertTrue(diff.contains("+new content"))
        assertTrue(diff.contains("-deleted content"))
        assertTrue(diff.contains("old.txt"))
        assertTrue(diff.contains("new.txt"))
        try {
            NativeCommitSelection.diff(project, listOf(change("huge.txt", "old", "x".repeat(256 * 1024))))
            fail("Oversized diff must not be silently truncated")
        } catch (_: IllegalArgumentException) {
            // The caller keeps the existing message.
        }
    }

    private fun fixture(root: Path, mode: String, profile: String): LocalAgent {
        val script = root.resolve("native cli fixture.py")
        Files.writeString(
            script,
            """
            import json, os, pathlib, sys, time
            mode, record = sys.argv[1:3]
            prompt = sys.stdin.read()
            pathlib.Path(record).write_text(json.dumps({'cwd': str(pathlib.Path.cwd()), 'args': sys.argv[3:], 'prompt': prompt, 'disableThinking': os.environ.get('CLAUDE_CODE_DISABLE_THINKING')}))
            if mode == 'wait':
                time.sleep(60)
            if mode == 'fail':
                print('private credentials should not be shown', file=sys.stderr)
                sys.exit(1)
            result = json.dumps({'prompt': 'Preserve the API and add a focused test.', 'assumptions': [], 'sources': []}) if mode.startswith('enhance') else 'feat: describe checked changes'
            if mode.startswith('stream') or mode == 'enhance-stream':
                codex = '--output-last-message' in sys.argv
                partial = '{"prompt":"Explain Main.java\\nwithout changing it' if mode == 'enhance-stream' else 'feat: draft in progress'
                if codex:
                    print(json.dumps({'type': 'item.completed', 'item': {'type': 'reasoning', 'text': 'private reasoning'}}), flush=True)
                    print(json.dumps({'type': 'item.updated', 'item': {'type': 'agent_message', 'text': partial}}), flush=True)
                else:
                    print(json.dumps({'type': 'stream_event', 'event': {'delta': {'type': 'thinking_delta', 'thinking': 'private reasoning'}}}), flush=True)
                    print(json.dumps({'type': 'stream_event', 'event': {'delta': {'type': 'text_delta', 'text': partial}}}), flush=True)
                deadline = time.monotonic() + 30
                while not pathlib.Path(record).with_name('continue').exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                if mode == 'stream-fail':
                    print('private credentials should not be shown', file=sys.stderr)
                    sys.exit(1)
                if mode == 'stream-incomplete':
                    sys.exit(0)
                if mode != 'enhance-stream':
                    result += '\n\n' + '- Explain a selected module and its resulting behavior.\n' * 600
                if codex:
                    print(json.dumps({'type': 'item.completed', 'item': {'type': 'agent_message', 'text': result}}), flush=True)
                else:
                    print(json.dumps({'type': 'assistant', 'message': {'content': [{'type': 'text', 'text': result}]}}), flush=True)
            if '--output-last-message' in sys.argv:
                pathlib.Path(sys.argv[sys.argv.index('--output-last-message') + 1]).write_text(result)
            else:
                print(json.dumps({'type': 'result', 'is_error': False, 'result': result}))
            """.trimIndent(),
        )
        return LocalAgent(profile, System.getProperty("flow.test.python"), listOf(script.toString(), mode, root.resolve("record.json").toString()), "fixture")
    }

    fun testBothNativeProvidersReceiveOnlyStdinDiffInScratchDirectory() {
        val root = Files.createTempDirectory("flow native commit test ")
        try {
            for (profile in listOf("codex", "claude")) {
                val draft = NativeTextGenerator.generate(fixture(root, "ok", profile), "selected content", EmptyProgressIndicator())
                assertEquals("feat: describe checked changes", draft)
                val record = com.google.gson.JsonParser.parseString(Files.readString(root.resolve("record.json"))).asJsonObject
                val cwd = Path.of(record["cwd"].asString)
                assertFalse(cwd.toString().startsWith(project.basePath!!))
                assertFalse("Scratch directory is cleaned", Files.exists(cwd))
                assertTrue(record["prompt"].asString.contains("selected content"))
                assertFalse(record["args"].toString().contains("selected content"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testEnhancementUsesCapturedModelAndNativeLoginTransportForBothProviders() {
        val root = Files.createTempDirectory("flow native enhancement test ")
        try {
            for (profile in listOf("codex", "claude")) {
                val target = UtilityAgent(profile, "provider-model", "high")
                val result = NativeTextGenerator.generate(fixture(root, "enhance", profile), "{\"userTask\":\"Preserve the API\"}", EmptyProgressIndicator(), settings = target, purpose = "enhance")
                assertEquals("Preserve the API and add a focused test.", NativeTextGenerator.enhancement(result)["prompt"].asString)
                val record = com.google.gson.JsonParser.parseString(Files.readString(root.resolve("record.json"))).asJsonObject
                val arguments = record["args"].asJsonArray.map { it.asString }
                assertEquals("provider-model", arguments[arguments.indexOf("--model") + 1])
                assertTrue(record["prompt"].asString.contains("assumptions"))
                assertTrue(record["prompt"].asString.contains("Do not run tools"))
                assertFalse(record["args"].toString().contains("Preserve the API"))
                if (profile == "codex") {
                    assertTrue(arguments.contains("model_reasoning_effort=\"low\""))
                    assertFalse(arguments.contains("model_reasoning_effort=\"high\""))
                } else {
                    assertFalse(arguments.contains("--effort"))
                    assertEquals("1", record["disableThinking"].asString)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testEnhancementReviewStreamsThenRequiresExplicitCurrentAcceptanceAndStopsItsProcess() {
        val root = Files.createTempDirectory("flow prompt review ")
        val preferences = ApplicationManager.getApplication().getService(EnhancementPreferences::class.java)
        val saved = EnhancementPreferences.Preferences().apply {
            provider = preferences.state.provider
            claudeModel = preferences.state.claudeModel
            codexModel = preferences.state.codexModel
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            preferences.save("automatic", "")
            for (profile in listOf("codex", "claude")) {
                val field = javax.swing.JTextArea("What does Main.java do?")
                val revision = TextRevision(field)
                var provenance = ""
                val config = dev.flow.bridge.AgentProtocol.configOptions(com.google.gson.JsonParser.parseString("""[{"id":"model","name":"Model","description":"","kind":"select","category":"model","currentValue":"confirmed-model","options":[]}]""").asJsonArray)
                val agent = dev.flow.bridge.AgentView(id = "chat", status = "running", agentName = "$profile-acp", configOptions = config)
                val native = fixture(root, "enhance-stream", profile)
                var latest: com.google.gson.JsonObject? = null
                val review = PromptEnhancementReview(project, scope, { profile }, { agent }, { latest = it }, prepare = { json ->
                    assertEquals(field.text, com.google.gson.JsonParser.parseString(json).asJsonObject["userTask"].asString)
                    "fixture-hash"
                }, agents = { listOf(native) })
                fun action(operation: String, output: String = "") = kotlinx.coroutines.runBlocking {
                    review.action(
                        com.google.gson.JsonObject().apply {
                            addProperty("reviewId", review.state!!["id"].asString)
                            addProperty("generation", review.state!!["generation"].asLong)
                            addProperty("operation", operation)
                            addProperty("output", output)
                        },
                    )
                }
                try {
                    Files.deleteIfExists(root.resolve("continue"))
                    review.open(field, revision) { provenance = it }
                    PlatformTestUtil.waitWithEventsDispatching("Only prompt text must stream before completion", { latest?.get("output")?.asString == "Explain Main.java\nwithout changing it" }, 10)
                    assertEquals("What does Main.java do?", field.text)
                    assertEquals("confirmed-model", latest!!["model"].asString)
                    assertTrue(runCatching { action("accept", "Partial cannot be accepted") }.isFailure)
                    val old = latest!!.deepCopy()
                    Files.writeString(root.resolve("continue"), "finish")
                    PlatformTestUtil.waitWithEventsDispatching("Only a complete valid result is acceptable", { latest?.get("status")?.asString == "ready" }, 10)
                    assertEquals("generating", old["status"].asString)
                    assertEquals("Preserve the API and add a focused test.", latest!!["output"].asString)
                    field.append(" changed")
                    field.text = "What does Main.java do?"
                    action("accept", "Do not overwrite my draft")
                    assertEquals("failed", latest!!["status"].asString)
                    assertEquals("What does Main.java do?", field.text)
                    assertEquals("", provenance)
                    action("close")
                    review.open(field, revision) { provenance = it }
                    PlatformTestUtil.waitWithEventsDispatching("Regenerated request must finish", { latest?.get("status")?.asString == "ready" }, 10)
                    action("accept", "Explain Main.java without modifying it.")
                    assertNull(latest)
                    assertEquals("Explain Main.java without modifying it.", field.text)
                    assertEquals("reviewed-enhancement:fixture-hash", provenance)
                    Files.deleteIfExists(root.resolve("continue"))
                    review.open(field, revision) { provenance = it }
                    PlatformTestUtil.waitWithEventsDispatching("Cancellation needs a running native request", { latest?.get("output")?.asString?.isNotBlank() == true }, 10)
                    val cwd = Path.of(com.google.gson.JsonParser.parseString(Files.readString(root.resolve("record.json"))).asJsonObject["cwd"].asString)
                    action("cancel")
                    PlatformTestUtil.waitWithEventsDispatching("Stopping must reap the native process and delete scratch input", { !Files.exists(cwd) }, 10)
                    assertEquals("cancelled", latest!!["status"].asString)
                    assertEquals("Explain Main.java without modifying it.", field.text)
                    action("close")
                    assertNull(latest)
                    assertTrue(runCatching { kotlinx.coroutines.runBlocking { review.action(old) } }.isFailure)
                } finally {
                    review.dispose()
                    Files.writeString(root.resolve("continue"), "finish")
                }
            }
        } finally {
            preferences.loadState(saved)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            root.toFile().deleteRecursively()
        }
    }

    fun testCoroutineCancellationStopsTheNativeUtilityAndCleansScratchStorage() {
        val root = Files.createTempDirectory("flow interrupted utility test ")
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    val agent = fixture(root, "wait", "codex")
                    val work = launch(kotlinx.coroutines.Dispatchers.IO) {
                        NativeTextGenerator.generateCancellable(agent, "diff", EmptyProgressIndicator())
                    }
                    while (!Files.exists(root.resolve("record.json"))) kotlinx.coroutines.delay(20)
                    work.cancel()
                    work.join()
                    val record = com.google.gson.JsonParser.parseString(Files.readString(root.resolve("record.json"))).asJsonObject
                    assertFalse(Files.exists(Path.of(record["cwd"].asString)))
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testCancellationTimeoutAndFailureRetainActionableErrorsWithoutStderr() {
        val root = Files.createTempDirectory("flow cancel commit test ")
        try {
            val agent = fixture(root, "wait", "codex")
            val indicator = EmptyProgressIndicator()
            val work = CompletableFuture.supplyAsync {
                try {
                    NativeTextGenerator.generate(agent, "diff", indicator)
                    false
                } catch (_: ProcessCanceledException) {
                    true
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(root.resolve("record.json")) && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(Files.exists(root.resolve("record.json")))
            indicator.cancel()
            assertTrue(work.get(10, TimeUnit.SECONDS))
            try {
                NativeTextGenerator.generate(agent, "diff", EmptyProgressIndicator(), timeoutMillis = 10)
                fail("Must time out")
            } catch (error: IllegalStateException) {
                assertTrue(error.message!!.contains("timed out"))
            }
            try {
                NativeTextGenerator.generate(fixture(root, "fail", "claude"), "diff", EmptyProgressIndicator())
                fail("Must fail")
            } catch (error: IllegalStateException) {
                assertTrue(error.message!!.contains("terminal"))
                assertFalse(error.message!!.contains("private credentials"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
