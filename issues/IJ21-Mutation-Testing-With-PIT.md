# IJ21 — Mutation testing with PIT

**Status:** Accepted.

**What must be true.** A developer can run mutation testing with PIT over the plugin by hand and read which of the
tests' gaps are real: a report sorts the surviving mutants into real gaps and harmless ones.

## The shape

- A `pit` entry of the Gradle build, this repository's form of a `pit` profile, run by hand: not in CI and with no
  threshold.
- PIT and its JUnit 5 plugin pinned, in `gradle/libs.versions.toml`, renewing `gradle/verification-metadata.xml` in the
  same commit.
- Run on a VM where tests call real tools.
- The model is the try in `porelo-message-matrix`: its report
  `~/.porelo/handover/porelo-message-matrix/pit-014d86a/report.md` and its branch `pit-try`, later its pushed profile.
