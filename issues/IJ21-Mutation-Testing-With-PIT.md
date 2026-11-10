# IJ21 — Mutation testing with PIT

```json
{
  "$schema": "issue-1.json",
  "id": "IJ21",
  "title": "Mutation testing with PIT",
  "state": "Accepted",
  "size": "-",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "Mutation testing with PIT over the plugin, run by hand, not in CI and with no threshold, PIT and its JUnit 5 plugin pinned, on a VM where tests call real tools, with a report sorting survivors into real gaps and harmless ones"
}
```

**What must be true.** A developer can run mutation testing with PIT over the plugin by hand and read which of the
tests' gaps are real: a report sorts the surviving mutants into real gaps and harmless ones.

## The shape

- A `pit` entry of the Gradle build, this repository's form of a `pit` profile, run by hand: not in CI and with no
  threshold.
- PIT and its JUnit 5 plugin pinned, in `gradle/libs.versions.toml`, renewing `gradle/verification-metadata.xml` in the
  same commit.
- Run on a VM where tests call real tools.
- The model is the profile `pit` in the `pom.xml` of `porelo-message-matrix`.
