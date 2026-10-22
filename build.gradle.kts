import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.logchange)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotless)
}

group = providers.gradleProperty("pluginGroup").get()
// An IDE offers a plugin from a custom repository only when its version is newer than the one installed, so every
// snapshot the Build publishes carries the Build's run number, -PsnapshotStamp, after the snapshot version.
version = providers.gradleProperty("pluginVersion").get().let { line ->
    val stamp = providers.gradleProperty("snapshotStamp").orNull
    when {
        stamp == null -> line
        !line.endsWith("-SNAPSHOT") -> throw GradleException("-PsnapshotStamp=$stamp is for a snapshot, and $line is none")
        !stamp.matches(Regex("[0-9]+")) -> throw GradleException("-PsnapshotStamp=$stamp is no run number")
        else -> "$line.$stamp"
    }
}

repositories {
    mavenCentral()
    // Porelo's own artifacts are built against while they are developed; this serves those snapshots and nothing else.
    // -PporeloSnapshots names another such repository, as a handover's local Maven repository is before it is published.
    exclusiveContent {
        forRepository {
            maven(providers.gradleProperty("poreloSnapshots").orElse("https://central.sonatype.com/repository/maven-snapshots/")) {
                mavenContent { snapshotsOnly() }
            }
        }
        filter { includeVersionByRegex("org\\.fuin\\.porelo", ".*", ".*-SNAPSHOT") }
    }
    intellijPlatform {
        defaultRepositories()
    }
}

// porelo-release and porelo-machines from Central, run as Java programs: the checks every Porelo repository runs.
val poreloTools: Configuration = configurations.create("poreloTools")

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // The change tree a review shows, as IntelliJ's own comparison with a branch shows one.
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.impl.shared")
        // The Git log a push fetched into a project is shown in.
        bundledModule("intellij.platform.vcs.log")
        bundledModule("intellij.platform.vcs.log.impl")
        pluginVerifier(libs.versions.plugin.verifier)
    }
    // The daemon's contract is spoken with the client that defines it, bundled with the plugin.
    implementation(libs.porelo.wire)
    compileOnly(libs.jspecify)
    errorprone(libs.errorprone)
    errorprone(libs.nullaway)
    poreloTools(libs.porelo.release)
    poreloTools(libs.porelo.machines)
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

// Google Java Style in its AOSP variant, as every Porelo repository's build checks it; `check` runs spotlessCheck.
spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get()).aosp()
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

// The tests tagged documents read the repository's files as they are; Gradle knows only the test classes as inputs,
// so without these a changed document would leave the result of the run before standing, or take it from the cache.
val readDocuments = fileTree(layout.projectDirectory) {
    exclude("build/**", ".gradle/**", ".git/**", ".idea/**", ".kotlin/**")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("live")
    }
    inputs.files(readDocuments).withPropertyName("documents").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Against a real daemon on a machine somebody keeps, named in the environment; never part of the build.
tasks.register<Test>("liveTest") {
    description = "Runs the tests tagged live against the machine PORELO_LIVE_HOST names."
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
    inputs.files(readDocuments).withPropertyName("documents").withPathSensitivity(PathSensitivity.RELATIVE)
}

fun poreloRelease(name: String, what: String, vararg arguments: String) = tasks.register<JavaExec>(name) {
    description = what
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = poreloTools
    mainClass = "org.fuin.porelo.release.Main"
    args = arguments.toList()
    workingDir = layout.projectDirectory.asFile
}

val checkActions = poreloRelease("checkActions", "Every workflow step pinned by commit.",
    "check-actions", ".github")
val checkShared = poreloRelease("checkShared",
    "The shared block of AGENTS.md carries the hash of its text, and .editorconfig is the shared one, so a copy edited on its own fails.",
    "check-shared", "AGENTS.md", ".editorconfig")
val checkCitations = poreloRelease("checkCitations",
    "No file cites an issue in a way that goes stale once the issue is finished and deleted.",
    "check-citations", ".")
val checkDocSite = poreloRelease("checkDocSite",
    "Every page of doc/ is in mkdocs.yml's navigation once, and every link on it can be followed.",
    "check-doc-site", "doc", "mkdocs.yml")

// The custom plugin repository a person adds in the IDE: updatePlugins.xml names the zip by its published URL, under
// the channel its version belongs to, in Artifactory's generic repository that the Publish job uploads both into.
val updatePluginsXml = tasks.register("updatePluginsXml") {
    description = "Writes the custom plugin repository's updatePlugins.xml for the plugin's zip."
    group = "intellij platform"
    val distribution = "https://fuinorg.jfrog.io/artifactory/porelo-dist-intellij"
    val zip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val pluginXml = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml")
    val since = providers.gradleProperty("pluginSinceBuild")
    val pluginVersion = version.toString()
    val output = layout.buildDirectory.file("distributions/updatePlugins.xml")
    inputs.file(zip)
    inputs.file(pluginXml)
    inputs.property("since", since)
    inputs.property("version", pluginVersion)
    outputs.file(output)
    doLast {
        val descriptor = pluginXml.asFile.readText()
        val id = Regex("<id>([^<]+)</id>").find(descriptor)?.groupValues?.get(1)
            ?: throw GradleException("plugin.xml names no <id>")
        val name = Regex("<name>([^<]+)</name>").find(descriptor)?.groupValues?.get(1)
            ?: throw GradleException("plugin.xml names no <name>")
        // Each value goes into XML and a URL as it is, so only what needs no escaping in either is taken.
        val plain = Regex("[A-Za-z0-9._+-]+")
        listOf("id" to id, "version" to pluginVersion, "since-build" to since.get(), "zip" to zip.get().asFile.name)
            .forEach { (what, value) ->
                if (!value.matches(plain)) throw GradleException("The plugin's $what '$value' is not plain enough for a URL")
            }
        val channel = if (pluginVersion.contains("-SNAPSHOT")) "snapshots" else "releases"
        val url = "$distribution/$channel/$pluginVersion/${zip.get().asFile.name}"
        output.get().asFile.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<plugins>
            |  <plugin id="$id" url="$url" version="$pluginVersion">
            |    <name>$name</name>
            |    <idea-version since-build="${since.get()}"/>
            |  </plugin>
            |</plugins>
            |""".trimMargin()
        )
    }
}

tasks.test {
    // The test of the published descriptor reads the one this build writes, so a changed one runs it again rather
    // than taking its result from the cache.
    inputs.files(updatePluginsXml).withPropertyName("updatePlugins")
    systemProperty("porelo.updatePlugins", updatePluginsXml.get().outputs.files.singleFile.absolutePath)
    systemProperty("porelo.version", version.toString())
    systemProperty("porelo.zip", tasks.named<Zip>("buildPlugin").get().archiveFile.get().asFile.absolutePath)
}

// What the 'Shared rules check' workflow runs: the checks on the documents, and the tests that read one.
tasks.register("documents") {
    description = "Runs the shared checks on the documents and the tests tagged documents."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(checkActions, checkShared, checkCitations, checkDocSite, "logchangeLint", documentsTest)
}

tasks.check {
    dependsOn(checkActions, checkShared, checkCitations, checkDocSite, "logchangeLint")
}

// Any porelo-release command, such as 'check-commits', which a pull request's commit messages are held to.
tasks.register<JavaExec>("release") {
    description = "Runs porelo-release with the arguments given as -Prelease.args."
    classpath = poreloTools
    mainClass = "org.fuin.porelo.release.Main"
    args = providers.gradleProperty("release.args").orElse("").get().split(' ').filter { it.isNotBlank() }
    workingDir = layout.projectDirectory.asFile
}

// Any porelo-machines command, such as 'jdk --github', which installs the pinned GraalVM in a CI job.
tasks.register<JavaExec>("machines") {
    description = "Runs porelo-machines with the arguments given as -Pmachines.args."
    classpath = poreloTools
    mainClass = "org.fuin.porelo.machines.Main"
    args = providers.gradleProperty("machines.args").orElse("").get().split(' ').filter { it.isNotBlank() }
}
