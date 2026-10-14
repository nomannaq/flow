package dev.flow.bridge

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Service(Service.Level.PROJECT)
class EngineService(private val project: Project, internal val scope: CoroutineScope) {
    // Only path text is read here; resolution, hashing, process launch and I/O are background work.
    private val root = project.basePath
    internal val controller = EngineController(scope, ProjectId("jetbrains-${project.locationHash}"), launchProcess = {
        val directory = Path.of(requireNotNull(root) { "Open a local project first" }).toRealPath()
        val executable = executable()
        ProcessBuilder(executable.toString(), "--project-root", directory.toString())
            .directory(directory.toFile())
            .apply {
                // Native adapter authentication stays outside Flow storage and logs.
                val inherited = environment().toMap()
                environment().clear()
                for (name in listOf("HOME", "PATH", "TMPDIR", "LANG", "LC_ALL", "TERM", "CODEX_HOME", "CODEX_SANDBOX", "CODEX_SANDBOX_NETWORK_DISABLED", "CODEX_API_KEY", "OPENAI_API_KEY", "SSL_CERT_FILE", "SSL_CERT_DIR", "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY", "__CF_USER_TEXT_ENCODING")) {
                    inherited[name]?.let { environment()[name] = it }
                }
            }.start()
    })

    private fun executable(): Path {
        val configured = System.getProperty("flow.engine.path")?.takeIf { it.isNotBlank() }
        val path = if (configured != null) {
            Path.of(configured)
        } else {
            val plugin = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.flow.workbench")))
            plugin.pluginPath.resolve("bin/flow-engine")
        }
        require(path.isAbsolute && Files.isRegularFile(path) && Files.isExecutable(path)) {
            "Configure an absolute Flow engine executable"
        }
        val resolved = path.toRealPath()
        // Bundled engines must ship a digest. Explicit development paths are user-selected.
        if (configured == null) {
            val expected = Files.readString(path.resolveSibling("flow-engine.sha256")).trim()
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(resolved).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Engine digest mismatch" }
        }
        return resolved
    }
}
