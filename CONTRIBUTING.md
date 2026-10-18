# Contributing

Changes to this repository follow the rules every Porelo repository shares, explained in
[Contributing to Porelo](https://porelo-ai.github.io/porelo-project/contributing/). This file names only what is
particular to the IntelliJ plugin.

## In this repository

- **It is built with Gradle, not Maven**, on Java 25: the pinned GraalVM is `JAVA_HOME`, and Gradle never downloads
  a JDK. The commands are in the README's [Build](README.md#build) section; a change is ready when `./gradlew build`
  and `./gradlew verifyPlugin` have passed, and a change to documents alone when `./gradlew documents` has.
- **Every dependency is checked against `gradle/verification-metadata.xml`.** A version moved in
  `gradle/libs.versions.toml` or in the Gradle Wrapper renews that file in the same commit, written from an empty
  Gradle home with the command `AGENTS.md` names.
- **The tests tagged `live` need a machine running Porelo's daemon** with a task that pushed, named in the
  environment as each test's comment says. They run as `./gradlew liveTest`, never in the build, and only a change
  to how the plugin speaks to a daemon needs them.
