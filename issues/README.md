# Issues

The open tasks of `sokar-intellij`, grouped by state; the rules for an issue are in [AGENTS.md](../AGENTS.md). Its prefix
is `IJ`.

## Implementing

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij01"></a>[IJ01](IJ01-Work-Is-Reviewed-In-IntelliJ.md) | S | | A tool window lists every waiting push; its diff is read in IntelliJ's own diff windows from a bare cache, nothing checked out; approve with the commit shown, or reject with a reason, through the daemon's contract. Built but for the publishing |
| <a id="ij03"></a>[IJ03](IJ03-Sokar-Reached-From-IntelliJ-On-Windows.md) | M | | Seen on Windows: a Sokar in a WSL2 distribution reached through `wsl.exe` and `sokar daemon connect`; a machine over ssh from Windows. The WSL way is built, the ssh way is next; GitHub's Windows runner is the regular check; untested on a Windows machine |

## Analyzed

| # | size | blocked by | what it covers | open questions |
|---|---|---|---|---|
| <a id="ij05"></a>[IJ05](IJ05-A-Waiting-Push-Opened-In-Its-Project.md) | M | | *Open in <project>*: a waiting push fetched as `refs/remotes/sokar/<push>` into the open project that holds its upstream, working tree untouched; that remote fetched there after approving | — |

## Estimated

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij02"></a>[IJ02](IJ02-Live-Notices-And-A-Guide.md) | M | | Notices within seconds through `WatchProjects`; a `doc/` chapter for people |
| <a id="ij04"></a>[IJ04](IJ04-Code-Comments-In-The-Review.md) | XL | `sokar` [B166](https://github.com/sokar-ai/sokar/blob/main/issues/base/README.md#b166), [B171](https://github.com/sokar-ai/sokar/blob/main/issues/base/README.md#b171) | Comments on a push's lines written in the review tab and shown as threads with their origin; suggestions shown, never applied; outgoing forge comments and held incoming ones released one at a time |
