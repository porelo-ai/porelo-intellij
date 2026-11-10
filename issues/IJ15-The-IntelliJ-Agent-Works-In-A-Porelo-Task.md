# IJ15 — The IntelliJ Agent Works In A Porelo Task

```json
{
  "$schema": "issue-1.json",
  "id": "IJ15",
  "title": "The IntelliJ Agent Works In A Porelo Task",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "This repository's agent moves into a Porelo task on the move's host, as the project's procedure for an agent's move lays out; the image on the forge's Ubuntu 24.04, Gradle's user home kept in the task, the move's own change through the gate with the forge's build green"
}
```

**Why this size.** `porelo.yml` and one small change taken through the gate; the rest is reading and measuring in the task, with the workflows as they already run on a forge.

**What must be true.** This repository's agent works in a Porelo task of the project `porelo` on the move's host, as
the project's procedure for an agent's move lays out. The task follows this repository through the gate and pushes to
the host's Forgejo, whose build is read through the `forgejo` reader. The agent is reached in the project's room as
`Agent IntelliJ`, after this repository's entry `intellij` in porelo-project's `project.yml`. This issue
is taken from start to Review inside the task, with the forge's build green.

## Plan

- **The repository:** `porelo-intellij` alone; no other repository is kept by this agent.
- **The task's image, `porelo.yml`:** it stays on Ubuntu 24.04 by digest, the image the forge's runner gives both
  Ubuntu labels, so the task builds where the forge's build runs; GitHub's 26.04 builds the same sources. Its
  GraalVM is set to the pin `porelo-machines` gives at the move, and the image gains what the agent's harness needs
  that the procedure does not bring itself, read from the agent's definition before the move.
- **Gradle's downloads:** the wrapper's distribution, the libraries and an IntelliJ IDEA for each of the three
  versions (`platformVersion` and `verifyIdeVersions`), some 17 GB, come at build time into Gradle's user home, checked
  against `gradle/verification-metadata.xml`. The task keeps Gradle's user home in its workspace, so a second build
  downloads nothing; measured before the move. Where the task lacks the disk for the three IDEs, the forge's
  `verifyPlugin` stands in for the local one, and the hand-over names that green run.
- **The move's own change:** this issue's move to Review, through the gate to `porelo/<task>`, with the pull request
  the gate opens and its forge build green, read through the `forgejo` reader inside the task.
- **The live tests:** not part of this change, which does not touch how the plugin speaks to a daemon. After the
  move, a change to how it does runs them on a machine from the host's VM pool; until that pool exists, on the
  development machine's VM.
- **The host's forge:** the move host's inventory names `porelo-intellij` in `forge_repositories`, and its registry
  holds any Porelo snapshot `gradle/libs.versions.toml` names at the move; at present the file names only releases.
- **The way back:** the one the move's procedure gives every agent; this repository adds nothing to it.

**Red first:** the procedure's check, read on the host before the task starts, fails on its first line (no task
runs); after the move each line passes.

## Acceptance

- The procedure's check passes line by line: the task runs, its push reached the forge as `porelo/<task>`, the
  forge's Build of it was read by the build reader, the gate opened the pull request, the task is in the room's
  `agent-card.json`, and a message naming `Agent IntelliJ` wakes it and is answered in the room.
- Inside the task `./gradlew build documents` passes, and a second run downloads nothing.

## To be checked

- The task's free disk and whether Gradle's user home survives a restart of the task, measured in the task.
- Which tools the agent's harness needs in the image beyond `porelo.yml`'s, and that the skills this repository
  names reach the task checked against their digests.
- That the task's egress reaches JetBrains' IDE downloads, the Gradle distribution, Central, Sonatype and the
  skills' store, and the forge runner's job reaches the first three.
- That the forge's first Build of the move's pull request goes red where the host lacks something and green once
  fixed, and that the disk the host gives the runner's containers holds the 30 GiB free the Build's room step asks.
