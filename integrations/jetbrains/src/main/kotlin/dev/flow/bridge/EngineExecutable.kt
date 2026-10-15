package dev.flow.bridge

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/** Fixed launch diagnostics only; never show raw adapter/process errors or paths. */
internal class EngineLaunchException(message: String) : IllegalStateException(message)

/** Called on the controller's IO dispatcher. IntelliJ owns plugin ZIP installation. */
internal object EngineExecutable {
    private const val TARGET = "aarch64-apple-darwin"
    fun resolve(
        pluginRoot: Path,
        pluginVersion: String,
        configured: String? = null,
        os: String = System.getProperty("os.name"),
        architecture: String = System.getProperty("os.arch"),
        osVersion: String = System.getProperty("os.version"),
    ): Path {
        if (!configured.isNullOrBlank()) {
            val path = runCatching { Path.of(configured) }.getOrNull()
            if (path == null || !path.isAbsolute || !Files.isRegularFile(path) || !Files.isExecutable(path)) {
                throw EngineLaunchException("The development engine override must be an absolute executable file. Rebuild the engine or remove flow.engine.path.")
            }
            return path.toRealPath()
        }
        if (os != "Mac OS X" || architecture.lowercase() !in setOf("aarch64", "arm64")) {
            throw EngineLaunchException("This Flow package supports macOS Apple Silicon only. Install a package validated for this platform.")
        }
        if ((osVersion.substringBefore('.').toIntOrNull() ?: 0) < 13) {
            throw EngineLaunchException("This Flow engine requires macOS 13 or later.")
        }
        try {
            require(pluginRoot.isAbsolute && pluginRoot.toRealPath() == pluginRoot.normalize())
            fun regular(relative: String): Path {
                var path = pluginRoot
                for (part in relative.split('/')) {
                    path = path.resolve(part)
                    require(!Files.isSymbolicLink(path))
                }
                require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                return path
            }
            val manifestPath = regular("distribution.json")
            require(Files.size(manifestPath) in 1..65536)
            val manifest = ProtocolJson.parse(Files.readString(manifestPath))
            require(manifest["schemaVersion"].asInt == 1 && manifest["target"].asString == TARGET)
            require(manifest["protocolVersion"].asInt == FLOW_PROTOCOL_VERSION)
            require(manifest["pluginVersion"].asString == pluginVersion && manifest["engineVersion"].asString == pluginVersion)
            val relative = "bin/$TARGET/flow-engine"
            require(manifest["enginePath"].asString == relative)
            val expected = manifest["sha256"].asString
            require(Regex("[a-f0-9]{64}").matches(expected))
            val executable = regular(relative)
            require(Files.size(executable) in 8..(128L * 1024 * 1024))
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(executable).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == expected)
            // ZIP installers may omit Unix mode bits. Restore only owner execute,
            // after verification, without broadening read/write permissions.
            if (!Files.isExecutable(executable)) {
                val permissions = Files.getPosixFilePermissions(executable, NOFOLLOW_LINKS).toMutableSet()
                permissions.add(PosixFilePermission.OWNER_EXECUTE)
                Files.setPosixFilePermissions(executable, permissions)
            }
            require(Files.isExecutable(executable))
            return executable
        } catch (_: Exception) {
            throw EngineLaunchException("The bundled Flow engine is missing, damaged, incompatible, or inaccessible. Reinstall a trusted matching Flow package.")
        }
    }
}
