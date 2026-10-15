import org.gradle.api.artifacts.dsl.LockMode
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

// Formatting runs in a separate tool configuration, never in the plugin runtime.
val ktlint by configurations.creating

dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier("1.410")
    }
    testImplementation("junit:junit:4.13.2")
    ktlint("com.pinterest.ktlint:ktlint-cli:1.7.1")
}

kotlin {
    jvmToolchain(21)
}

sourceSets.test {
    resources.srcDir("../../tests/fixtures/protocol")
}

val buildProtocolTestEngine by tasks.registering(Exec::class) {
    group = "verification"
    description = "Build the Rust executable used by the Kotlin IPC contract test."
    workingDir = rootProject.file("../..")
    commandLine("cargo", "build", "-p", "engine", "--locked", "--target-dir", rootProject.file("../../target"))
}

val windowsHost = System.getProperty("os.name").startsWith("Windows")
val engineFileName = if (windowsHost) "flow-engine.exe" else "flow-engine"
val pythonExecutable = providers.environmentVariable("FLOW_PYTHON").getOrElse(if (windowsHost) "python" else "python3")
val nativeTarget = when {
    windowsHost && System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64") -> "x86_64-pc-windows-msvc"
    System.getProperty("os.name") == "Linux" && System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64") -> "x86_64-unknown-linux-gnu"
    System.getProperty("os.name") == "Mac OS X" && System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "aarch64-apple-darwin"
    System.getProperty("os.name") == "Mac OS X" && System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64") -> "x86_64-apple-darwin"
    else -> error("Unsupported native engine build host")
}

val stageNativeEngine by tasks.registering(Exec::class) {
    group = "build"
    description = "Build and stage the host release engine, manifest and dependency notices."
    workingDir = rootProject.file("../..")
    commandLine(pythonExecutable, "scripts/package_native_engine.py", "--version", project.version.toString())
    if (gradle.startParameter.isOffline) args("--offline")
    providers.environmentVariable("FLOW_NATIVE_SIGNING_IDENTITY").orNull?.let { args("--signing-identity", it) }
    // Let Cargo decide rebuilds; staging always rechecks the binary and regenerates notices.
}

tasks.withType<PrepareSandboxTask>().configureEach {
    dependsOn(stageNativeEngine)
    from(layout.buildDirectory.dir("native-engine")) {
        into(pluginName)
        filesMatching("bin/**/flow-engine") {
            permissions { unix("rwxr-xr-x") }
        }
    }
}

tasks.test {
    dependsOn(buildProtocolTestEngine)
    // Native executables and external fixtures must invalidate cached test runs.
    inputs.file(rootProject.file("../../target/debug/$engineFileName"))
    inputs.dir(layout.buildDirectory.dir("native-engine"))
    inputs.files(rootProject.file("../../tests/fixtures/engine/fake_engine.py"), rootProject.file("../../scripts/spikes/fixture_agent.py"))
    systemProperty("flow.test.nativePayload", layout.buildDirectory.dir("native-engine").get().asFile.absolutePath)
    systemProperty("flow.test.engine", rootProject.file("../../target/debug/$engineFileName").absolutePath)
    // Agent validation requires an absolute executable, not a PATH command.
    val pythonPath = providers.exec { commandLine(pythonExecutable, "-c", "import sys; print(sys.executable)") }.standardOutput.asText.get().trim()
    systemProperty("flow.test.python", pythonPath)
    systemProperty("flow.test.fakeEngine", rootProject.file("../../tests/fixtures/engine/fake_engine.py").absolutePath)
    systemProperty("flow.test.adapterFixture", rootProject.file("../../scripts/spikes/fixture_agent.py").absolutePath)
    val realCodex = providers.gradleProperty("flowRealCodex").orNull == "true"
    if (realCodex) {
        require(providers.gradleProperty("flowRealAllowOnce").orNull == "true") { "Real Codex acceptance requires -PflowRealAllowOnce=true" }
        filter.includeTestsMatching("dev.flow.ui.FirstVerticalSliceTest.testOptInRealCodexTwoEdits")
        systemProperty("flow.test.realAdapter", providers.environmentVariable("FLOW_B12_ADAPTER").get())
        systemProperty("flow.test.realSha256", providers.environmentVariable("FLOW_B12_ADAPTER_SHA256").get())
        systemProperty("flow.test.realModel", providers.environmentVariable("FLOW_B12_MODEL").get())
        systemProperty("flow.test.realAllowOnce", "true")
        systemProperty("flow.test.realEvidence", layout.buildDirectory.file("b12-real-native-evidence.json").get().asFile.absolutePath)
        systemProperty("flow.test.engine", layout.buildDirectory.file("native-engine/bin/$nativeTarget/$engineFileName").get().asFile.absolutePath)
        outputs.upToDateWhen { false } // Real calls occur only on explicit opt-in.
        doFirst { layout.buildDirectory.file("b12-real-native-evidence.json").get().asFile.delete() }
    } else {
        filter.excludeTestsMatching("dev.flow.ui.FirstVerticalSliceTest.testOptInRealCodexTwoEdits")
    }
    if (providers.gradleProperty("flowPerformance").orNull == "true") {
        systemProperty("flow.test.performanceOutput", layout.buildDirectory.file("b27-native").get().asFile.absolutePath)
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            // Verify only the exact platform used to compile this plugin.
            current()
        }
    }
    pluginConfiguration {
        name = "Flow"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "253"
            untilBuild = "253.*"
        }
    }
}

val kotlinSources = fileTree("src") { include("**/*.kt") } + files("build.gradle.kts", "settings.gradle.kts")

val ktlintCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Check Kotlin formatting without changing source files."
    classpath = ktlint
    mainClass = "com.pinterest.ktlint.Main"
    args("src/**/*.kt", "*.gradle.kts")
    inputs.files(kotlinSources)
    inputs.file(rootProject.file("../../.editorconfig"))
    mustRunAfter("ktlintFormat")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Apply the shared Kotlin formatting rules."
    classpath = ktlint
    mainClass = "com.pinterest.ktlint.Main"
    args("--format", "src/**/*.kt", "*.gradle.kts")
}

tasks.check {
    dependsOn(ktlintCheck)
}

tasks.runIde {
    dependsOn(buildProtocolTestEngine)
    providers.gradleProperty("flowAcceptanceDataRoot").orNull?.let { systemProperty("flow.engine.dataRoot", it) }
    if (providers.gradleProperty("flowBundledEngine").orNull != "true") {
        jvmArgs("-Dflow.engine.path=${rootProject.file("../../target/debug/$engineFileName").absolutePath}")
    }
    if (providers.gradleProperty("flowCompatibilitySpikes").orNull == "true") {
        jvmArgs("-Dflow.compatibility.spikes=true")
    }
}

tasks.buildPlugin {
    archiveClassifier.set(nativeTarget)
    filesMatching("**/bin/**/flow-engine") {
        permissions { unix("rwxr-xr-x") }
    }
}

val checkPackagedPlugin by tasks.registering(Exec::class) {
    group = "verification"
    description = "Install the built plugin ZIP into scratch storage and smoke its bundled engine."
    dependsOn(tasks.buildPlugin)
    workingDir = rootProject.file("../..")
    doFirst {
        commandLine(pythonExecutable, "scripts/check_packaged_plugin.py", "--plugin", tasks.buildPlugin.get().archiveFile.get().asFile.absolutePath)
    }
}

tasks.register<Exec>("checkPackagedAgentSession") {
    group = "verification"
    description = "Verify offline ACP edits, durable history and descendant cleanup using the installed release engine."
    dependsOn(tasks.buildPlugin)
    workingDir = rootProject.file("../..")
    doFirst {
        commandLine(pythonExecutable, "scripts/check_packaged_plugin.py", "--session-fixture", "--plugin", tasks.buildPlugin.get().archiveFile.get().asFile.absolutePath)
    }
}
