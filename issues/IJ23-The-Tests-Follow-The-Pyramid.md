# IJ23 — The Tests Follow The Pyramid

**Status:** Accepted.

**What must be true.** Every flow of the plugin - its states, choices, limits and failure paths - is proven by unit
tests over fakes that run in milliseconds. Integration tests keep only what a real socket, git, process or IDE adds,
and the live runs against a real daemon keep only what nothing lower can show. The unit suite runs in under a
minute.

## The suites today

Measured on the development machine on `378feff`: `./gradlew test --rerun ideTest --rerun` ran 144 tests in 2 min 1 s
wall, none failed and none skipped. The times are each class's own, from Gradle's reports. Levels follow the shared
rule: unit is one class with no outside system; integration is a real socket, git, process or IDE; end-to-end is the
plugin against a real daemon on a machine.

| level | tests | time | classes |
|---|---|---|---|
| unit | 32 (20 %) | 0.9 s | `MachinesTest` 14, `OpenProjectsTest` 5, `LinesTest` 4, `SnapshotTest` 2, `DocumentTestsTaggedTest` 2, `NoticesTest`, `ConnectionsTest`, `NullMarkedPackagesTest`, `ReviewAdapterAloneTest`, `UpdatePluginsTest` 1 each |
| integration, stand-in daemon on a real unix socket | 69 | 3.2 s | `GateReviewTest` 27, `GateTest` 18, `WaitingTest` 14, `ReviewTabTest` 10 |
| integration, real git and shell stand-ins | 20 | 11.1 s | `ReviewCacheTest` |
| integration, stand-ins for `ssh` and `wsl.exe` in a JVM of their own | 18 | 67.9 s | `WslBridgeTest` 8 (56.0 s), `TunnelTest` 6 (5.8 s), `RelayTest` 4 (6.1 s) |
| integration, a headless IntelliJ IDEA (`ideTest`) | 5 | 14.7 s | `ReviewDiffExtensionTest` 2, `EditorModelTest` 2, `ThreadViewTest` 1 |
| integration, Windows' `ssh.exe` and OpenSSH (`windowsSshTest`, Windows job only) | 3 | not run here | `WindowsSshRelayTest` |
| end-to-end, a real daemon on a VM (`liveTest`, by hand) | 10 | not run in this audit | `LiveGateTest`, `LiveHostKeyTest` 3, `LiveIntoProjectTest`, `LiveNoticeTest`, `LiveRelayOverSshTest`, `LiveStreamHoldTest`, `LiveTakenBranchTest`, `LiveWslBridgeTest` |

Of 157 tests, 32 are unit (20 %), 115 integration (73 %) and 10 end-to-end (6 %). The unit target is about 70-80 %
and integration about 15-20 %. The `test` task, which holds the unit and most integration tests, takes 83 s, so it
is over the minute; `WslBridgeTest` alone is 56 s.

## Flows proven only above the unit level

- **Following a machine's stream of projects** (`PoreloWatch`): open the stream, read again when what waits moves,
  and on an end read once, wait `REOPEN_SECONDS` and reopen until the IDE closes. Only `LiveStreamHoldTest` covers
  it, and that test copies the loop instead of running `PoreloWatch`'s own, so the plugin's loop is tested nowhere.
- **Approve and Reject** (`Decisions`): a taken branch asks again with the next name offered, a push that moved
  closes the review so it is read again, a blank branch or reason does nothing, and a decided push goes to
  `IntoProjects`. Nothing tests this. `GateTest` covers only the gate's refusals, and `LiveTakenBranchTest` only the
  daemon's.
- **A decided push leaving the person's projects** (`IntoProjects.decided`): its `porelo/<push>` ref is deleted,
  and the remote is fetched only when the push was approved. Only `LiveIntoProjectTest` covers this; the git steps
  alone are in `ReviewCacheTest`.
- **Choosing a WSL distribution** (`WslBridge`): a running WSL 2 one is taken, also when Windows names its state in
  another language; WSL 1, a distribution the machine lacks and a stopped one are refused in words, and a stopped
  one is never started; a second bridge to an open one is refused. Each case starts the `wsl.exe` stand-in as a JVM
  of its own, several times.
- **What `ssh` is asked and how its refusal is said** (`Tunnel`, `Relay`): no prompt, the remote socket quoted as
  one word, the card's configuration only, and ssh's own words passed on. Each case starts the `ssh` stand-in as a
  JVM of its own.
- **Reading the gate's answers** (`Gate`, `Waiting`, `ReviewTab`): ranking, refusals turned into their exceptions,
  what moved since the review, and the review tab's state. Their 69 tests are fast but go through a real unix
  socket, which the logic does not need.

## Tests that wait where a fake would do

- `WslBridgeTest` and `TunnelTest` poll the process table in `Thread.sleep(100)` steps for up to 10 s, waiting for a
  stand-in to start or end.
- `ReviewCacheTest` waits out a real 2 s fetch limit, and polls for the stand-in's git and ssh in 50 ms and 100 ms
  steps.
- `GateTest` waits out a real 2 s read deadline against a daemon that never answers.

No test reaches the network: `ssh://example.invalid` goes only to stand-ins that refuse.

## Plan

Each fix stands on its own; the order is by harm, then by time saved.

- **S - the stream follower as plain logic.** Move `PoreloWatch`'s per-machine loop into a class that takes the
  gate as a small interface, plus a sleeper and a disposed flag. Unit tests over fakes cover: an end reads once and
  reopens, a move of what waits reads again, disposal stops it with no reopen, and a failed read never ends the
  loop. `LiveStreamHoldTest` then drives that class instead of its copy. Red first: a test that the loop stops on
  disposal, run against a copy with the check removed.
- **S - the decision flow as plain logic.** Move what `Decisions` and `IntoProjects.decided` decide behind small
  interfaces for asking the person, the gate and the project's git. Unit tests cover the taken branch's re-ask with
  the next name, the moved push closing the review, a blank answer doing nothing, and the ref deleted with the
  remote fetched on approval only. The IntelliJ dialogs and background tasks stay a thin shell.
- **S - the WSL choice as plain logic.** Parse `wsl.exe -l -v` and choose the distribution in a class of
  its own, with unit tests over captured lists, another language among them. `WslBridgeTest` keeps two process
  tests: the bridge carries the daemon's bytes, and closing it ends every `wsl.exe`. That takes the `test` task
  under a minute.
- **XS - ssh's command and its refusal as plain logic.** Unit tests on the built command lines and on how ssh's
  words are passed on. `TunnelTest` and `RelayTest` keep forwarding, the owner-only socket and cleanup.
- **S - the gate read without a socket**, if porelo-wire lets a `Gate` speak over an in-process connection: then
  `GateReviewTest`, `WaitingTest` and `ReviewTabTest` become unit tests, and `GateTest` keeps one test per real-socket
  concern (deadline, owner-only path).
- **XS - waits made short.** The fetch limit and the read deadline taken as parameters in milliseconds in their
  tests, and the process polls bounded at the stand-in's own exit, not at 10 s.

Expected after the fixes, a guess: about 75 tests at unit level, roughly 60 % of the suite, and the `test` task
under 30 s.

## Acceptance

- Every flow named above has unit tests over fakes, each seen red once.
- `./gradlew test --rerun` runs in under a minute, measured, and the share of unit tests is read again from
  Gradle's reports.
- No plan in this repository needs `liveTest` to see a flow work.

## To be checked

- Whether porelo-wire's `VarlinkConnection` can run over an in-process pipe, or a `Gate` can take one; if not, the
  socket fix is dropped or asked of porelo-wire's agent.
- The live suite's run time against a VM, measured once with the other fixes.
