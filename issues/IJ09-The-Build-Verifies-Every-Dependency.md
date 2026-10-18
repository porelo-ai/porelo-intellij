# IJ09 — The build verifies every dependency

**Status:** Analyzed.
**Size:** M - one generated file and the build's switch to it, a rule for Porelo's own snapshots, the way a
dependency update renews the file, and a check that a tampered entry fails; no code of the plugin changes.

**What must be true.** Every plugin, library and tool the Gradle build resolves is checked against the digest its
source names before it is used, as `AGENTS.md` asks of every download; a dependency whose bytes differ from the ones
recorded fails the build, naming it. Today only the Gradle Wrapper is checked, by its `distributionSha256Sum` and the
wrapper's validation, while `porelo-release` and `porelo-machines` run as programs in the Build with the
checkout's token at hand.

## Plan

- **Measured on `0fb6b91`:** `./gradlew --write-verification-metadata sha256 build verifyPlugin` writes
  `gradle/verification-metadata.xml` of 287 KB, 552 components and 1,116 artifacts, the three IntelliJ IDEAs the
  plugin compiles and is verified against among them, as group `idea`. Gradle then checks each one before use and
  fails naming the artifact whose digest differs; nothing in the IntelliJ Platform's plugin escapes it.
- **Porelo's own snapshots** (`porelo-wire` while it is one, `porelo-release`, `porelo-machines`) change their bytes
  with every publish, so a SHA-256 would break on each. Central's snapshots carry a signature (`.asc`, measured for
  `porelo-release` `0.5.1-SNAPSHOT`), so the file checks `org.fuin.porelo` by signature against Porelo's own
  signing key, trusted for that group alone, and everything else by SHA-256.
- **The file is committed and kept by its own task:** a dependency update renews it with the same command, in the
  same change, or the build fails on the new artifact. How Dependabot's pull requests get it is the open question
  below.
- **Red first:** one entry's digest changed by hand fails `build`, naming the artifact; a signature from another
  key fails it for a Porelo artifact. Neither change is committed.
- **What else changes:** `AGENTS.md`'s repository part names the command that renews the file; the changelog
  says the build verifies its dependencies.

## Acceptance

- `build`, `verifyPlugin` and `documents` green with the file, locally, on the VM and in the Build.
- A changed digest and a foreign signature each seen to fail the build, naming the artifact.
- A dependency update shown renewing the file in the way the operator chooses.

## Open questions

- **How a Dependabot pull request gets the renewed file.** (a) A workflow renews it on Dependabot's branch and
  pushes: that needs a token that writes to that branch, which the new rule on `persist-credentials` and the list
  of jobs that may write must then name. (b) A person, or the repository's agent, renews it on the branch by hand
  before merging; nothing writes from a workflow. (c) Dependabot is left to open its pull requests red until
  someone renews the file. I propose (b): it adds no token that writes.

## To be checked

- Whether the Maven repositories carry the same risk without a check of their own, so that one answer holds for
  all of them; that is a question for `porelo-project`, not built here.
