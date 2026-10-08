// Where the build's plugins come from, declared here, so nothing on a developer's machine - an init script, a
// mirror - decides what it resolves. The libraries' repositories are in build.gradle.kts, beside the IntelliJ
// Platform's, which only the project-level plugin can declare.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "sokar-intellij"
