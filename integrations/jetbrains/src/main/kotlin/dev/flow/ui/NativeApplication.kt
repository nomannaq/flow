package dev.flow.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** All methods run on the EDT. Nothing saves an already-unsaved document. */
internal class NativeApplication(private val project: Project, private val projectRoot: Path = Path.of(requireNotNull(project.basePath))) {
    private data class Target(val documentStamp: Long?, val parents: Map<Path, Any?>, val index: Int)
    private val targets = mutableMapOf<String, Target>()
    private var root: Path? = null
    private var planId: String? = null

    fun capture(plan: JsonObject): JsonObject {
        check(!project.isDisposed)
        val path = Path.of(plan["root"].asString).toRealPath()
        require(path == projectRoot.toRealPath() && path == path.toRealPath()) { "Application project changed" }
        root = path
        planId = plan["id"].asString
        targets.clear()
        val stamps = JsonObject()
        require(plan.getAsJsonArray("files").size() <= 128)
        for ((index, element) in plan.getAsJsonArray("files").withIndex()) {
            val file = element.asJsonObject
            if (file["status"].asString == "unchanged") continue
            val relative = file["path"].asString
            val target = checkedPath(relative)
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            val document = vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            check(document == null || !FileDocumentManager.getInstance().isDocumentUnsaved(document)) { "Save or exclude the affected unsaved buffer, then replan" }
            val stamp = document?.modificationStamp
            if (stamp != null) stamps.addProperty(relative, stamp)
            check(relative !in targets)
            targets[relative] = Target(stamp, parents(target), index)
        }
        return stamps
    }

    fun write(ticket: JsonObject) {
        check(!project.isDisposed)
        val relative = ticket["path"].asString
        val captured = requireNotNull(targets[relative])
        check(ticket["planId"].asString == planId && ticket["fileIndex"].asInt == captured.index)
        val expected = bytes(ticket["expected"])
        val replacement = bytes(ticket["replacement"])
        WriteCommandAction.runWriteCommandAction(
            project,
            "Apply Flow complete result",
            null,
            Runnable {
                val path = checkedPath(relative)
                check(captured.parents.all { (parent, identity) -> attributes(parent).fileKey() == identity }) { "Application parent directory changed" }
                var vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                vf?.refresh(false, false)
                val document = vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
                check(document?.modificationStamp == captured.documentStamp) { "Document changed after application planning" }
                check(document == null || !FileDocumentManager.getInstance().isDocumentUnsaved(document)) { "Unsaved document preserved" }
                check(same(read(path), expected)) { "Newer disk content preserved" }
                if (expected != null) check(mode(path) == ticket["beforeMode"].asInt) { "File permissions changed" }
                // The native write command serializes IDE typing. Disk writers remain
                // optimistic: byte/identity checks run immediately before mutation.
                if (document != null) {
                    check(replacement != null) { "Close the editor before applying deletion" }
                    check(same(document.text.toByteArray(Charsets.UTF_8), expected) && vf?.charset == Charsets.UTF_8) { "Open file encoding/line separators are unsupported; close its editor first" }
                    val text = strictText(replacement)
                    check(!text.contains('\r') && !text.startsWith('\uFEFF')) { "Close this editor to preserve exact BOM/line-separator bytes" }
                    document.setText(text)
                    FileDocumentManager.getInstance().saveDocument(document)
                    check(!FileDocumentManager.getInstance().isDocumentUnsaved(document)) { "IDE did not save the applied document; inspect recovery" }
                } else if (replacement == null) {
                    requireNotNull(vf).delete(this)
                } else {
                    if (vf == null) {
                        val parent = VfsUtil.createDirectories(requireNotNull(path.parent).toString())
                        checkedPath(relative)
                        check(!Files.exists(path, NOFOLLOW_LINKS)) { "A new file collided with the result" }
                        vf = parent.createChildData(this, path.fileName.toString())
                    }
                    vf!!.setBinaryContent(replacement)
                }
                checkedPath(relative)
                if (replacement != null) FileChannel.open(path, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { it.force(true) }
                if (!System.getProperty("os.name").startsWith("Windows")) FileChannel.open(requireNotNull(path.parent), StandardOpenOption.READ).use { it.force(true) }
                check(same(read(path), replacement)) { "Written bytes differ; inspect recovery" }
                if (replacement != null) check(mode(path) == ticket["afterMode"].asInt) { "Written mode differs; inspect recovery" }
            },
        )
    }
    private fun checkedPath(relative: String): Path {
        require(relative.isNotEmpty() && relative.length <= 4096 && relative.all { it.code in 32..126 } && relative.none { it in "\\:*?<>|\"" })
        val parts = relative.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) || it.startsWith("git~", true) || it.startsWith(".git~", true) || it.endsWith('.') || it.endsWith(' ') })
        var current = requireNotNull(root)
        check(current == current.toRealPath())
        for ((index, part) in parts.withIndex()) {
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(current).use { children ->
                    check(children.none { it.fileName.toString().equals(part, true) && it.fileName.toString() != part }) { "Case collision" }
                }
            }
            current = current.resolve(part)
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                val attrs = attributes(current)
                check(!attrs.isSymbolicLink && !attrs.isOther && current == current.toRealPath()) { "Symlink or unsupported file preserved" }
                if (index < parts.lastIndex) {
                    check(attrs.isDirectory && !Files.exists(current.resolve(".git"), NOFOLLOW_LINKS))
                } else {
                    check(attrs.isRegularFile && attrs.size() <= 65536)
                    if (current.fileSystem.supportedFileAttributeViews().contains("unix")) check(Files.getAttribute(current, "unix:nlink", NOFOLLOW_LINKS) == 1)
                }
            }
        }
        return current
    }
    private fun parents(path: Path): Map<Path, Any?> = generateSequence(path.parent) { it.parent }.takeWhile { it.startsWith(requireNotNull(root)) }.filter { Files.exists(it, NOFOLLOW_LINKS) }.associateWith { attributes(it).fileKey() }
    private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun read(path: Path): ByteArray? = if (Files.exists(path, NOFOLLOW_LINKS)) {
        check(attributes(path).size() <= 65536)
        Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(65537) }.also { check(it.size <= 65536) }
    } else {
        null
    }
    private fun mode(path: Path): Int = if (path.fileSystem.supportedFileAttributeViews().contains("unix")) {
        (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int) and 4095
    } else if (Files.isWritable(path)) {
        420
    } else {
        292
    }
    private fun bytes(value: JsonElement): ByteArray? = if (value.isJsonNull) {
        null
    } else {
        value.asJsonArray.also { require(it.size() <= 65536) }.map {
            require(it.asInt in 0..255)
            it.asInt.toByte()
        }.toByteArray()
    }
    private fun same(a: ByteArray?, b: ByteArray?) = if (a == null || b == null) a == null && b == null else a.contentEquals(b)
    private fun strictText(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().also { require(!it.contains('\u0000')) }
}
