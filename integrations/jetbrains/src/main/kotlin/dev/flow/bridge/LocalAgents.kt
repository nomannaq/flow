package dev.flow.bridge

import com.intellij.openapi.application.PathManager
import com.intellij.util.EnvironmentUtil
import java.nio.file.Files
import java.nio.file.Path

internal data class LocalAgent(val profile: String, val executable: String, val arguments: List<String>, val description: String)

/** Locate launchers only. Credentials remain with the native CLI and OS credential store. */
internal object LocalAgents {
    private val windows get() = System.getProperty("os.name").startsWith("Windows")

    fun environment(): Map<String, String> {
        val values = EnvironmentUtil.getEnvironmentMap().toMutableMap()
        val directories = directories(values)
        val pathKey = values.keys.firstOrNull { it.equals("PATH", ignoreCase = windows) } ?: "PATH"
        values[pathKey] = directories.joinToString(java.io.File.pathSeparator)
        return values
    }

    fun launchEnvironment(): Map<String, String> {
        val allowed = listOf("HOME", "PATH", "TMPDIR", "XDG_DATA_HOME", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATHEXT", "COMSPEC", "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMDATA", "LANG", "LC_ALL", "TERM", "CODEX_HOME", "CODEX_PATH", "CODEX_CA_CERTIFICATE", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CONFIG_DIR", "CURSOR_API_KEY", "CURSOR_AUTH_TOKEN", "CODEX_SANDBOX", "CODEX_SANDBOX_NETWORK_DISABLED", "CODEX_API_KEY", "OPENAI_API_KEY", "SSL_CERT_FILE", "SSL_CERT_DIR", "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY", "__CF_USER_TEXT_ENCODING").toSet()
        return environment().filterKeys { key -> allowed.any { key.equals(it, ignoreCase = windows) } }
    }

    fun discover(
        environment: Map<String, String> = environment(),
        cache: Path = Path.of(PathManager.getSystemPath()),
        home: Path = Path.of(System.getProperty("user.home")),
        searchDirectories: List<Path> = directories(environment, home),
        windowsHost: Boolean = windows,
        nativeCli: Boolean = false,
    ): List<LocalAgent> {
        val paths = searchDirectories.distinct()
        val cachedPaths by lazy {
            val found = mutableListOf<Path>()
            val roots = listOf(cache) + children(cache.parent).filter { it.fileName.toString().contains(Regex("(?:Idea|RustRover|PyCharm|WebStorm|CLion|IntelliJ)")) }
            for (root in roots.distinct()) {
                for (agent in children(root.resolve("acp-agents"))) {
                    for (version in children(agent).sortedDescending().take(12)) found.add(version.resolve("node_modules/.bin"))
                }
            }
            if (!nativeCli) {
                val zedRoots = listOf(home.resolve("Library/Application Support/Zed/external_agents"), home.resolve(".local/share/zed/external_agents")) +
                    listOfNotNull(environment["LOCALAPPDATA"]?.let { Path.of(it, "Zed/external_agents") })
                for (root in zedRoots) for (agent in children(root)) found.addAll(children(agent).sortedDescending().take(12))
            }
            if (nativeCli) for (entry in children(cache.resolve("flow/adapters/npm-cache/_npx"))) found.add(entry.resolve("node_modules/.bin"))
            found.distinct()
        }
        fun directories() = paths.asSequence() + sequence { yieldAll(cachedPaths) }
        fun locate(name: String): Path? = directories().flatMap { directory ->
            (if (windowsHost) listOf("$name.exe", "$name.cmd", name) else listOf(name)).asSequence().map(directory::resolve)
        }.firstOrNull { Files.isRegularFile(it) && (windowsHost || Files.isExecutable(it)) }
        val node by lazy { locate("node")?.toAbsolutePath() }
        fun launch(path: Path): Pair<String, List<String>>? {
            val real = runCatching { path.toRealPath() }.getOrNull() ?: return null
            if (real.toString().endsWith(".js") || real.toString().endsWith(".mjs")) return node?.let { it.toString() to listOf(real.toString()) }
            if (!real.toString().endsWith(".cmd", true)) return real.toString() to emptyList()
            // Never feed a user-controlled path through cmd.exe. Use the package entry point.
            val packagePath = if (nativeCli) {
                if (path.fileName.toString().startsWith("claude")) "@anthropic-ai/claude-code/cli.js" else "@openai/codex/bin/codex.js"
            } else {
                val packageName = if (path.fileName.toString().startsWith("claude")) "claude-agent-acp" else "codex-acp"
                "@agentclientprotocol/$packageName/dist/index.js"
            }
            val entry = listOf(path.parent.resolve("node_modules/$packagePath"), path.parent.resolve("../$packagePath")).firstOrNull(Files::isRegularFile)
            return node?.takeIf { entry != null }?.let { it.toString() to listOf(entry!!.normalize().toString()) }
        }
        val npxEntry by lazy {
            locate("npx")?.let { runCatching { it.toRealPath() }.getOrNull() }?.takeIf { it.toString().endsWith(".js") }
                ?: directories().flatMap { directory -> sequenceOf(directory.resolve("node_modules/npm/bin/npx-cli.js"), directory.resolve("../lib/node_modules/npm/bin/npx-cli.js")) }.firstOrNull(Files::isRegularFile)
        }
        val adapters = listOf("codex" to "2.2.2", "claude" to "0.93.0").mapNotNull { (profile, version) ->
            val command = if (nativeCli) {
                profile
            } else if (profile == "codex") {
                "codex-acp"
            } else {
                "claude-agent-acp"
            }
            val cached by lazy {
                if (nativeCli) {
                    null
                } else {
                    children(cache.resolve("flow/adapters/npm-cache/_npx")).sortedDescending().firstNotNullOfOrNull { entry ->
                        val metadata = entry.resolve("node_modules/@agentclientprotocol/$command/package.json")
                        val matches = runCatching {
                            Files.isRegularFile(metadata) && Files.size(metadata) <= 16384 &&
                                com.google.gson.JsonParser.parseString(Files.readString(metadata)).asJsonObject.let { it["name"].asString == "@agentclientprotocol/$command" && it["version"].asString == version }
                        }.getOrDefault(false)
                        if (!matches) {
                            null
                        } else {
                            val bin = entry.resolve("node_modules/.bin")
                            (if (windowsHost) listOf("$command.cmd", "$command.exe", command) else listOf(command)).asSequence()
                                .map(bin::resolve).firstNotNullOfOrNull { candidate ->
                                    runCatching { candidate.toRealPath().takeIf { it.startsWith(entry.toRealPath()) } }.getOrNull()?.let { launch(candidate) }
                                }
                        }
                    }
                }
            }
            val installed = locate(command)?.let(::launch) ?: cached
            if (installed != null) {
                LocalAgent(profile, installed.first, installed.second, "Installed $command · uses your native login")
            } else if (!nativeCli) {
                val runner = node
                val entry = npxEntry
                if (runner != null && entry != null) {
                    val directory = cache.resolve("flow/adapters").toAbsolutePath()
                    LocalAgent(profile, runner.toString(), listOf(entry.toAbsolutePath().normalize().toString(), "--yes", "--ignore-scripts", "--registry=https://registry.npmjs.org", "--prefix=$directory", "--cache=${directory.resolve("npm-cache")}", "--package=@agentclientprotocol/$command@$version", command), "Connect downloads $command $version into the IDE cache using npm; uses your native login. Node.js 22+ is required.")
                } else {
                    null
                }
            } else {
                null
            }
        }
        val cursor = if (nativeCli || windowsHost) {
            null
        } else {
            locate("agent")?.let(::launch)?.let {
                LocalAgent("cursor", it.first, it.second + "acp", "Installed Cursor agent · direct ACP connection using your existing Cursor login")
            }
        }
        return (adapters + listOfNotNull(cursor)).sortedBy { agent -> agent.arguments.any { it.startsWith("--package=@agentclientprotocol/") } }
    }

    private fun directories(values: Map<String, String>, home: Path = Path.of(System.getProperty("user.home"))): List<Path> {
        val path = values.entries.firstOrNull { it.key.equals("PATH", ignoreCase = windows) }?.value.orEmpty()
        fun value(name: String) = values.entries.firstOrNull { it.key.equals(name, ignoreCase = windows) }?.value
        val common = if (windows) listOfNotNull(value("APPDATA")?.let { Path.of(it, "npm") }, value("PROGRAMFILES")?.let { Path.of(it, "nodejs") }, value("LOCALAPPDATA")?.let { Path.of(it, "Programs/nodejs") }) else listOf(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"), Path.of("/usr/bin"), Path.of("/bin"))
        return (path.split(java.io.File.pathSeparator).filter { it.isNotBlank() }.mapNotNull { runCatching { Path.of(it) }.getOrNull() }.filter(Path::isAbsolute) + common + listOf(home.resolve(".local/bin"), home.resolve(".volta/bin"), home.resolve(".npm-global/bin")) + children(home.resolve(".nvm/versions/node")).sortedDescending().take(12).map { it.resolve("bin") }).distinct()
    }

    private fun children(path: Path?): List<Path> = if (path == null || !Files.isDirectory(path)) emptyList() else runCatching { Files.list(path).use { stream -> stream.filter(Files::isDirectory).limit(64).toList() } }.getOrDefault(emptyList())
}
