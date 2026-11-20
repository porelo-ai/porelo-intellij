---
name: intellij-build
description: porelo-intellij's Gradle build: why Gradle, the wrapper and build scripts, porelo-release and porelo-machines from Gradle, dependency verification metadata, the Maven-only shared rules that do not hold, changelog entries. Use before moving a version or the Gradle Wrapper, changing the build or writing a changelog entry.
---

# intellij-build

## The build

- **It is built with Gradle and the IntelliJ Platform Gradle Plugin, not Maven** - the exception the shared
  build-tool rule names: JetBrains supports building, testing, verifying and publishing a plugin only with Gradle,
  and Gradle does nothing here but build, check and publish the plugin.
  So the files that are not Java are Gradle's: `gradlew`, `gradlew.bat` and `gradle/wrapper/` as Gradle 9.8.1
  generated them (the jar matching Gradle's published checksum), and the build scripts `settings.gradle.kts` and
  `build.gradle.kts`, which Gradle reads only in Kotlin or Groovy.
- **`porelo-release` and `porelo-machines` run from Gradle as the Java programs they are**, from Central at
  `porelo-buildtools` in `gradle/libs.versions.toml`, where every version the build names is kept.
- **Gradle checks every plugin, library and IDE the build resolves against its SHA-256 in
  `gradle/verification-metadata.xml` before using it**, and fails naming the artifact that differs. Gradle never checks
  a snapshot, so Porelo's snapshots run unchecked until a release replaces them. Dependabot moves only the actions:
  a version in `gradle/libs.versions.toml` or the Gradle Wrapper is moved by hand, and the same commit renews the file
  from an empty Gradle home, with `rm gradle/verification-metadata.xml && ./gradlew -g <empty directory>
  --no-configuration-cache --no-build-cache --write-verification-metadata sha256 clean build verifyPlugin documents`.
  A Gradle home that already holds the downloads leaves out what it did not fetch again. Linux never fetches the
  IntelliJ IDEA the *Windows* job compiles against, `idea-<platformVersion>-win.zip`: its entry is added again by
  hand, with the SHA-256 JetBrains publishes beside the download, compared with the download itself.
- **Two shared rules are written for Maven alone and hold nothing here:**
  - *Every Porelo Maven repository takes `porelo-parent` as its parent*: Gradle has no parent pom. The versions
    `porelo-parent` pins that this build uses (JUnit, AssertJ, `porelo-buildtools`) are named again in
    `gradle/libs.versions.toml`, with the same values.
  - *`porelo-release check-readmes`* checks a README beside every `pom.xml`; there is none here, so it does not run.
- **A changelog entry is one YAML file under `changelog/unreleased/`**: one plain line `title:`, a single sentence
  without quotes or block marks, and one line `type:` of `added`, `changed`, `deprecated`, `removed`, `fixed`,
  `security`, `dependency_update` or `other`. The build writes the version's entries into the plugin's What's New
  and fails on an entry of any other shape, and on a release whose `changelog/v<version>` holds none.
