# IJ09 — The build verifies every dependency

**Status:** Implementing; blocked by `porelo-buildtools` 0.5.1, released with the close of release 0.6. Until then the
file is renewed whenever the snapshot's dependencies move.
**Size:** M - one generated file, Dependabot reduced to the actions, the way a version is moved and the file renewed,
and a check that a tampered entry fails; no code of the plugin changes.

**What must be true.** Every plugin, library and tool the Gradle build resolves is checked against the digest its
source names before it is used, as `AGENTS.md` asks of every download. A dependency whose bytes differ from the ones
recorded fails the build, naming it. Today only the Gradle Wrapper is checked, by its `distributionSha256Sum` and the
wrapper's validation, while `porelo-release` and `porelo-machines` run as programs in the Build with the
checkout's token at hand.

## Plan

- **Measured on `1169e03`:** the command `AGENTS.md` names, run from an empty Gradle home, writes
  `gradle/verification-metadata.xml` of 295 KB, 569 components and 1,145 artifacts. The three IntelliJ IDEAs the
  plugin compiles and is verified against are among them, as group `idea`. Gradle then checks each one before use and
  fails naming the artifact whose digest differs. Nothing the IntelliJ Platform's plugin downloads escapes it. Seen
  red: one digest of `jspecify-1.0.0.jar` changed fails the build in its configuration, "One artifact failed
  verification: jspecify-1.0.0.jar (org.jspecify:jspecify:1.0.0)".
- **Every release by SHA-256, `porelo-wire` among them.** Gradle does not verify a snapshot at all, by digest or by
  signature ("Gradle does *not* verify changing dependencies", its user guide; measured: a wrong digest written for
  `porelo-release` 0.5.1-SNAPSHOT passes). So `porelo-release` and `porelo-machines` stay unchecked while the build
  takes `porelo-buildtools` as a snapshot, as the operator decided on 2026-10-18, and are checked like everything else
  once a release is pinned: `porelo-buildtools` 0.5.1 follows this round, and this issue then pins it and renews the
  file. Their own dependencies are releases and are checked now.
- **The file is renewed from an empty Gradle home.** A Gradle home that already holds the downloads skips metadata it
  does not fetch again, such as the boms the plugins' poms name. A file written there then fails on a fresh machine.
- **Shown on the first move:** Gradle 9.8.1, logchange 1.20.0 and JSpecify 1.0.1, taken by hand from what
  Dependabot's last Gradle pull request offered, without its JUnit 6, which `porelo-parent` does not pin. The renewed
  file changes the logchange, slf4j and Jackson entries they pull in, in the same commit, and it was written from an
  empty Gradle home.
- **A version is moved by hand, never by Dependabot** (the operator's decision of 2026-10-18). Dependabot keeps only
  the `github-actions` ecosystem, as in every other Porelo repository. A version in `gradle/libs.versions.toml` or
  the Gradle Wrapper is moved like `porelo-parent`'s Maven pins, and the same commit renews the file, with a
  changelog entry and the VM run before the push. Renewing a digest for a new version records what the publisher
  serves that day, after the download was compared with the repository's own checksum. Who runs the command
  changes little, so the path that matches the rest of the project is taken. No workflow writes, and no token
  that writes is added.
- **Red first:** one entry's digest changed by hand fails `build`, naming the artifact. The change is not committed.
- **What else changes:** `AGENTS.md`'s repository part names the command that renews the file. There is no changelog
  entry: the plugin the build produces stays the same.

## Acceptance

- `build`, `verifyPlugin` and `documents` green with the file, from an empty Gradle home, locally, on the VM and in
  the Build.
- A changed digest seen to fail the build, naming the artifact.
- A version move shown renewing the file in the same commit.
- `porelo-buildtools` 0.5.1 pinned once it is released, and `porelo-release` and `porelo-machines` then checked by
  digest like every other dependency.

## To be checked

- Whether the Maven repositories carry the same risk without a check of their own, so that one answer holds for
  all of them. That is a question for `porelo-project`, not built here.
