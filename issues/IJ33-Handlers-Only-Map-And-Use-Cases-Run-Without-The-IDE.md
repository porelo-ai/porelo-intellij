# IJ33 — Handlers only map, and use cases run without the IDE

```json
{
  "$schema": "issue-1.json",
  "id": "IJ33",
  "title": "Handlers only map, and use cases run without the IDE",
  "state": "Idea",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Each of the plugin's IDE handlers (a dialog's answer, a button, a notification's action, a background task) only reads its input, calls one use case and shows the answer; reading a review, approving, rejecting and fetching a push into an open project run in classes that know no IntelliJ type and are tested with a fake daemon"
}
```

**Why this size.** Several classes of `ide` move their work into a new package with tests of its own; no contract
with the daemon changes and nothing a person sees changes.

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
  `place` are tested on their own, but the sequence is not.
- `ide/IntoProjects.open` and `decided`: fetch a push into a person's repository, and after a decision fetch its
  remote and delete the ref, between notifications.
- `ide/PoreloWatch`: the reading, following and caching service also builds the notifications and their actions
  (`tell`), so the service knows the UI.

Steps: a package `org.fuin.porelo.intellij.usecase` with `ReadReview` (gives back today's `ReviewTab.Read`
without `into`), `Decide` (`approve` returns *forwarded*, *branch taken with the next name* or *moved*; `reject`
returns *told* or *not told*) and `IntoRepository` (fetch, and the update after a decision). They take the gate,
the cache and the repositories as small interfaces, never `Project`, `ProgressManager` or `Messages`. The
handlers in `Decisions`, `ReviewTab`, `IntoProjects` and `ReviewEditor` keep the dialogs, progress and
notifications and map the outcome to them; `PoreloWatch` publishes snapshots, and `ide` turns them into
notifications. The test that goes red first is `DecideTest` against `FakePorelo`: a moved push and a taken branch,
each an outcome, never an exception the handler must catch.

## Acceptance

- `usecase` imports no `com.intellij` class outside `com.intellij.diff.util.Side`, which an architecture test
  checks and fails on the first import found.
- `ReadReviewTest`, `DecideTest` and `IntoRepositoryTest` run in the plain test task against `FakePorelo` and a
  temporary git repository; each fails when its outcome is turned around.
- The live tests and a walk of approving, rejecting and opening a push behave as before.

## To be checked

- Whether `ReviewTab.Read`'s records move with `ReadReview` or stay in `ide` and are mapped.
