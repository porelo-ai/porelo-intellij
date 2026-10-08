import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.logchange)
    alias(libs.plugins.errorprone)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    // Sokar's own artifacts are built against while they are developed; this serves those snapshots and nothing else.
    exclusiveContent {
        forRepository {
            maven("https://central.sonatype.com/repository/maven-snapshots/") {
                mavenContent { snapshotsOnly() }
            }
        }
        filter { includeVersionByRegex("org\\.fuin\\.sokar", ".*", ".*-SNAPSHOT") }
    }
    intellijPlatform {
        defaultRepositories()
    }
}

// sokar-release and sokar-machines from Central, run as Java programs: the checks every Sokar repository runs.
val sokarTools: Configuration = configurations.create("sokarTools")

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // The change tree a review shows, as IntelliJ's own comparison with a branch shows one.
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.impl.shared")
        pluginVerifier(libs.versions.plugin.verifier)
    }
    // The daemon's contract is spoken with the client that defines it, bundled with the plugin.
    implementation(libs.sokar.wire)
    compileOnly(libs.jspecify)
    errorprone(libs.errorprone)
    errorprone(libs.nullaway)
    sokarTools(libs.sokar.release)
    sokarTools(libs.sokar.machines)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            providers.gradleProperty("verifyIdeVersions").get().split(',').forEach {
                create(IntelliJPlatformType.IntellijIdea, it.trim())
            }
        }
    }
}

// NullAway makes a broken @NullMarked contract a compile error, in main code only: tests pass null on purpose.
tasks.compileJava {
    options.errorprone {
        disableAllChecks = true
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:OnlyNullMarked", "true")
    }
}

tasks.compileTestJava {
    options.errorprone.enabled = false
}

tasks.test {
    useJUnitPlatform {
        excludeTags("live")
    }
}

// Against a real daemon on a machine somebody keeps, named in the environment; never part of the build.
tasks.register<Test>("liveTest") {
    description = "Runs the tests tagged live against the machine SOKAR_LIVE_HOST names."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("live")
    }
    outputs.upToDateWhen { false }
}

// A change to documents or issues runs only these: the tests that read a document, which carry the tag.
val documentsTest = tasks.register<Test>("documentsTest") {
    description = "Runs the tests tagged documents."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("documents")
    }
}

fun sokarRelease(name: String, what: String, vararg arguments: String) = tasks.register<JavaExec>(name) {
    description = what
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = sokarTools
    mainClass = "org.fuin.sokar.release.Main"
    args = arguments.toList()
    workingDir = layout.projectDirectory.asFile
}

val checkActions = sokarRelease("checkActions", "Every workflow step pinned by commit.",
    "check-actions", ".github")
val checkShared = sokarRelease("checkShared",
    "The shared block of AGENTS.md carries the hash of its text, so a copy edited on its own fails.",
    "check-shared", "AGENTS.md")
val checkCitations = sokarRelease("checkCitations",
    "No file cites an issue in a way that goes stale once the issue is finished and deleted.",
    "check-citations", ".")
val checkDocSite = sokarRelease("checkDocSite",
    "Every page of doc/ is in mkdocs.yml's navigation once, and every link on it can be followed.",
    "check-doc-site", "doc", "mkdocs.yml")

// What the 'Shared rules check' workflow runs: the checks on the documents, and the tests that read one.
tasks.register("documents") {
    description = "Runs the shared checks on the documents and the tests tagged documents."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(checkActions, checkShared, checkCitations, checkDocSite, "logchangeLint", documentsTest)
}

tasks.check {
    dependsOn(checkActions, checkShared, checkCitations, checkDocSite, "logchangeLint")
}

// Any sokar-machines command, such as 'jdk --github', which installs the pinned GraalVM in a CI job.
tasks.register<JavaExec>("machines") {
    description = "Runs sokar-machines with the arguments given as -Pmachines.args."
    classpath = sokarTools
    mainClass = "org.fuin.sokar.machines.Main"
    args = providers.gradleProperty("machines.args").orElse("").get().split(' ').filter { it.isNotBlank() }
}
