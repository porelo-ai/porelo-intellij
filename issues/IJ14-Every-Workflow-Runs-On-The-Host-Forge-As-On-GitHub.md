# IJ14 — Every Workflow Runs On The Host's Forge As On GitHub

**Status:** Implementing; built and green on a throwaway Forgejo, what only the host can show is under *To be checked*.
**Size:** M - five workflow files, the JDK action and the build script's snapshot repository change, with no contract
of another repository changed; the job fitted a forge runner's 8 GiB, with 26 GiB of disk at its peak.

**What must be true.** This repository's workflows run unchanged on the move's host's Forgejo and on GitHub, with
only the values of variables and secrets differing, as porelo-host's
[local registry and publication settings](https://github.com/porelo-ai/porelo-host/blob/main/doc/local-forge.md#the-local-registry-and-publication-settings)
name them. On the forge the plugin is built, tested, checked and verified; publishing and the Windows job run on
GitHub alone, each switched by a variable present on both forges, never by a branch in a workflow.

## Plan

The forge's runner gives every job the labels `ubuntu-24.04` and `ubuntu-26.04` as one pinned Ubuntu 24.04 act image,
up to 4 CPUs and 8 GiB as the host's size allows, no container socket, a Java 25 as `JAVA_HOME_25_X64` and a cache
server for `actions/cache`. Against that, today's workflows:

- **`build.yml`, the room for the IDEs:** `docker image prune` fails where there is no socket, and `sudo` may be
  missing where the job runs as root. The step takes `sudo` only when not root, prunes only where `docker info`
  answers, and fails naming both numbers when `df` shows less than 30 GiB free - a check of what the runner has, not of
  which forge it is. The job's peak was 26 GiB on the forge's image; GitHub's runner has 113 GiB free at that point.
- **`build.yml`, the triggers:** a push builds `main` only. On the forge the gate pushes `porelo/<task>` and opens its
  pull request, which `pull_request` already builds; the push trigger stays `main`, so GitHub runs no build twice.
- **`build.yml`, Publish and its read-back:** Publish also needs `vars.PORELO_PUBLISH != 'false'`, and the read-back
  follows Publish's outcome. A first step fails when the variable is misspelt, or missing on a forge that is not GitHub,
  so a missing one never leaves the plugin unpublished silently. Missing on GitHub, the Build publishes as it did before
  the variable existed: this repository's access to the organization's variable cannot be read from here, and the
  central Build must not depend on it.
- **`windows.yml`:** its job runs only `if: vars.PORELO_PUBLISH != 'false'`, the switch the host's documentation names
  for central-only jobs; without it the job would wait on the forge for a `windows-2025` runner that never comes.
- **`delete-runs.yml`:** the same condition, since it calls GitHub's API.
- **`workflows.yml`:** installs `python3-yaml` where `import yaml` fails, with `sudo` only when not root.
- **`shared-rules.yml`:** already reads the forge's pull requests as from inside (`github.server_url`); unchanged
  unless the first run says otherwise.
- **Porelo's snapshots:** the build takes `porelo-buildtools` and `porelo-wire` as releases, from Central, and a
  handover names a snapshot for a while. `build.gradle.kts` takes the snapshot
  repository from `MAVEN_SNAPSHOTS_URL` when the environment names it, before `-PporeloSnapshots` and Sonatype, with
  `porelo-publisher` and `PORELO_PACKAGES_READ_TOKEN`, which can only read, as its credentials when the token is set,
  sent only to that address and only when the server asks. Plain http is allowed only to a loopback name. On a forge that is
  not GitHub the pinned-JDK action fails naming whichever of the two is missing. Every workflow that runs on the forge
  passes both from `vars` and `secrets` to the whole job; Gradle never checks a snapshot's digest, so this changes nothing
  in `gradle/verification-metadata.xml`.

**Red first:** today's `build.yml` run as the forge runs it - in the pinned act image under podman on this machine,
with `--cpus=4 --memory=8g`, no socket and `JAVA_HOME_25_X64` set - fails at the room step; after the change the
same container runs `build`, `verifyPlugin` and `documents` green. On the host, the first push of the move's change
is the forge's own red and green.

## Acceptance

- On the host's Forgejo a pull request from `porelo/<task>` runs `build.yml` green, with `build` and `verifyPlugin`
  passing and Publish skipped, and `shared-rules.yml` green with `documents`; `windows.yml` and `delete-runs.yml` start no job there.
- On GitHub a push to `main` runs the same files as today: Build, Publish with its read-back, and Windows.
- A Build with `PORELO_PUBLISH` misspelt, or unset on a forge that is not GitHub, fails naming the variable.
- A build naming a Porelo snapshot on the forge resolves it from the forge's registry, seen in its log, and a wrong
  `PORELO_PACKAGES_READ_TOKEN` fails it naming the repository.

## To be checked

What only the host can show; everything else was measured on a throwaway Forgejo in podman, with the forge's pinned image
and limits:

- The first pull request from `porelo/<task>` on the host's forge, its own red and green.
- That the disk the host gives the runner's containers holds 30 GiB free; the role sets no limit of its own.
- That the job's egress reaches JetBrains' IDE downloads, the Gradle distribution and Central from the host.
- That a snapshot the host's registry does not hold fails the Build, since this build reads one snapshot repository: the
  Porelo versions it names today are releases, so nothing asks.
