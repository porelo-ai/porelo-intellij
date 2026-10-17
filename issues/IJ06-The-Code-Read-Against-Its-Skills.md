# IJ06 — The code read against its skills

**Status:** Implementing, on [IJ08](README.md#ij08)'s reformatting; blocked by `porelo-project` [PJ29](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj29)'s shared part until
it is published.
**Size:** L - the reading found 31 defects to reproduce and fix, and five tests that are weak or cannot
fail; several fixes in `ide` first move logic out of the IntelliJ services into plain classes so that a unit test
reaches it, and one needs a deadline the plugin cannot set without `porelo-wire`. It was M before the reading.

**What must be true.** All of the plugin's code has been read against the skills `AGENTS.md` names, and every
finding is either fixed, with a test that was seen to fail first, or declined with its reason written here; the
code a later issue changes is then read against them before it is handed over.

## Plan

- **The skills, in the versions the reading uses**, each downloaded as `AGENTS.md` says and its SHA-256 compared
  with the one the repository publishes before it is loaded:

  | skill | version | SHA-256 of the package |
  |---|---|---|
  | `java-code-review` | 2026.906.3 | `802f86339442ed9fa9d5bca14b6d16e1f6420ae92a047b18bba11239e32c698b` |
  | `concurrency-review` | 2026.906.3 | `3a0aab612f25483a3fe777eb18fbcc434e936b19298708bf88beff8b22d79e56` |
  | `test-quality` | 2026.906.3 | `9c5d23ad7a6d9c6f5c3679f3cc44a7fcc2fb3cc1c0f0d0ba4a2c0f7812428bad` |
  | `security-audit` | 2026.906.3 | `a46fd1be07bd539b955291537dea0f24f65a2f8b15d40d50a6a9098bbd992747` |
  | `clean-code` | 2026.906.3 | `5cf777a24630298b4b801c934f599722978c21aeac53d61683243908fc041c7b` |

  A newer version published before the reading starts replaces its row, in the same commit as the first finding.
- **What is read against which skill:**
  - `java-code-review` and `clean-code`: all of `src/main` - `cache` (`ReviewCache`, `Changes`), `gate`
    (`Gate`, `Waiting`, `Review`, `Project`, `PendingPush` and the exceptions), `ide` (`PoreloWatch`,
    `WaitingTree`, `ReviewTab`, `ReviewEditor`, `Decisions`, `Snapshot` and the factories) and `machine`
    (`Machines`, `Machine`, `Tunnel`, `WslBridge`).
  - `concurrency-review`: what runs off the event dispatch thread and what it shares with it - `PoreloWatch`'s
    timed read, `WaitingTree` and `ReviewTab` handing results back, `Decisions`, and the processes `Tunnel`,
    `WslBridge` and `ReviewCache` start and wait for, with their timeouts and what an interrupt or a closed
    project leaves running.
  - `security-audit`: every value from outside that reaches a command line, a file or a decision - names,
    commits and paths from the daemon passed to `git`, `ssh` and `wsl.exe`, the machines file, the cache's
    directory - and `build.gradle.kts`, `settings.gradle.kts`, the workflows and `plugin.xml`.
  - `test-quality`: all of `src/test`, and which main code has no test at all (today every class in `ide`).
- **Steps:** one package at a time, `machine`, `gate`, `cache`, then `ide`, each skill in turn. A finding is
  first reproduced by a unit test that fails, then fixed; one that cannot be reproduced is written under
  *Findings* as a guess and declined with what was tried. Each fixed finding that changes what ships gets its
  changelog entry.
- **The test that goes red first** is each finding's own where the finding names a harm it prevents, as the shared
  rule on what is tested asks; a finding that only reads wrong is fixed without one. There is none for the reading
  as a whole.
- **No interface another repository or a user's IDE reads changes:** not the daemon's varlink calls as the plugin
  makes them, the machines file `porelo-frontend` writes, the plugin's id or its settings keys. A finding that would
  change one is named in the channel, not built.

## Acceptance

- *Findings* below lists every finding by skill and file, each with its outcome: fixed (with the test's name) or
  declined (with the reason).
- Every fixing test was seen to fail on the code before its fix.
- `./gradlew build`, `./gradlew verifyPlugin` and `./gradlew documents` green, and the tests tagged `live` run if
  a fix changes how the plugin speaks to a daemon.
- The hand-over names each skill with the version and SHA-256 that were read.

## Findings

Read on `8c20f8a` against the five skills at 2026.906.3, each package's SHA-256 matching the table. Each is a guess
until its test is seen red; none is fixed before [IJ08](README.md#ij08)'s reformatting. Named by class and method, as
the reformatting moves the lines.

**Fixed without a test**, as each only reads wrong or costs time and names no harm a test would prevent: 13 (a
cancelled read calls machines silent), 15 and 16 (what a refusal's message says), 17 and 29 (work done twice), 20 (a
close that fails after the call is reported as the call failing), 21 (two groups shown as one) and 24 (a push
announced again). Their *Test* lines below are struck by this. Every other finding names its harm: a hang, a process
left running, a fetch or a decision sent where it should not go, a review that shows less than was pushed, a crash,
or a token or markup from the agent reaching the person.

**`cache` and `machine`**

1. *concurrency* - `ReviewCache.run` reads git's output to its end before `waitFor`, so the 120 s limit never
   fires and a fetch to a host that never answers blocks the review for good. Test: an injectable limit and
   `GIT_SSH_COMMAND=sleep 600` against `ssh://x/y`; `fetch` refuses within the limit.
   **Fixed**, `ReviewCacheTest.aFetchThatHangsEndsWithinItsLimit`: every stream is read on a thread of its own, so the wait is what bounds git.
2. *concurrency* - an interrupt or a timeout in `ReviewCache.run` leaves git, and the ssh it started, running.
   Test: interrupt the caller; no git or ssh is left among the test's descendants.
   **Fixed**, `ReviewCacheTest.anInterruptedFetchLeavesNothingRunning`: git and what it started are ended on a timeout and an interrupt.
3. *security-audit* - the fetch does not stop ssh from asking at a terminal (no `BatchMode=yes`, as `Tunnel`
   has). Checked on the VM, as a prompt needs a terminal.
   **Fixed**, `ReviewCacheTest.sshIsNeverLeftToAsk`: ssh runs with `BatchMode=yes` unless the person named an ssh of their own (`GIT_SSH_COMMAND`, `GIT_SSH`, `core.sshCommand`).
4. *java-code-review* - `ReviewCache.file` reads every git failure as "no such file", so a broken cache shows a
   changed file as added or deleted. Test: `file` on a missing repository refuses.
   **Fixed**, `ReviewCacheTest.aFileOfABrokenCacheIsRefused`: the commit is verified first, then every file of a side is read by one `git cat-file --batch`, which says `missing` for an absent file and nothing else.
5. *java-code-review* - `ReviewCache` reads every `merge-base` failure as "never met" (exit 1) and diffs
   against the tip. Test with a git that exits 128.
   **Fixed without a test**: exit 1 is "never met", anything else a failure. A repository where `merge-base` fails otherwise while `rev-parse` works could not be made in a test.
6. *concurrency* - `WslBridge.open` deletes whatever lies at its socket, a live bridge too: a second IDE, or a
   machine whose name slugs alike, cuts the first one off for good. Test: two bridges on one path; the first still
   answers, or the second is refused.
   **Fixed**, `WslBridgeTest.aSecondBridgeLeavesTheFirstAlone`: a socket something answers at is left alone, as `Tunnel` does.
7. *java-code-review* - `WslBridge` reads `wsl -l -v` in English; a German Windows lists no running distribution.
   Test with the localized output, once measured.
   **Fixed**, `WslBridgeTest.readsTheListInAnotherLanguage`: the name is the first word and the version the last; a state that is neither `Running` nor `Stopped` is asked of `wsl.exe -l --running -q`. Seen against the stand-in only; a Windows machine in another language is [IJ03](README.md#ij03)'s to see.
8. *security-audit* - `Machines` accepts two machines of one name; the second is read and decided through the
   first one's tunnel. The interface never writes such a file and drops the second entry of a name when it reads
   one; the plugin lists it as unreachable, with why, as it lists the other entries it cannot use. Test: the second
   of two machines named alike is unreachable and never connected.
   **Fixed**, `MachinesTest.theSecondMachineOfANameIsNotReached`.
9. *security-audit* - `Machines` takes `socket` and `remoteSocket` without checking they are absolute paths;
   `localhost:22` makes the tunnel forward to a port. Test: either one relative is refused.
   **Fixed**, `MachinesTest.refusesASocketThatIsNoPath`.
10. *java-code-review* - a throwing `Tunnel.close` or `WslBridge.close` skips ending its relays and, in
    `PoreloWatch.dispose`, every tunnel after it. Test: a close that fails still ends its processes.
    **Fixed without a test**: a close that fails cannot be provoked in a unit test. `WslBridge.close` ends its relays whatever else fails, and `Connections.close` closes each connection even if another fails.
11. *security-audit* - `WslBridge` binds its socket, then sets its mode and never checks it, unlike `Tunnel`.
    Test: owner-only under umask 002.
    **Fixed without a test**: the mode is read back after it is set, as `Tunnel` does; a socket whose mode does not take cannot be made in a test.

**`gate`**

12. *concurrency* - no gate call has a deadline, and `porelo-wire`'s client reads without one: a daemon that
    accepts and never answers stops `Waiting.read` for every machine after it, holds `PoreloWatch`'s lock, and so
    hangs closing the project. Test: a stand-in that never replies beside a healthy one; the read ends with the
    first silent and the second listed. Fixed in the plugin with a deadline per machine; a deadline in
    `porelo-wire` itself is `porelo`'s issue *A Varlink Call Has A Read Deadline*, linked here once it is pushed,
    and no contract changes.
    **Fixed**, `GateTest.aDaemonThatNeverAnswersIsLeft`: a read answers within 30 s or is given up. Approving and rejecting are not bounded here: the daemon bounds its own push to the forge.
13. *concurrency* - an interrupted `Waiting.read` records every machine left as "does not answer" and returns
    that snapshot. Test: an interrupted read throws, or names no machine silent.
    **Fixed without a test**: an interrupted read is given up whole.
14. *security-audit* - `Waiting.Entry.source` puts the daemon's `mirror` into the fetch address unchecked:
    `@evil.example/x` sends the fetch to another host with the person's ssh agent, `:2222/x` to another port,
    `/../Other/x` to another WSL distribution, and an empty one matches every path. Test: each is refused.
    **Fixed**, `WaitingTest.aMirrorThatLeadsElsewhereIsRefused`: the mirror is an absolute path without `..`.
15. *java-code-review* - `Gate.refusal` for `MovedSinceReview` without its fields says only that a field is
    missing, losing the refusal's name and its cause. Test: the message names it and keeps the cause.
    **Fixed without a test**.
16. *java-code-review* - a malformed answer is copied whole into the message the person reads, a megabyte diff
    too. Test: the message is bounded and holds no diff.
    **Fixed without a test**: an answer is cut to 300 characters in a message.
17. *java-code-review* - every read fetches every waiting push's whole diff to count its far-reaching files,
    which cannot change while its name and commit stay. Test: a second read of the same push asks no `Review`.
    **Fixed without a test**: a push at the same commit takes its count from the read before.
18. *java-code-review* - `BranchExistsException.next` overflows on a long number. Test: `b-99999999999999999999`.
    **Fixed**, `GateTest.theNameOfferedCountsOnPastALong`.
19. *java-code-review* - `Gate` reads a missing `own` as false, an `asked` of the wrong type as a task gone, and
    truncates `pending` (2^32 becomes 0, so its repository is skipped). Test: each is refused.
    **Fixed**, `GateTest.aRepositoryReadWrongIsRefused`; an `asked` that is no text is refused too.
20. *java-code-review* - a `Gate.close` that fails after a successful call marks a machine read in full as
    silent, and says "approve failed" after "forwarded". Test through a seam for `close`.
    **Fixed without a test**.
21. *clean-code* - `Waiting` groups by a joined label, so project `a / b` with repository `c` and project `a`
    with `b / c` share a group. Test: two groups.
    **Fixed without a test**.

**`ide`**

22. *java-code-review* - an unchecked exception out of `PoreloWatch.read` ends the scheduled reads for good,
    unlogged, and the tool window keeps showing the old snapshot. Test on the read moved into a plain class.
    **Fixed**, `SnapshotTest.aReadThatFailsIsSaid`; a notice or a listener that fails is logged and the reads go on.
23. *concurrency* - after `PoreloWatch.dispose`, a `readNow` (as `Decisions` calls after a decision) still
    starts ssh or wsl.exe, left behind when the IDE exits. Test on the connections moved into a plain class:
    after close nothing starts.
    **Fixed**, `ConnectionsTest.raisesNothingOnceClosed`: the tunnels and bridges moved out of the service into `Connections`.
24. *java-code-review* - `PoreloWatch` compares with the read before only, so a machine silent for one read has
    every push announced again when it answers. Test: `newSince` across a silent read announces nothing.
    **Fixed without a test**: `Waiting.seenAfter` keeps what was announced across a read in which its machine did not answer.
25. *security-audit* - notifications carry the agent's commit subject and the daemon's text as HTML, unescaped,
    so the agent under review can shape the notice. Test on the notice's text: markup arrives escaped.
    **Fixed**, `NoticesTest.markupFromTheAgentIsText`: the notice's words moved into `Notices`, every name escaped.
26. *concurrency* - a double-click in `WaitingTree` opens two reviews and runs two fetches into one cache at
    once; one fails on git's lock. Test: two fetches into one repository at once both succeed, or one waits.
    **Fixed**, `ReviewCacheTest.twoReviewsAtOnceBothRead`: fetches into one cache take turns. A review open or being read is shown, not read again; no unit test reaches the editor.
27. *concurrency* - `ReviewTab`'s task is cancellable but never looks at its indicator, so *Cancel* does nothing
    while a fetch hangs. Test with finding 1's fake ssh.
    **Fixed**: *Cancel* interrupts the read, which ends its git and ssh as finding 2's test shows.
28. *java-code-review* - `ReviewTab` lists only the files the cache's diff has; a file the daemon ranked
    far-reaching but the diff lacks is left out without a word. Test on the merge moved into a function.
    **Fixed**, `ReviewTabTest.aRankedFileTheDiffLacksIsRefused`: refused, to be read again.
29. *java-code-review* - opening a review runs two git processes per file and keeps every file in memory while
    the tab is open. Test: a constant number of git calls per review.
    **Fixed without a test**: one `git cat-file --batch` per side.
30. *concurrency* - after a decision, `Decisions` and `ReviewEditor` queue work on the event thread without
    `project.getDisposed()`, so a project closed meanwhile throws. Fixed as `ReviewTab` does; no unit test reaches
    the event queue, so it is declined as a test and checked by reading.
    **Fixed without a test**, as said.

**Tests** (*test-quality*)

- `TunnelTest.endsSshThatNeverForwards` cannot fail: its fake never makes the socket it asserts is gone. It
  asserts that the ssh process ended instead, seen red with the `close` in `raise` removed. **Repaired.**
- `WslBridgeTest` counts descendants that may not have ended yet, and leaks its bridge when an assertion fails
  before `close`: made to wait for them and to close in every case, as a flaky guard of a left process is no guard.
  **Repaired.**
- `NoOldNameTest` runs git without `GIT_CONFIG_GLOBAL=/dev/null`, so a developer's configuration can change what
  it reads. **Repaired.**
- What the reading found untested gets a test only where a finding above names a harm; `TunnelTest`'s weak
  `replacesAStaleSocket` and the untested `ide` classes, rejections without `told` and `Machine`'s own checks are
  left as they are.

**The build** (*security-audit*)

31. `actions/checkout` keeps the read-only token in `.git/config` while the build's code runs, in `build.yml` and
    `shared-rules.yml`; no step pushes with git, so `persist-credentials: false` drops it with nothing lost. Red
    first: `checkActions`, once it runs the tools of `porelo-project` [PJ29](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj29)'s shared part, refuses both steps.
    **Fixed** in [IJ08](README.md#ij08)'s reformatting commit, as the tools of that shared part refuse it in `build`;
    seen red there.

That the Gradle build verifies no dependency's digest is an issue of its own, [IJ09](README.md#ij09).

## Open questions

- None.
