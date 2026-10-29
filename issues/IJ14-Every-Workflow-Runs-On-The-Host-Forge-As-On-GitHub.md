# IJ14 — Every Workflow Runs On The Host's Forge As On GitHub

**Status:** Estimated.
**Size:** M - five workflow files, the JDK action and the build script's snapshot repository change, with no contract
of another repository changed; the first runs on a forge runner with 8 GiB and the IDEs' 17 GB are the risk.

**What must be true.** This repository's workflows run unchanged on the move's host's Forgejo and on GitHub, with
only the values of variables and secrets differing, as porelo-host's
[local registry and publication settings](https://github.com/porelo-ai/porelo-host/blob/main/doc/local-forge.md#the-local-registry-and-publication-settings)
name them. On the forge the plugin is built, tested, checked and verified; publishing and the Windows job run on
GitHub alone, each switched by a variable present on both forges, never by a branch in a workflow.

## Plan

The forge's runner gives every job the labels `ubuntu-24.04` and `ubuntu-26.04` as one pinned Ubuntu 24.04 act image,
4 CPUs, 8 GiB, no container socket, the host's CA through `JAVA_TOOL_OPTIONS` and the bundle, a Java 25 as
`JAVA_HOME_25_X64` and a cache server for `actions/cache`. Against that, today's workflows:

- **`build.yml`, the room for the IDEs:** `docker image prune` fails where there is no socket, and `sudo` may be
  missing where the job runs as root. The step takes `sudo` only when not root, prunes only where `docker info`
  answers, and fails naming both numbers when `df` shows less free than the IDEs need (measured, below) - a check of
  what the runner has, not of which forge it is.
- **`build.yml`, the triggers:** a push builds `main` only. On the forge the gate pushes `porelo/<task>` and opens its
  pull request, which `pull_request` already builds; the push trigger stays `main`, so GitHub runs no build twice.
- **`build.yml`, Publish and its read-back:** both also need `vars.PORELO_PUBLISH == 'true'`; a first step fails
  when the variable is neither `true` nor `false`, so a missing one never leaves the plugin unpublished silently.
  On GitHub `PORELO_PUBLISH=true` is already in place as a variable of the organization, for all its repositories.
- **`windows.yml`:** its job runs only `if: vars.PORELO_PUBLISH == 'true'`, the switch the host's documentation names
  for central-only jobs; without it the job would wait on the forge for a `windows-2025` runner that never comes.
- **`delete-runs.yml`:** the same condition, since it calls GitHub's API.
- **`workflows.yml`:** installs `python3-yaml` where `import yaml` fails, with `sudo` only when not root.
- **`shared-rules.yml`:** already reads the forge's pull requests as from inside (`github.server_url`); unchanged
  unless the first run says otherwise.
- **Porelo's snapshots:** the build resolves `porelo-release` and `porelo-machines` (`porelo-buildtools`
  `0.5.1-SNAPSHOT`) from Sonatype; `porelo-wire` is a release, from Central. `build.gradle.kts` takes the snapshot
  repository from `MAVEN_SNAPSHOTS_URL` when the environment names it, before `-PporeloSnapshots` and Sonatype, with
  `porelo-publisher` and `PORELO_PUBLISHER_TOKEN` as its credentials when the token is set, sent only when the server
  asks. Every workflow passes both from `vars` and `secrets`; Gradle never checks a snapshot's digest, so this changes
  nothing in `gradle/verification-metadata.xml`.

**Red first:** today's `build.yml` run as the forge runs it - in the pinned act image under podman on this machine,
with `--cpus=4 --memory=8g`, no socket and `JAVA_HOME_25_X64` set - fails at the room step; after the change the
same container runs `build`, `verifyPlugin` and `documents` green. On the host, the first push of the move's change
is the forge's own red and green.

## Acceptance

- On the host's Forgejo a pull request from `porelo/<task>` runs `build.yml` green, with `build`, `verifyPlugin` and
  `documents` passing and Publish skipped; `windows.yml` and `delete-runs.yml` start no job there.
- On GitHub a push to `main` runs the same files as today: Build, Publish with its read-back, and Windows.
- A Build with `PORELO_PUBLISH` unset or misspelt fails naming the variable.
- The forge's build resolves `porelo-buildtools`' snapshot from the forge's registry, seen in its log, and a wrong
  `PORELO_PUBLISHER_TOKEN` fails it naming the repository.

## To be checked

- The job's free disk on the host and the peak of `verifyPlugin` with three IDEs, measured in the act image; the
  disk the host gives the runner's containers is read from the host's role.
- Whether `verifyPlugin` fits into 8 GiB with Gradle's `-Xmx2g`, the verifier's own heap and the test JVMs.
- Whether a test reads a child JVM's stderr together with its output, which `JAVA_TOOL_OPTIONS`' notice would break;
  today only `git` is run that way.
- That the job's egress reaches JetBrains' IDE downloads, the Gradle distribution and Central.
