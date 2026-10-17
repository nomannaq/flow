package dev.flow.bridge

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class EngineService(private val project: Project, internal val scope: CoroutineScope) : Disposable {
    override fun dispose() = Unit // The platform cancels the service coroutine scope.

    // Only path text is read here; resolution, hashing, process launch and I/O are background work.
    private val root = project.basePath
    internal val controller = EngineController(scope, ProjectId("jetbrains-${project.locationHash}"), launchProcess = {
        if (!TrustedProjects.isProjectTrusted(project)) throw EngineLaunchException("Trust this project before starting Flow.")
        val directory = Path.of(requireNotNull(root) { "Open a local project first" }).toRealPath()
        val plugin = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.flow.workbench")))
        val executable = EngineExecutable.resolve(plugin.pluginPath, requireNotNull(plugin.version), System.getProperty("flow.engine.path"))
        val command = mutableListOf(executable.toString(), "--project-root", directory.toString())
        // Explicit development/acceptance storage, shared across IDE restarts.
        // The engine validates its location and still scopes it to this project.
        System.getProperty("flow.engine.dataRoot")?.let { value ->
            val data = Path.of(value)
            require(data.isAbsolute) { "Acceptance data root must be absolute" }
            command.addAll(listOf("--data-root", data.toRealPath().toString()))
        }
        ProcessBuilder(command)
            .directory(directory.toFile())
            .apply {
                // Native adapter authentication stays outside Flow storage and logs.
                environment().clear()
                environment().putAll(LocalAgents.launchEnvironment())
            }.let { builder ->
                try {
                    builder.start()
                } catch (_: java.io.IOException) {
                    throw EngineLaunchException("Flow could not start its verified engine. Check OS security approval, executable permissions and system requirements, then retry. See the native packaging guide.")
                }
            }
    })
}
