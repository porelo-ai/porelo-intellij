# IJ07 — Sokar is renamed Porelo

**Status:** Review.
**Size:** S - one module, 483 occurrences in 64 of 80 tracked files and 44 paths before this issue, all of them made by the script; no
image draws the name, and every contract the plugin reads is renamed by another repository first, so the work is the
script, its exceptions, the build and a live run against a Porelo daemon.

**What must be true.** The plugin, its id, its packages, its build, its documentation and everything it shows in the
IDE are named Porelo, and it speaks to a Porelo daemon; nothing a developer installs or reads in it still says Sokar.
It is installed afresh: nothing the Sokar plugin kept in the IDE or on disk is read or carried over. From `sokar-project`
[PJ41](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj41).

## What carries the name

- **Paths (44):** the packages `org.fuin.sokar.intellij.*` under `src/main` and `src/test` (43 files), and the file name of [IJ03](README.md#ij03).
- **Owned by the plugin, renamed with nothing to keep:** the plugin id `org.fuin.sokar` (becomes `org.fuin.porelo`), its
  name and vendor in `plugin.xml`, the tool window and notification group `Sokar`, the editor type `sokar-review`, the
  toolbars `SokarWaiting` and `SokarReview`, the settings keys `org.fuin.sokar.groupByMachine` and `sokar.review.split`,
  the cache directory `sokar-intellij`, the tunnel sockets `sokar-intellij-<machine>.sock`, the threads' names, the
  classes `SokarWatch`, `SokarStartup` and `SokarToolWindowFactory`, `pluginGroup`, `rootProject.name`, the doc site's
  name and the live tests' variables `SOKAR_LIVE_*` (nine). The plugin is not published yet, so a new id loses nothing.
- **Read from other repositories, so each must match what they become:**
  - from `sokar`: the varlink interface `org.fuin.sokar.Tasks1` (`Gate.INTERFACE`), the gate's refs `refs/sokar/base/`
    and `refs/sokar/incoming/`, the forwarded branch `sokar/<push>`, the daemon's socket
    `$XDG_RUNTIME_DIR/sokar/sokard.sock` (becomes `porelo/porelod.sock`), the command `sokar daemon connect` the WSL
    bridge runs, the artifact `sokar-wire` - each the plain replacement there too, nothing kept under the old name;
  - from `sokar-frontend`: the machines file `~/.config/sokar/frontend.json` and `%APPDATA%\sokar` on Windows,
    which becomes `porelo` there by the same replacement, nothing kept under the old name;
  - from `sokar-buildtools`: `sokar-release` and `sokar-machines` with their main classes `org.fuin.sokar.release.Main`
    and `org.fuin.sokar.machines.Main`, and the snapshot filter on the group `org.fuin.sokar`;
  - from the forge: the links to `sokar-ai.github.io` and to the other repositories' indexes.

## Plan

1. **Red first:** a test tagged `documents`, beside `DocumentTestsTaggedTest`, that fails while any tracked file
   outside the exceptions says `sokar` in any case, naming each file and line. Seen red on the tree before the script.
2. **The script** (in the scratchpad, not committed): `git mv` of every path naming `sokar`, then `sokar`, `Sokar`
   and `SOKAR` replaced by `porelo`, `Porelo` and `PORELO` in every tracked text file, then the exceptions put back.
   It makes `sokard` into `porelod` as [PJ41](https://github.com/sokar-ai/sokar-project/blob/main/issues/README.md#pj41) decided, and `org\\.fuin\\.sokar` in the build's regular expression
   into `org\\.fuin\\.porelo`.
3. **The exceptions:** this issue's file, which says what the old names were; the test of step 1, which names the
   old word; and the changelog entry for `0.5.0` that says the plugin was renamed. There are no released changelog
   entries here.
4. **What the script does not do:** nothing measured. `doc/images/early-bird.svg` is the only image and does not
   draw the name; no name is built from parts that split the word; no fixture carries a hash over it. The tunnel's
   socket name grows by one character, `<runtime>/porelo-intellij-<machine>.sock`, well inside the 108-character
   limit, and no test asserts its length.
5. **Versions:** `porelo-buildtools` and `porelo-wire` at their `0.5.0` snapshot in `gradle/libs.versions.toml`, and
   the plugin's own version on that line.
6. **The Shared Area** is taken in the same window from `sokar-project`, as every repository does.

**Published before it builds:** `porelo-buildtools` (`porelo-release`, `porelo-machines`) and `porelo-wire` from
`sokar`, as snapshots; the forge renamed, so `check-citations` resolves the links; the site renamed for the doc's
links.

## Acceptance

- The test of step 1 seen red before the script and green after, and kept.
- `./gradlew build`, `./gradlew verifyPlugin` and `./gradlew documents` green on the renamed commit, checked out.
- The tests tagged `live` run against a Porelo daemon installed afresh in the account `intellij` on the VM: the
  gate, the taken branch, and the WSL bridge where a Windows machine is reachable; otherwise the hand-over says it
  did not run.
- The plugin installed in a fresh IntelliJ IDEA on the VM shows *Porelo* in its tool window and notices, and lists
  what waits at the Porelo daemon.

## Open questions

- None.
