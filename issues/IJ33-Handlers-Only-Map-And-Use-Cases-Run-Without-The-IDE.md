# IJ33 — Handlers only map, and use cases run without the IDE

```json
{
  "$schema": "issue-1.json",
  "id": "IJ33",
  "title": "Handlers only map, and use cases run without the IDE",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Each of the plugin's IDE handlers (a dialog's answer, a button, a notification's action, a background task) only reads its input, calls one use case and shows the answer; reading a review, approving, rejecting and fetching a push into an open project run in classes that know no IntelliJ type and are tested with a stand-in daemon"
}
```

**Why this size.** Four classes of `ide` give their work to three new use case classes, each with a test of its
own, and an architecture test is added; no contract with the daemon changes and nothing a person sees changes.

**What must be true.** Whoever changes how a push is read, approved, rejected or fetched into a project changes
one use case class and its test, which runs without an IDE; the IDE's handlers only ask, call and show.

## Plan

Read on `main`: these handlers carry a use case today.

- `ide/Decisions.approve` and `reject`: inside the background task they open the gate, approve or reject, handle a
  push that moved and a branch that exists (asking again with the next name), update the open projects through
  `IntoProjects.decided`, close the gate and read again. No test reaches them, the live tests included,
  which call the gate directly.
- `ide/ReviewTab.read`: reads the daemon's review, fetches into the cache, checks the base, orders the files, reads
  both sides, reads every run's comments and places the threads. The parts `based`, `ordered`, `comments` and
  `place` are tested on their own in `ReviewTabTest`, but the sequence is not.
- `ide/IntoProjects.open` and `decided`: fetch a push into a person's repository, and after a decision fetch its
  remote and delete the ref, between notifications.
- `ide/PoreloWatch`: the service that reads and follows the machines also builds the notices of new pushes and
  their *Review* and *Open in* actions (`tell`), so the service calls the handlers.

What the use cases build on is already free of the IDE: `gate`, `cache`, `machine` and `sarif` import no
`com.intellij` class; `review/Lines`, which placing threads needs, imports only `com.intellij.diff.util`'s
`LineRange` and `Side`, plain data the plain test task already loads.

Steps:

1. A package `org.fuin.porelo.intellij.usecase`. Its classes take a `Gates` (opens the `Gate` of a `Machine`, as
   `PoreloWatch.gate` does today) and the `ReviewCache`, and nothing of `Project`, `ProgressManager`, `Messages`
   or notifications.
2. `ReadReview.read(Waiting.Entry)`: the body of `ReviewTab.read`, with `based`, `ordered`, `comments`, `place`
   and the records `Read`, `Side`, `Discussion`, `Placed`, `Comments`, `Unread` moved along; `Read` loses `into`,
   which `ReviewTab` adds from the open projects. `ReviewTabTest` moves with them.
3. `Decide.approve(entry, branch)` returns a sealed outcome, *forwarded* (with the branch), *branch taken* (with
   the next name and the daemon's words) or *moved*; `Decide.reject(entry, reason)` returns *told* or *not told*.
   An unreachable daemon or a refusal stays a `GateException`. `Decisions` keeps the dialogs, the progress and the
   notices and maps each outcome to one of them.
4. `IntoRepository.fetch(entry, root)` and `IntoRepository.decided(entry, roots, approved)`, the second returning
   each root whose update failed, with git's words; `IntoProjects` keeps finding the open projects, focusing the
   window, refreshing the file system and the notices.
5. `PoreloWatch.tell` moves to `ide/NewPushNotices`, a listener of the watch, so the watch only reads, follows and
   publishes snapshots.
6. `UseCasesImportNoIdeTest` reads every source file of `usecase` and fails on the first `import com.intellij`
   outside `com.intellij.diff.util`, naming file and line.

The test that goes red first is `DecideTest`: a stand-in daemon on a unix socket, porelo-wire's `VarlinkServer` as
`GateTest` runs it, answers `Approve` with the branch-exists error, and the test expects the *branch taken*
outcome with the next name, which does not exist yet.

## Acceptance

- `ReadReviewTest`, `DecideTest` and `IntoRepositoryTest` run in the plain test task, against a `VarlinkServer`
  stand-in and a temporary git repository as `ReviewCacheTest` builds one; each fails when its outcome is turned
  around. Like `GateTest`, the ones that need a unix socket's owner-only permissions do not run on Windows.
- `UseCasesImportNoIdeTest` fails when an `import com.intellij.openapi.ui.Messages` is put into `Decide`.
- `./gradlew build`, `verifyPlugin` and the `live` tests pass; approving, rejecting, a taken branch and opening a
  push in a project behave in the IDE as before, checked once under Xvfb on the build box.

## To be checked

- Whether `LiveTakenBranchTest`, which calls the gate today, can call `Decide` instead, so the taken branch is also
  proven through the use case against a real daemon.
