package dev.flow.bridge

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class EngineExecutableTest {
    private fun resolve(root: Path, version: String = "0.1.0") = EngineExecutable.resolve(root, version, os = "Mac OS X", architecture = "aarch64", osVersion = "13.0")

    @Test
    fun verifiesIdentityAndRestoresOnlyOwnerExecute() {
        val root = Files.createTempDirectory("Flow installed with spaces ").toRealPath()
        try {
            assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"))
            val binary = Files.createDirectories(root.resolve("bin/aarch64-apple-darwin")).resolve("flow-engine")
            Files.writeString(binary, "fixture executable bytes")
            Files.setPosixFilePermissions(binary, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            val manifest = manifest(binary)
            Files.writeString(root.resolve("distribution.json"), manifest.toString())
            assertEquals(binary, resolve(root))
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(binary))
            for ((field, value) in listOf("enginePath" to "../../other", "engineVersion" to "9", "target" to "x86_64-apple-darwin", "sha256" to "bad")) {
                Files.writeString(root.resolve("distribution.json"), manifest.deepCopy().apply { addProperty(field, value) }.toString())
                assertThrows(EngineLaunchException::class.java) { resolve(root) }
            }
            Files.writeString(root.resolve("distribution.json"), manifest.toString())
            assertThrows(EngineLaunchException::class.java) { resolve(root, "9") }
            Files.writeString(binary, "tampered executable bytes")
            assertThrows(EngineLaunchException::class.java) { resolve(root) }
            Files.delete(binary)
            Files.createSymbolicLink(binary, root.resolve("distribution.json"))
            assertThrows(EngineLaunchException::class.java) { resolve(root) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unsupportedPlatformAndMissingPayloadHaveFixedDiagnostics() {
        val root = Files.createTempDirectory("flow-unsupported-").toRealPath()
        try {
            assertTrue(assertThrows(EngineLaunchException::class.java) { EngineExecutable.resolve(root, "0.1.0", os = "Linux", architecture = "aarch64") }.message!!.contains("Apple Silicon"))
            assertTrue(assertThrows(EngineLaunchException::class.java) { EngineExecutable.resolve(root, "0.1.0", os = "Windows 11", architecture = "x86") }.message!!.contains("x86-64"))
            assertTrue(assertThrows(EngineLaunchException::class.java) { EngineExecutable.resolve(root, "0.1.0", os = "Mac OS X", architecture = "aarch64", osVersion = "12.7") }.message!!.contains("macOS 13"))
            assertTrue(assertThrows(EngineLaunchException::class.java) { EngineExecutable.resolve(root, "0.1.0", os = "Windows 7", architecture = "amd64", osVersion = "6.1") }.message!!.contains("Windows 10"))
            assertTrue(assertThrows(EngineLaunchException::class.java) { resolve(root) }.message!!.contains("Reinstall"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun resolvesEveryPlatformAndRejectsWrongPackageAndTampering() {
        for ((os, arch, target) in listOf(
            Triple("Mac OS X", "arm64", "aarch64-apple-darwin"),
            Triple("Mac OS X", "x86_64", "x86_64-apple-darwin"),
            Triple("Linux", "amd64", "x86_64-unknown-linux-gnu"),
            Triple("Windows 11", "amd64", "x86_64-pc-windows-msvc"),
        )) {
            val root = Files.createTempDirectory("Flow platform ").toRealPath()
            try {
                val relative = "bin/$target/flow-engine${if (os.startsWith("Windows")) ".exe" else ""}"
                val binary = root.resolve(relative)
                Files.createDirectories(binary.parent)
                Files.writeString(binary, "fixture executable bytes")
                // These are fixture bytes, never launched. Windows ACLs decide executability.
                if (Files.getFileStore(root).supportsFileAttributeView("posix")) binary.toFile().setExecutable(true, true)
                val manifest = manifest(binary).apply {
                    addProperty("target", target)
                    addProperty("enginePath", relative)
                }
                Files.writeString(root.resolve("distribution.json"), manifest.toString())
                assertEquals(binary, EngineExecutable.resolve(root, "0.1.0", os = os, architecture = arch, osVersion = "13.0"))
                Files.writeString(root.resolve("distribution.json"), manifest.deepCopy().apply { addProperty("target", "wrong-platform") }.toString())
                assertTrue(assertThrows(EngineLaunchException::class.java) { EngineExecutable.resolve(root, "0.1.0", os = os, architecture = arch, osVersion = "13.0") }.message!!.contains(target))
                Files.writeString(root.resolve("distribution.json"), manifest.toString())
                Files.writeString(binary, "corrupted fixture bytes")
                assertThrows(EngineLaunchException::class.java) { EngineExecutable.resolve(root, "0.1.0", os = os, architecture = arch, osVersion = "13.0") }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun integrityFailureStopsRetriesUntilExplicitRestart() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val attempts = AtomicInteger()
        val controller = EngineController(scope, ProjectId("launch-failure"), launchProcess = {
            attempts.incrementAndGet()
            throw EngineLaunchException("Reinstall the trusted package.")
        })
        try {
            controller.start()
            withTimeout(5000) { controller.view.first { it.status == "Disconnected. Reinstall the trusted package." } }
            assertEquals(1, attempts.get())
            assertFalse(controller.view.value.connected)
            controller.restart()
            withTimeout(5000) { controller.view.first { attempts.get() == 2 && it.status == "Disconnected. Reinstall the trusted package." } }
        } finally {
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        }
    }

    @Test
    fun realBundledEngineHealthRestartAndShutdownOutsideCheckout() = runBlocking {
        val payload = Path.of(requireNotNull(System.getProperty("flow.test.nativePayload")))
        val root = Files.createTempDirectory("Flow packaged bridge ").toRealPath()
        try {
            val installed = root.resolve("Installed plugin with spaces")
            Files.walk(payload).use { paths ->
                paths.forEach { source ->
                    val target = installed.resolve(payload.relativize(source))
                    if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
                }
            }
            val binary = EngineExecutable.resolve(installed, "0.1.0")
            assertTrue(binary.startsWith(root))
            val project = Files.createDirectory(root.resolve("Project with spaces"))
            val data = Files.createDirectory(root.resolve("Private data"))
            val sentinel = project.resolve("sentinel.txt")
            Files.writeString(sentinel, "developer content")
            repeat(2) {
                coroutineScope {
                    val process = ProcessBuilder(binary.toString(), "--project-root", project.toString(), "--data-root", data.toString()).directory(project.toFile()).start()
                    val connection = EngineConnection(process, this)
                    try {
                        assertTrue(connection.initialize(ProjectId("packaged-test")).capabilities.persistentEvents)
                        assertEquals("0.1.0", ProtocolJson.health(connection.request("engine.health").asJsonObject).engineVersion)
                        assertTrue(connection.request("sessions.active").asJsonObject["sessionId"].isJsonNull)
                    } finally {
                        connection.close()
                    }
                    assertFalse(process.isAlive)
                    assertEquals(0, process.exitValue())
                }
            }
            assertEquals("developer content", Files.readString(sentinel))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun manifest(binary: Path) = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("protocolVersion", FLOW_PROTOCOL_VERSION)
        addProperty("target", "aarch64-apple-darwin")
        addProperty("pluginVersion", "0.1.0")
        addProperty("engineVersion", "0.1.0")
        addProperty("enginePath", "bin/aarch64-apple-darwin/flow-engine")
        addProperty("sha256", MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)).joinToString("") { "%02x".format(it) })
    }
}
