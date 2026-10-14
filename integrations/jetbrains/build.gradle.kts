import org.gradle.api.artifacts.dsl.LockMode
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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

tasks.test {
    dependsOn(buildProtocolTestEngine)
    systemProperty("flow.test.engine", rootProject.file("../../target/debug/flow-engine").absolutePath)
    systemProperty("flow.test.fakeEngine", rootProject.file("../../tests/fixtures/engine/fake_engine.py").absolutePath)
    systemProperty("flow.test.adapterFixture", rootProject.file("../../scripts/spikes/fixture_agent.py").absolutePath)
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
    jvmArgs("-Dflow.engine.path=${rootProject.file("../../target/debug/flow-engine").absolutePath}")
    if (providers.gradleProperty("flowCompatibilitySpikes").orNull == "true") {
        jvmArgs("-Dflow.compatibility.spikes=true")
    }
}
