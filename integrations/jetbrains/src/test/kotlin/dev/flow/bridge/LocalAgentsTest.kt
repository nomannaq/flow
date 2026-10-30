package dev.flow.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class LocalAgentsTest {
    @Test
    fun nativePrimaryLaunchersAndLazyCacheFallbackBothResolve() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("flow mixed native discovery ")
        try {
            val bin = Files.createDirectories(root.resolve("bin"))
            val codex = Files.writeString(bin.resolve("codex"), "fixture").apply { toFile().setExecutable(true) }
            val cache = root.resolve("ide")
            val cachedBin = Files.createDirectories(cache.resolve("flow/adapters/npm-cache/_npx/entry/node_modules/.bin"))
            val node = Files.writeString(cachedBin.resolve("node"), "fixture").apply { toFile().setExecutable(true) }
            val claude = Files.writeString(cachedBin.parent.resolve("claude.mjs"), "fixture").apply { toFile().setExecutable(true) }
            Files.createSymbolicLink(cachedBin.resolve("claude"), claude)
            val agents = LocalAgents.discover(emptyMap(), cache, root.resolve("home"), listOf(bin), windowsHost = false, nativeCli = true)
            assertEquals(codex.toRealPath().toString(), agents.single { it.profile == "codex" }.executable)
            assertEquals(node.toString(), agents.single { it.profile == "claude" }.executable)
            assertEquals(listOf(claude.toRealPath().toString()), agents.single { it.profile == "claude" }.arguments)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachedPinnedAdaptersLaunchDirectlyWithoutNpmAndRejectWrongVersion() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("flow cached adapter ")
        try {
            val bin = Files.createDirectories(root.resolve("bin"))
            val node = Files.writeString(bin.resolve("node"), "fixture").apply { toFile().setExecutable(true) }
            val cache = root.resolve("ide")
            val modules = cache.resolve("flow/adapters/npm-cache/_npx/cached/node_modules")
            val pkg = Files.createDirectories(modules.resolve("@agentclientprotocol/claude-agent-acp"))
            val entry = Files.writeString(Files.createDirectories(pkg.resolve("dist")).resolve("index.js"), "fixture")
            Files.writeString(pkg.resolve("package.json"), """{"name":"@agentclientprotocol/claude-agent-acp","version":"0.93.0"}""")
            Files.createDirectories(modules.resolve(".bin"))
            Files.createSymbolicLink(modules.resolve(".bin/claude-agent-acp"), entry)
            val agents = LocalAgents.discover(emptyMap(), cache, root.resolve("home"), listOf(bin), windowsHost = false)
            val adapter = agents.single()
            assertEquals("claude", adapter.profile)
            assertEquals(node.toString(), adapter.executable)
            assertEquals(listOf(entry.toRealPath().toString()), adapter.arguments)
            assertFalse(adapter.arguments.any { Path.of(it).fileName.toString() == "npx-cli.js" || it.startsWith("--package=") })
            Files.writeString(pkg.resolve("package.json"), """{"name":"@agentclientprotocol/claude-agent-acp","version":"0.1.0"}""")
            assertTrue(LocalAgents.discover(emptyMap(), cache, root.resolve("home"), listOf(bin), windowsHost = false).isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cursorUsesTheInstalledAgentAcpModeWithoutAnNpmAdapter() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("flow cursor discovery ")
        try {
            val bin = Files.createDirectories(root.resolve("bin"))
            val cursor = Files.writeString(bin.resolve("agent"), "fixture").apply { toFile().setExecutable(true) }
            Files.writeString(bin.resolve("node"), "fixture").toFile().setExecutable(true)
            val npx = Files.writeString(bin.resolve("npx-cli.js"), "fixture").apply { toFile().setExecutable(true) }
            Files.createSymbolicLink(bin.resolve("npx"), npx)
            val agents = LocalAgents.discover(emptyMap(), root.resolve("cache"), root.resolve("home"), listOf(bin), windowsHost = false)
            assertEquals(3, agents.size)
            val adapter = agents.first()
            assertTrue(agents.drop(1).all { it.arguments.any { arg -> arg.startsWith("--package=") } })
            assertEquals("cursor", adapter.profile)
            assertEquals(cursor.toRealPath().toString(), adapter.executable)
            assertEquals(listOf("acp"), adapter.arguments)
            assertTrue(LocalAgents.discover(emptyMap(), root.resolve("cache"), root.resolve("home"), listOf(bin), windowsHost = false, nativeCli = true).isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun detectsExistingZedAdapterWithoutNodeOrDownloads() {
        val root = Files.createTempDirectory("flow zed discovery ")
        try {
            val home = Files.createDirectories(root.resolve("home"))
            val adapter = home.resolve("Library/Application Support/Zed/external_agents/codex/v0.5.2/codex-acp")
            Files.createDirectories(adapter.parent)
            Files.writeString(adapter, "fixture")
            adapter.toFile().setExecutable(true)
            val agents = LocalAgents.discover(emptyMap(), root.resolve("ide"), home, emptyList(), windowsHost = false)
            assertEquals(adapter.toRealPath().toString(), agents.single().executable)
            assertTrue(agents.single().arguments.isEmpty())
            assertFalse(Files.exists(root.resolve("ide")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun nativeWindowsCliUsesItsOwnEntryPointAndNeverFallsBackToAnAdapterDownload() {
        val root = Files.createTempDirectory("flow native discovery ")
        try {
            val bin = Files.createDirectories(root.resolve("bin"))
            val node = Files.writeString(bin.resolve("node.exe"), "fixture")
            Files.writeString(bin.resolve("codex.cmd"), "fixture shim")
            val entry = bin.resolve("node_modules/@openai/codex/bin/codex.js")
            Files.createDirectories(entry.parent)
            Files.writeString(entry, "fixture")
            val agents = LocalAgents.discover(emptyMap(), root.resolve("ide"), root.resolve("home"), listOf(bin), windowsHost = true, nativeCli = true)
            assertEquals(1, agents.size)
            assertEquals(node.toString(), agents.single().executable)
            assertEquals(listOf(entry.toString()), agents.single().arguments)
            assertEquals("codex", agents.single().profile)
            assertFalse(Files.exists(root.resolve("ide")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun resolvesScriptAdaptersAndPinsFallbackWithoutLaunchingAProcess() {
        val root = Files.createTempDirectory("flow agent discovery ")
        try {
            val bin = Files.createDirectories(root.resolve("terminal bin"))
            fun file(path: Path): Path = path.apply {
                Files.createDirectories(parent)
                Files.writeString(this, "fixture")
                toFile().setExecutable(true)
            }
            val windows = System.getProperty("os.name").startsWith("Windows")
            val node = file(bin.resolve(if (windows) "node.exe" else "node"))
            val codex: Path
            if (windows) {
                file(bin.resolve("node_modules/npm/bin/npx-cli.js"))
                file(bin.resolve("npx.cmd"))
                codex = file(bin.resolve("node_modules/@agentclientprotocol/codex-acp/dist/index.js"))
                file(bin.resolve("codex-acp.cmd"))
            } else {
                val npx = file(root.resolve("npm/bin/npx-cli.js"))
                Files.createSymbolicLink(bin.resolve("npx"), npx)
                codex = file(root.resolve("codex/dist/index.js"))
                Files.createSymbolicLink(bin.resolve("codex-acp"), codex)
            }
            val agents = LocalAgents.discover(emptyMap(), root.resolve("ide"), root.resolve("home"), listOf(bin), windowsHost = windows)
            val installed = agents.single { it.profile == "codex" }
            assertEquals(node.toString(), installed.executable)
            assertEquals(listOf(if (windows) codex.toString() else codex.toRealPath().toString()), installed.arguments)
            val managed = agents.single { it.profile == "claude" }
            assertEquals(node.toString(), managed.executable)
            assertTrue(managed.arguments.contains("--package=@agentclientprotocol/claude-agent-acp@0.93.0"))
            assertTrue(managed.arguments.contains("--ignore-scripts"))
            assertTrue(managed.arguments.contains("--registry=https://registry.npmjs.org"))
            assertFalse("Detection must not install or create caches", Files.exists(root.resolve("ide")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun windowsNpmShimsResolveToNodeWithoutCmdOrShellParsing() {
        val root = Files.createTempDirectory("flow Windows discovery ")
        try {
            val bin = Files.createDirectories(root.resolve("node modules/.bin"))
            val node = Files.writeString(bin.resolve("node.exe"), "fixture")
            Files.writeString(bin.resolve("claude-agent-acp.cmd"), "fixture shim")
            val entry = bin.resolve("../@agentclientprotocol/claude-agent-acp/dist/index.js").normalize()
            Files.createDirectories(entry.parent)
            Files.writeString(entry, "fixture")
            val agents = LocalAgents.discover(emptyMap(), root.resolve("ide"), root.resolve("home"), listOf(bin), windowsHost = true)
            assertEquals(node.toString(), agents.single().executable)
            assertEquals(listOf(entry.toString()), agents.single().arguments)
            assertEquals("claude", agents.single().profile)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
