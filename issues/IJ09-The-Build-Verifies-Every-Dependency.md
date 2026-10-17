# IJ09 — The build verifies every dependency

**Status:** Accepted.

**What must be true.** Every plugin, library and tool the Gradle build resolves is checked against the digest its
source names before it is used, as `AGENTS.md` asks of every download; a dependency whose bytes differ from the ones
recorded fails the build, naming it. Today only the Gradle Wrapper is checked, by its `distributionSha256Sum` and the
wrapper's validation, while `porelo-release` and `porelo-machines` run as programs in the Build with the
checkout's token at hand.

## To be checked

- Gradle's own dependency verification (`gradle/verification-metadata.xml`) with SHA-256 for every artifact, and
  what a Dependabot update then must regenerate in the same change.
- Whether the IntelliJ Platform's IDE downloads and the plugin verifier's IDEs can be verified the same way, or
  name their digests elsewhere.
- What the Maven repositories do for the same risk, so that the answer is one for all of them.
