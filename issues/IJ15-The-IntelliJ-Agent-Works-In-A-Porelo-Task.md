# IJ15 — The IntelliJ Agent Works In A Porelo Task

**Status:** Idea; blocked by [IJ14](README.md#ij14).

**What must be true.** This repository's agent works in a Porelo task of the project `porelo` on the move's host, as
porelo-project [PJ59](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj59) lays out for
sprint 7c, beside porelo-message-matrix and porelo-frontend. The task follows this repository through the gate and
pushes to the host's Forgejo, whose build is read through the `forgejo` reader. The agent is reached in the project's
room under the name its repository gives it. This issue is taken from start to Review inside the task, with the
forge's build green.

## What moves, and what to check

- **The repository:** `porelo-intellij` alone; no other repository is kept by this agent.
- **The task's image:** this repository's `porelo.yml`, Ubuntu by digest with the pinned GraalVM. Its base is
  Ubuntu 24.04 while the Build runs on 26.04; which one the task takes is read before the move.
- **Gradle's downloads:** the Gradle Wrapper's distribution and an IntelliJ IDEA for every version `verifyPlugin`
  checks, some 17 GB, come at build time and are checked against `gradle/verification-metadata.xml`. Whether the task
  keeps them between builds, and has the disk for them, is measured before the move.
- **Forgejo's build:** [IJ14](README.md#ij14). The Windows job and Publish stay on GitHub.
- **The live tests:** `./gradlew liveTest` needs a daemon with a task that pushed, reached today over ssh in the VM
  account `intellij`. Whether a task reaches such a machine, or the tests run against the host's own daemon, is read
  before the move.
- **The way back:** none beyond the move's own step 5, which holds for every agent.
