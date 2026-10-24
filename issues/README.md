# Issues

The open tasks of `porelo-intellij`, grouped by state; the rules for an issue are in [AGENTS.md](../AGENTS.md). Its prefix
is `IJ`.

## Implementing

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij02"></a>[IJ02](IJ02-Live-Notices-And-A-Guide.md) | M | | Notices within seconds through `WatchProjects`, measured at 3 s on a VM; `doc/index.md` grown by what a person does. Its walk waits for the operator, with [IJ01](README.md#ij01)'s |
| <a id="ij01"></a>[IJ01](IJ01-Work-Is-Reviewed-In-IntelliJ.md) | S | | A tool window lists every waiting push; its diff is read in IntelliJ's own diff windows from a bare cache, nothing checked out; approve with the commit shown, or reject with a reason, through the daemon's contract. Built; the install section waits for the operator's walk |
| <a id="ij09"></a>[IJ09](IJ09-The-Build-Verifies-Every-Dependency.md) | M | `porelo-buildtools` 0.5.1, with release 0.6 | Every plugin, library and IDE the Gradle build resolves checked against its SHA-256 before use, the file renewed by hand with every version move; Porelo's snapshots unchecked until released, as Gradle checks no snapshot |

## Idea

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij11"></a>[IJ11](IJ11-Tried-On-A-Windows-Machine.md) | | | The WSL way and the ssh way tried once on a real Windows machine, with what to install and what to try; what failed goes to the channel, what passed into `doc/` with the plugin's version |
| <a id="ij10"></a>[IJ10](IJ10-Relays-And-Connections-End-Cleanly.md) | | | Relays, WSL bridges and tunnels never outlive their use, leave no socket file or child behind, and a probe never answers with half of what it read; seven older points from a skill reading, each to be reproduced first |

## Estimated

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij04"></a>[IJ04](IJ04-Code-Comments-In-The-Review.md) | XL | `porelo` [B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166), [B171](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b171) | Comments on a push's lines written in the review tab and shown as threads with their origin; suggestions shown, never applied; outgoing forge comments and held incoming ones released one at a time |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="ij07"></a>[IJ07](README.md#ij07) | Sokar is renamed Porelo | The plugin, its id `org.fuin.porelo`, packages, build and documents named Porelo, speaking to a Porelo daemon and installed afresh; a test of the documents refuses the old name. |
| <a id="ij08"></a>[IJ08](README.md#ij08) | Reformatted to the shared style | Google Java Style in its AOSP variant checked by Spotless in `build`, the shared `.editorconfig` checked by `checkShared`, and one commit that only reformats, which `git blame` skips. |
| <a id="ij06"></a>[IJ06](README.md#ij06) | The code read against its skills | All of the plugin's code, tests and build read against the five skills `AGENTS.md` names; 31 defects fixed, 17 of them with a test of their own seen red first. |
| <a id="ij05"></a>[IJ05](README.md#ij05) | A waiting push opened in its project | *Open in* the IntelliJ window whose repository holds the gate's upstream fetches the push there as `porelo/<push>`, leaving the working tree, index and branch as they were; the ref is deleted once decided, and that remote fetched after approving. |
| <a id="ij03"></a>[IJ03](README.md#ij03) | Porelo reached from IntelliJ on Windows | A Porelo in a WSL 2 distribution reached through `wsl.exe` and `porelo daemon connect`, and a machine over ssh through Windows' own `ssh.exe`; the ssh way checked on GitHub's Windows runner on every change, the WSL way against stand-ins on Linux only. |
