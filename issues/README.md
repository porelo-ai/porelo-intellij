# Issues

The open tasks of `porelo-intellij`, grouped by state; the rules for an issue are in [AGENTS.md](../AGENTS.md). Its prefix
is `IJ`.

## Implementing

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij02"></a>[IJ02](IJ02-Live-Notices-And-A-Guide.md) | M | | Notices within seconds through `WatchProjects`, measured at 3 s on a VM; `doc/index.md` grown by what a person does. Its walk waits for the operator, with [IJ01](README.md#ij01)'s |
| <a id="ij01"></a>[IJ01](IJ01-Work-Is-Reviewed-In-IntelliJ.md) | S | | A tool window lists every waiting push; its diff is read in IntelliJ's own diff windows from a bare cache, nothing checked out; approve with the commit shown, or reject with a reason, through the daemon's contract. Built; the install section waits for the operator's walk |
| <a id="ij09"></a>[IJ09](IJ09-The-Build-Verifies-Every-Dependency.md) | M | | Every plugin, library and IDE the Gradle build resolves checked against its SHA-256 before use, the file renewed by hand with every version move; Porelo's snapshots unchecked until released, as Gradle checks no snapshot |

## Analyzed

| # | size | blocked by | what it covers | open questions |
|---|---|---|---|---|
| <a id="ij04"></a>[IJ04](IJ04-Code-Comments-In-The-Review.md) | L | [IJ12](README.md#ij12) | Comments, findings, replies and read-only fix previews in the review tab as SARIF 2.1.0, inline through [IJ12](README.md#ij12)'s adapter and in a discussion panel for file, general and outdated threads, with authenticated source levels and risk classes | 0 |

## Idea

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij12"></a>[IJ12](IJ12-The-Code-Review-Adapter-Over-collaborationTools.md) | | | One narrow, replaceable pure-Java adapter package over `intellij.platform.collaborationTools` for diff threads and multi-line gutter controls on Porelo's diff viewers, verified on 2026.1+ without `qodana-sarif` or internal classes |
| <a id="ij13"></a>[IJ13](IJ13-Held-And-Outgoing-Forge-Comments-Released-In-IntelliJ.md) | | [IJ04](README.md#ij04) | Held inbound forge comments (`E0`) and outgoing forge comments, replies, suggestions and verdicts shown with their exact destination, operation, commit and content, and released or dropped singly through the daemon; an edit invalidates a prior release |
| <a id="ij11"></a>[IJ11](IJ11-Tried-On-A-Windows-Machine.md) | | | The WSL way and the ssh way tried once on a real Windows machine, with what to install and what to try; what failed goes to the channel, what passed into `doc/` with the plugin's version |
| <a id="ij10"></a>[IJ10](IJ10-Relays-And-Connections-End-Cleanly.md) | | | Relays, WSL bridges and tunnels never outlive their use, leave no socket file or child behind, and a probe never answers with half of what it read; seven older points from a skill reading, each to be reproduced first |

## Estimated

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij14"></a>[IJ14](IJ14-Every-Workflow-Runs-On-The-Host-Forge-As-On-GitHub.md) | M | | The workflows run unchanged on the move's host's Forgejo and on GitHub: the IDEs' room made by what the runner has, Publish, the Windows job and run deletion switched by `PORELO_PUBLISH`, Porelo's snapshots from `MAVEN_SNAPSHOTS_URL`; rehearsed first in the forge's act image under podman |
| <a id="ij15"></a>[IJ15](IJ15-The-IntelliJ-Agent-Works-In-A-Porelo-Task.md) | S | [IJ14](README.md#ij14) | This repository's agent moves into a Porelo task on the move's host, as the project's procedure for an agent's move lays out; the image on the forge's Ubuntu 24.04, `~/.gradle` kept in the task, the move's own change through the gate with the forge's build green |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="ij07"></a>[IJ07](README.md#ij07) | Sokar is renamed Porelo | The plugin, its id `org.fuin.porelo`, packages, build and documents named Porelo, speaking to a Porelo daemon and installed afresh; a test of the documents refuses the old name. |
| <a id="ij08"></a>[IJ08](README.md#ij08) | Reformatted to the shared style | Google Java Style in its AOSP variant checked by Spotless in `build`, the shared `.editorconfig` checked by `checkShared`, and one commit that only reformats, which `git blame` skips. |
| <a id="ij06"></a>[IJ06](README.md#ij06) | The code read against its skills | All of the plugin's code, tests and build read against the five skills `AGENTS.md` names; 31 defects fixed, 17 of them with a test of their own seen red first. |
| <a id="ij05"></a>[IJ05](README.md#ij05) | A waiting push opened in its project | *Open in* the IntelliJ window whose repository holds the gate's upstream fetches the push there as `porelo/<push>`, leaving the working tree, index and branch as they were; the ref is deleted once decided, and that remote fetched after approving. |
| <a id="ij03"></a>[IJ03](README.md#ij03) | Porelo reached from IntelliJ on Windows | A Porelo in a WSL 2 distribution reached through `wsl.exe` and `porelo daemon connect`, and a machine over ssh through Windows' own `ssh.exe`; the ssh way checked on GitHub's Windows runner on every change, the WSL way against stand-ins on Linux only. |
