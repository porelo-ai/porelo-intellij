# IJ27 — Builds take the IDEs from a cache

```json
{
  "$schema": "issue-1.json",
  "id": "IJ27",
  "title": "Builds take the IDEs from a cache",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every IntelliJ IDEA archive the Build and the Windows job download - 4.5 GB on Linux each run today - and the Windows job's Gradle and libraries come from the Actions cache, keyed by version, one generation kept; a JetBrains or Central outage no longer turns a build red"
}
```

**Why this size.** Two workflows and the shared cache step, no code; the earlier cache of the IDEs filled the runner's
disk, so the disk is measured on a hosted runner before it is handed over, and the removal of older entries needs a
token with write access to the cache.

**What must be true.** A build on GitHub or on a host's forge runs on the downloads of its last green run when
JetBrains' download server or Maven Central does not answer: every IntelliJ IDEA archive and every library a job
resolves comes from the cache once it was fetched, and the cache holds only the newest of each.

## How it stands

- The cache step in `.github/actions/pinned-jdk/action.yml` keeps Gradle's distribution and the libraries, keyed by the
  wrapper, `gradle/libs.versions.toml`, the build scripts and `gradle.properties`. It leaves out
  `caches/modules-2/files-2.1/idea` in Gradle's user home on purpose: the IDEs zipped and unpacked were some 17 GB,
  and restoring them filled the runner's disk before the Build's first step made room.
- So every Build downloads three IntelliJ IDEA archives from JetBrains: 2026.1, which
  the plugin compiles against, and 2026.1.5 and 2026.2.3, which the verifier checks. Measured in a local Gradle home:
  1.57, 1.58 and 1.62 GB, 4.5 GB together; the unpacked transforms beside them are some 13 GB and are made again from
  the archives in every run.
- The *Windows* job uses no cache at all: Gradle's distribution, every library and `idea-2026.1-win.zip` are
  downloaded in each run. It does not run the pinned-jdk action, since it builds on the runner's own Java 25.
- Downloads that stay uncached, with the reason:
  - The pinned GraalVM: cached already by `porelo-machines jdk --github`.
  - The *Windows* job's OpenSSH server, `Add-WindowsCapability`, only when the runner lacks it: a Windows component
    from Windows Update, which no Actions cache can hold.
  - `python3-yaml` in *Workflows*, from the distribution's mirror, only on a forge whose image lacks it: one small
    package, and the forge's image is the place to add it.
  - The actions themselves, which the runner fetches by commit before any step.

## Plan

- **The IDEs' archives, Linux:** a cache step of their own in `build.yml` and `shared-rules.yml`, after the Build's
  step that makes room and before the build: path `caches/modules-2/files-2.1/idea` in Gradle's user home (the
  archives alone, never the transforms), key `idea-${{ runner.os }}-` plus the hash of `gradle.properties`, which
  names every IDE version, and no `restore-keys`, so a version move never restores the old archives beside the new
  ones.
  Gradle checks each archive against `gradle/verification-metadata.xml` as it does a download.
- **The *Windows* job:** the same libraries cache as the pinned-jdk action, under its own `runner.os` key, and the
  IDE cache above for `idea-2026.1-win.zip`.
- **One generation kept:** after a cache is saved under a new key, a step deletes the entries of the same prefix with
  another key through the REST API (`DELETE /repos/{owner}/{repo}/actions/caches?key=`), with `actions: write` given to
  that step's job alone; on a forge the step is skipped where its cache server has no such API, and the forge's own
  eviction holds.
- No unit test: the change is to workflows only. Its proof is two runs on GitHub: the first saves, the second restores
  and downloads no IDE (read in the log, and the step's restore hit), and the disk left after `verifyPlugin`, which
  `build.yml` prints, is no lower than today's.

## Acceptance

- A second run of the Build, *Shared rules check* and *Windows* on an unchanged commit downloads no IntelliJ IDEA and
  no library: its cache steps report a hit, and Gradle's log names no download from JetBrains or Central.
- After a version in `gradle.properties` moves, the cache holds the new IDE entry alone, read from the repository's
  cache list.
- The Build's free disk after `verifyPlugin` is no lower than before the change.

## To be checked

- Which IDEs *Shared rules check*'s `documents` resolves, since its tests compile against 2026.1; it gets the IDE
  cache only where it downloads one.
- Whether GitHub's repository settings can remove cache entries after a set time or cap the cache's size per
  repository, beside the eviction of entries unused for seven days and the 10 GB limit; if they can, that replaces the
  delete step.
- The *Windows* runner's free disk with the IDE archive restored, and the size of `idea-2026.1-win.zip`.
- Whether the host's Forgejo cache server answers the cache list and delete calls GitHub's API offers.
