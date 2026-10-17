# Issues

The open tasks of `porelo-intellij`, grouped by state; the rules for an issue are in [AGENTS.md](../AGENTS.md). Its prefix
is `IJ`.

## Implementing

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij01"></a>[IJ01](IJ01-Work-Is-Reviewed-In-IntelliJ.md) | S | | A tool window lists every waiting push; its diff is read in IntelliJ's own diff windows from a bare cache, nothing checked out; approve with the commit shown, or reject with a reason, through the daemon's contract. Built but for the publishing |
| <a id="ij03"></a>[IJ03](IJ03-Porelo-Reached-From-IntelliJ-On-Windows.md) | M | | Seen on Windows: a Porelo in a WSL2 distribution reached through `wsl.exe` and `porelo daemon connect`; a machine over ssh from Windows. The WSL way is built, the ssh way is next; GitHub's Windows runner is the regular check; untested on a Windows machine |

## Analyzed

| # | size | blocked by | what it covers | open questions |
|---|---|---|---|---|
| <a id="ij06"></a>[IJ06](IJ06-The-Code-Read-Against-Its-Skills.md) | M | | All of the plugin's code, its tests and its build read against the five skills `AGENTS.md` names, in the versions recorded; each finding fixed with a test seen to fail first, or declined with the reason | — |
| <a id="ij05"></a>[IJ05](IJ05-A-Waiting-Push-Opened-In-Its-Project.md) | M | | *Open in <project>*: a waiting push fetched as `refs/remotes/porelo/<push>` into the open project that holds its upstream, working tree untouched; that remote fetched there after approving | — |

## Estimated

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij02"></a>[IJ02](IJ02-Live-Notices-And-A-Guide.md) | M | | Notices within seconds through `WatchProjects`; a `doc/` chapter for people |
| <a id="ij04"></a>[IJ04](IJ04-Code-Comments-In-The-Review.md) | XL | `porelo` [B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166), [B171](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b171) | Comments on a push's lines written in the review tab and shown as threads with their origin; suggestions shown, never applied; outgoing forge comments and held incoming ones released one at a time |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="ij07"></a>[IJ07](README.md#ij07) | Sokar is renamed Porelo | The plugin, its id `org.fuin.porelo`, packages, build and documents named Porelo, speaking to a Porelo daemon and installed afresh; a test of the documents refuses the old name. |
