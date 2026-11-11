# IJ27 — Builds take the IDEs from a cache

```json
{
  "$schema": "issue-1.json",
  "id": "IJ27",
  "title": "Builds take the IDEs from a cache",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every IntelliJ IDEA archive the Build and the Windows job download - 4.5 GB on Linux each run today - and the Windows job's Gradle and libraries come from the Actions cache, keyed by the IDE versions, old entries removed by GitHub's own eviction; the GraalVM follows with porelo-buildtools 0.5.2's cache"
}
```

**Why this size.** Three workflows, no code and no contract; the delete step first planned fell away, since GitHub
removes old entries itself.

**What must be true.** A build on GitHub runs on the downloads of an earlier run when JetBrains' download server or
Maven Central does not answer: every IntelliJ IDEA archive and every library a job resolves comes from the cache once
it was fetched, and old entries leave the cache by themselves, so it never overflows.

## How it stands

- The cache step in `.github/actions/pinned-jdk/action.yml` keeps Gradle's distribution and the libraries, keyed by the
  wrapper, `gradle/libs.versions.toml`, the build scripts and `gradle.properties`. It leaves out
  `caches/modules-2/files-2.1/idea` in Gradle's user home: the IDEs zipped and unpacked were some 17 GB, and
  restoring them once filled the runner's disk.
- So every Build downloads three IntelliJ IDEA archives from JetBrains: 2026.1, which the plugin compiles against, and
  2026.1.5 and 2026.2.3, which the verifier checks. Measured in a local Gradle home: 1.57, 1.58 and 1.62 GB, 4.5 GB
  together. The unpacked transforms, some 13 GB, are made again from the archives in every run and stay uncached.
- The hosted runner has room for the archives: the last green Build on main had 145 GB, 113 GB free after the step
  that makes room and 93 GB after `verifyPlugin`.
- The *Windows* job used no cache at all: Gradle's distribution, every library and the Windows archive of 2026.1 were
  downloaded in each run.
- The repository's cache held two entries, 597 MB, both `gradle-libraries-Linux-`. GitHub removes an entry seven days
  after its last use and the oldest entries once a repository's cache passes its size limit, 10 GB by default; both are
  repository settings, raised only at a price. The token the agents read builds with may not read them.
- The pinned GraalVM is downloaded in every run too, 36 s in the last Build: `machines jdk --github` of
  porelo-buildtools 0.5.1 installs only into an empty folder. Its `--cache` option and the shared `pinned-jdk` action
  that keeps the GraalVM by its digest come with porelo-buildtools 0.5.2, which this repository takes when it is
  released, together with that action's `pinned.sh`.
- Downloads that stay uncached, with the reason:
  - The *Windows* job's OpenSSH server, `Add-WindowsCapability`, only when the runner lacks it: a Windows component
    from Windows Update, which no Actions cache can hold.
  - `python3-yaml` in *Workflows*, from the distribution's mirror, only on a forge whose image lacks it: one small
    package, and the forge's image is the place to add it.
  - The actions themselves, which the runner fetches by commit before any step.

## Plan

- **The IDEs' archives:** a cache step of their own in `build.yml` and `windows.yml`, after the step that makes room:
  path `caches/modules-2/files-2.1/idea` in Gradle's user home (the archives alone, never the transforms), key
  `idea-${{ runner.os }}-` plus the hash of `gradle.properties`, which names every IDE version, and no `restore-keys`,
  so a version move never restores the old archives beside the new ones. Gradle checks each archive against
  `gradle/verification-metadata.xml` as it does a download.
- **The *Shared rules check*:** it restores the Build's entry and never saves one, since it resolves only the IDE the
  plugin compiles against and would store an incomplete entry under the Build's key.
- **The *Windows* job:** the same libraries cache as the pinned-jdk action, under its own `runner.os` key.
- **Old entries:** GitHub's eviction, seven days after last use and by size, removes them; no step deletes any, so no
  job holds a token that may write the cache.
- No unit test: the change is to workflows only. Its proof is two runs on GitHub: the first saves, the second restores
  and downloads no IDE.

## Acceptance

- A second run of the Build and *Windows* on an unchanged `gradle.properties` restores the IDEs' archives (the cache
  step reports a hit) and downloads no IntelliJ IDEA from JetBrains.
- The Build's free disk after `verifyPlugin` stays far above zero; it printed 93 GB before the change.

## To be checked

- That the repository's cache settings are at GitHub's defaults, seven days and 10 GB, which only someone with
  administrative rights can read.
