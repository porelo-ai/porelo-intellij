# Issues

The open tasks of `porelo-intellij`, grouped by state; the rules for an issue are in [AGENTS.md](../AGENTS.md). Its prefix
is `IJ`.

## Review

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij05"></a>[IJ05](IJ05-A-Waiting-Push-Opened-In-Its-Project.md) | M | | *Open in <project>*: a waiting push fetched as `refs/remotes/porelo/<push>` into the open project that holds its upstream, working tree untouched; that remote fetched there after approving |

## Implementing

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij02"></a>[IJ02](IJ02-Live-Notices-And-A-Guide.md) | M | | Notices within seconds through `WatchProjects`; `doc/index.md` grown by what a person does, its install section left to [IJ01](README.md#ij01) |
| <a id="ij01"></a>[IJ01](IJ01-Work-Is-Reviewed-In-IntelliJ.md) | S | | A tool window lists every waiting push; its diff is read in IntelliJ's own diff windows from a bare cache, nothing checked out; approve with the commit shown, or reject with a reason, through the daemon's contract. Built but for the Publish job and the install section |
| <a id="ij03"></a>[IJ03](IJ03-Porelo-Reached-From-IntelliJ-On-Windows.md) | M | | Seen on Windows: a Porelo in a WSL2 distribution reached through `wsl.exe` and `porelo daemon connect`; a machine over ssh from Windows. Both ways are built; the ssh way is checked in the Windows job on GitHub's runner, by hand only; untested on a Windows machine |
| <a id="ij09"></a>[IJ09](IJ09-The-Build-Verifies-Every-Dependency.md) | M | | Every plugin, library and IDE the Gradle build resolves checked against its SHA-256 before use, the file renewed by hand with every version move; Porelo's snapshots unchecked until released, as Gradle checks no snapshot |

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
