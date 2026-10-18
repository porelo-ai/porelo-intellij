# porelo-intellij

<img src="doc/images/early-bird.svg" width="350" alt="Early bird - work in progress">

> **Early bird - work in progress.** Porelo is not stable yet: until release 1.0.0, its code, commands
> and file formats can change without notice.

The Porelo plugin for IntelliJ IDEA: a developer reviews an agent's waiting work in the IDE - sees what waits,
reads its diff, and approves or rejects it - without checking anything out, building it or running it.

**Documentation: [porelo-ai.github.io/intellij](https://porelo-ai.github.io/intellij/)**, one chapter of
[all of Porelo's documentation](https://porelo-ai.github.io).

## Build

Java 25 (the pinned GraalVM); `gradlew` brings the pinned Gradle, and Gradle the IntelliJ IDEA it builds against.

    ./gradlew build          # compiled, tested, and the shared checks on the documents
    ./gradlew verifyPlugin   # the plugin verifier against every supported IntelliJ IDEA
    ./gradlew documents      # a change to documents only: the checks and the tests that read a document

How to contribute is in [CONTRIBUTING.md](CONTRIBUTING.md), and how to report a vulnerability in
[SECURITY.md](SECURITY.md).
