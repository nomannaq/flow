package dev.flow.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.components.JBTextArea
import dev.flow.bridge.EngineController
import dev.flow.bridge.ProjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files

class ComposerContextTest : LightPlatformTestCase() {
    fun testNativeAgentMentionsAndQuotedLineReferencesKeepTheirMeaning() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("native-references"), { error("References must not launch an agent") })
        val prompt = JBTextArea("Ask @agent-explorer to inspect the source")
        val context = ComposerContext(project, prompt, controller, scope)
        val root = java.nio.file.Path.of(project.basePath!!)
        Files.createDirectories(root)
        val folder = Files.createTempDirectory(root, "flow-lines-")
        try {
            assertEquals(prompt.text, context.prepare().text)
            val file = Files.writeString(folder.resolve("source.txt"), "first line\nselected line\nlast line\n")
            val relative = root.relativize(file).toString().replace('\\', '/')
            prompt.text = "Inspect @\"$relative#L2-2\""
            val prepared = context.prepare().text
            assertTrue(prepared.contains("selected line"))
            assertFalse(prepared.contains("first line"))
            assertFalse(prepared.contains("last line"))
            assertTrue(prepared.contains("\"startLine\":2"))
            context.clear()
            prompt.text = "@\"$relative#L99\""
            assertTrue(runCatching { context.prepare() }.isFailure)
        } finally {
            context.dispose()
            folder.toFile().deleteRecursively()
            scope.cancel()
        }
    }

    fun testAcknowledgementOnlyClearsTheUnchangedDraft() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("draft-ack"), { error("No agent required") })
        val prompt = JBTextArea("first draft")
        val context = ComposerContext(null, prompt, controller, scope)
        try {
            val original = context.prepare()
            prompt.text = "newer draft"
            context.acknowledge(original)
            assertEquals("newer draft", prompt.text)
            val current = context.prepare()
            context.acknowledge(current)
            assertEquals("", prompt.text)
        } finally {
            context.dispose()
            scope.cancel()
        }
    }

    fun testExplicitReferencesAreFrozenRemovableAndDoNotChangeTheWorkspace() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = EngineController(scope, ProjectId("context-ui"), { error("Context selection must not launch an agent") })
        val prompt = JBTextArea("Explain this code")
        val context = ComposerContext(project, prompt, controller, scope)
        val root = java.nio.file.Path.of(project.basePath!!)
        Files.createDirectories(root)
        val directory = Files.createTempDirectory(root, "flow context test ")
        try {
            val file = Files.writeString(directory.resolve("selected file.rs"), "fn original() {}\n")
            val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
            context.attach(virtual)
            val payload = context.prepare()
            assertTrue(payload.text.contains("currentProjectEditorSnapshot"))
            assertTrue(payload.text.contains("fn original()"))
            assertEquals("fn original() {}\n", Files.readString(file))
            val document = FileDocumentManager.getInstance().getDocument(virtual)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText("fn changed() {}\n") }
            WriteCommandAction.runWriteCommandAction(project) { document.setText("fn original() {}\n") }
            try {
                context.prepare()
                fail("Typing and undo must invalidate a reference")
            } catch (_: IllegalStateException) { }
            context.clear()
            assertFalse(context.prepare().text.contains("currentProjectEditorSnapshot"))
            prompt.text = "Explain @\"${root.relativize(file).toString().replace('\\', '/')}\""
            assertTrue(context.prepare().text.contains("fn original()"))
            fun controls(component: java.awt.Component): List<java.awt.Component> = listOf(component) +
                if (component is java.awt.Container) component.components.flatMap(::controls) else emptyList()
            controls(context).filterIsInstance<javax.swing.JButton>().single { it.accessibleContext.accessibleName.startsWith("Remove context ") }.doClick()
            assertFalse("Removing a chip must not reattach its typed @ reference", context.prepare().text.contains("currentProjectEditorSnapshot"))
            val automatic = controls(context.workspaceSettings).filterIsInstance<javax.swing.JCheckBox>().single()
            automatic.doClick()
            assertFalse("Automatic prompt hints can be disabled without disconnecting", context.prepare().includeContext)
            automatic.doClick()
            assertTrue(context.prepare().includeContext)
            context.clear()
            prompt.text = "Explain @../outside.rs"
            try {
                context.prepare()
                fail("References must not escape the project")
            } catch (_: IllegalArgumentException) { }
        } finally {
            context.dispose()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }
}
