package dev.flow.ui

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import dev.flow.bridge.AgentCommandDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Local suggestions only. Definitions are read as metadata; native agents execute commands/skills. */
internal class ComposerCompletions(private val project: Project?) {
    internal data class Item(val kind: String, val label: String, val detail: String, val insert: String)
    private var cachedFiles = emptyList<String>()
    private var scannedAt = 0L
    private val gson = Gson()

    suspend fun complete(trigger: String, query: String, provider: String, workspace: String?, commands: List<AgentCommandDefinition>): JsonArray {
        require(trigger in setOf("@", "/", "$", "#") && query.length <= 256)
        if (trigger == "/") {
            return gson.toJsonTree(
                commands.filter { it.name.contains(query, true) || it.description.contains(query, true) }.take(30).map {
                    Item("command", it.name, listOf(it.description, it.inputHint).filter(String::isNotBlank).joinToString(" · "), "/${it.name} ")
                },
            ).asJsonArray
        }
        val target = requireNotNull(project) { "Open a project to use references." }
        val root = canonical(Path.of(requireNotNull(target.basePath)))
        val suggestions = withContext(Dispatchers.Default) {
            val roles = if (trigger == "#" || trigger == "@" && query.startsWith("agent-")) agents(provider, canonical(workspace?.let(Path::of) ?: root)) else emptyList()
            when (trigger) {
                "#" -> roles
                "$" -> if (provider == "codex") skills(root, workspace).map { Item("skill", it.first, it.second, "\$${it.first} ") } else emptyList()
                else -> if (query.startsWith("agent-") && provider == "claude") roles.map { it.copy(label = "agent-${it.label}", insert = "@agent-${it.label} ") } else files(target, root).map { Item("file", it, "Current project file", "@\"$it\" ") }
            }.filter { it.label.contains(query, true) || it.detail.contains(query, true) }.take(30)
        }
        return gson.toJsonTree(suggestions).asJsonArray
    }

    @Synchronized
    private fun files(target: Project, root: Path): List<String> {
        if (System.nanoTime() - scannedAt < 2_000_000_000L) return cachedFiles
        val paths = mutableListOf<String>()
        ReadAction.run<RuntimeException> {
            var visited = 0
            ProjectFileIndex.getInstance(target).iterateContent { file ->
                visited++
                if (!file.isDirectory && !file.name.contains('"') && file.path.length <= 4096) {
                    val path = Path.of(file.path)
                    if (path.startsWith(root)) {
                        val relative = root.relativize(path).toString().replace('\\', '/')
                        if (relative.split('/').none { it == ".git" || it == ".flow" } && !Files.isSymbolicLink(path)) paths.add(relative)
                    }
                }
                visited < 20000 && paths.size < 10000
            }
        }
        cachedFiles = paths.sorted()
        scannedAt = System.nanoTime()
        return cachedFiles
    }

    private fun agents(provider: String, root: Path): List<Item> {
        if (provider !in setOf("claude", "codex")) return emptyList()
        val builtins = when (provider) {
            "claude" -> listOf("Explore", "Plan", "general-purpose")
            "codex" -> listOf("default", "worker", "explorer")
            else -> emptyList()
        }.map { Item("agent", it, "Built-in subagent · availability is controlled by your agent", "Use the $it subagent to ") }
        val folder = if (provider == "claude") ".claude/agents" else ".codex/agents"
        val user = if (provider == "claude") System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".claude") else codexHome()
        val custom = listOf(root.resolve(folder), user.resolve("agents")).flatMap { directory ->
            definitions(directory, if (provider == "claude") ".md" else ".toml").mapNotNull { path ->
                val header = metadata(path) ?: return@mapNotNull null
                val name = header["name"] ?: path.fileName.toString().substringBeforeLast('.')
                if (!NAME.matches(name)) return@mapNotNull null
                Item("agent", name, header["description"].orEmpty().ifBlank { "Custom native subagent" }, "Use the $name subagent to ")
            }
        }
        return (custom + builtins).distinctBy { it.label }
    }

    private fun skills(root: Path, workspace: String?): List<Pair<String, String>> {
        val base = canonical(workspace?.let(Path::of) ?: root)
        return listOf(base.resolve(".agents/skills"), base.resolve(".codex/skills"), codexHome().resolve("skills"), codexHome().resolve("skills/.system")).flatMap { directory ->
            definitions(directory, null).mapNotNull { folder ->
                val header = metadata(folder.resolve("SKILL.md")) ?: return@mapNotNull null
                val name = header["name"] ?: folder.fileName.toString()
                if (NAME.matches(name)) name to header["description"].orEmpty() else null
            }
        }.distinctBy { it.first }
    }

    private fun codexHome() = System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".codex")
    private fun canonical(path: Path) = runCatching { path.toRealPath() }.getOrDefault(path.toAbsolutePath().normalize())
    private fun definitions(directory: Path, suffix: String?): List<Path> = runCatching {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory) || directory.toRealPath() != directory.toAbsolutePath().normalize()) return emptyList()
        Files.list(directory).use { stream -> stream.filter { !Files.isSymbolicLink(it) && (suffix == null || it.fileName.toString().endsWith(suffix)) }.limit(128).toList() }
    }.getOrDefault(emptyList())

    private fun metadata(file: Path): Map<String, String>? = runCatching {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536) return null
        Files.newBufferedReader(file).useLines { lines ->
            lines.take(64).mapNotNull { line ->
                HEADER.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2].trim().trim('"', '\'').take(512) }
            }.distinctBy { it.first }.toMap()
        }
    }.getOrNull()

    /** File links use IDE navigation only and can never escape the project or active worktree. */
    suspend fun open(reference: String, workspace: String?, valid: () -> Boolean = { true }) {
        val target = requireNotNull(project)
        val location = withContext(Dispatchers.Default) {
            resolve(reference, listOfNotNull(target.basePath, workspace).mapNotNull { runCatching { Path.of(it).toRealPath() }.getOrNull() })
        }
        check(valid() && !target.isDisposed) { "The session changed. Select the file reference again." }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(location.first) ?: error("The referenced file is unavailable.")
        OpenFileDescriptor(target, file, location.second - 1, 0).navigate(true)
    }

    companion object {
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        private val HEADER = Regex("\\s*(name|description)\\s*[:=]\\s*(.+)")
        internal fun resolve(reference: String, roots: List<Path>): Pair<Path, Int> {
            require(reference.length in 1..4096 && reference.none(Char::isISOControl)) { "Invalid file reference." }
            var value = reference.removePrefix("@")
            if (!value.startsWith("file:") && Regex("%[a-fA-F0-9]{2}").containsMatchIn(value)) value = java.net.URLDecoder.decode(value.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8)
            if (value.startsWith("file:")) value = Path.of(URI(value.substringBefore('#'))).toString() + value.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
            require(!value.contains("://")) { "Only local file references can be opened." }
            val match = Regex("^(.*?)(?:#L([1-9][0-9]*)(?:-L?[1-9][0-9]*)?|:([1-9][0-9]*)(?::[1-9][0-9]*)?)?$").matchEntire(value) ?: error("Invalid file reference.")
            val line = match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { "1" }.toIntOrNull()?.also { require(it in 1..10000000) } ?: error("Invalid line number.")
            val path = Path.of(match.groupValues[1])
            require(path.none { it.toString() == ".." }) { "The referenced file is outside this project." }
            for (root in roots) {
                val boundary = root.toRealPath()
                val candidate = if (path.isAbsolute) path else boundary.resolve(path)
                val real = runCatching { candidate.toRealPath() }.getOrNull() ?: continue
                if (real.startsWith(boundary) && Files.isRegularFile(real)) return real to line
            }
            error("The referenced file is missing or outside this project and its active worktree.")
        }
    }
}
