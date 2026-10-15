package dev.flow.bridge

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class EngineService(private val project: Project, internal val scope: CoroutineScope) {
    // Only path text is read here; resolution, hashing, process launch and I/O are background work.
    private val root = project.basePath
    internal val controller = EngineController(scope, ProjectId("jetbrains-${project.locationHash}"), launchProcess = {
        val directory = Path.of(requireNotNull(root) { "Open a local project first" }).toRealPath()
        val plugin = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.flow.workbench")))
        val executable = EngineExecutable.resolve(plugin.pluginPath, requireNotNull(plugin.version), System.getProperty("flow.engine.path"))
        ProcessBuilder(executable.toString(), "--project-root", directory.toString())
            .directory(directory.toFile())
            .apply {
                // Native adapter authentication stays outside Flow storage and logs.
                val inherited = environment().toMap()
                environment().clear()
                for (name in listOf("HOME", "PATH", "TMPDIR", "XDG_DATA_HOME", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATHEXT", "COMSPEC", "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMDATA", "LANG", "LC_ALL", "TERM", "CODEX_HOME", "CODEX_SANDBOX", "CODEX_SANDBOX_NETWORK_DISABLED", "CODEX_API_KEY", "OPENAI_API_KEY", "SSL_CERT_FILE", "SSL_CERT_DIR", "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY", "__CF_USER_TEXT_ENCODING")) {
                    inherited.entries.firstOrNull { it.key.equals(name, ignoreCase = System.getProperty("os.name").startsWith("Windows")) }?.let { environment()[it.key] = it.value }
                }
            }.let { builder ->
                try {
                    builder.start()
                } catch (_: java.io.IOException) {
                    throw EngineLaunchException("Flow could not start its verified engine. Check OS security approval, executable permissions and system requirements, then retry. See the native packaging guide.")
                }
            }
    })
}
