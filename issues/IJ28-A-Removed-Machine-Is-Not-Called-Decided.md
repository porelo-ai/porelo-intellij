# IJ28 — A removed machine is not called decided

```json
{
  "$schema": "issue-1.json",
  "id": "IJ28",
  "title": "A removed machine is not called decided",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 3,
  "blockedBy": [],
  "covers": "A review tab whose machine was taken out of the interface's list while it was open stays as it was, never marked *no longer waits*: a read records which machines it asked, and a push of a machine it did not ask is unknown"
}
```

**Why this size.** one class in `gate` and its unit tests, no contract and no change to the review tab.

**What must be true.** A person whose review tab is open while its machine is taken out of the interface's list is
never told that the push was decided elsewhere: the tab stays as it was, since nothing about that push was read.

## Why

`Waiting.now` says *gone* for a push whose machine is neither among the silent nor has a push of that name. A machine
the interface no longer lists is not read at all, so it is neither, and the tab says *This push no longer waits: it was
decided elsewhere.*, disables *Approve* and *Reject* and offers *Close*: the person is told a push was decided that
still waits.

## Plan

Read in today's code: `Waiting` (`src/main/java/org/fuin/porelo/intellij/gate/Waiting.java`) is the record
`(pushes, silent)`; `now(Entry shown)` returns `Unknown` only for a silent machine, otherwise `Same`, `Moved` or
`Gone`. Every read builds it in `read(List<Machine>, ...)`, `read(Machines.Listed, ...)`, `withMachine`, `readOne`
and `seenAfter`; `Snapshot.now` and `ReviewEditor` take its answer as it is.

1. **Red first, in `WaitingTest`:** a `Waiting` read over machines that do not include the shown push's machine
   answers `now(shown)` with `Unknown`, not `Gone`; a machine that was asked and answered with nothing still answers
   `Gone`.
2. **`Waiting`** gains a third component, the names of the machines the read asked; `now` answers `Unknown` for a
   machine not among them. Each read sets it from the machines it asked, `withMachine` and `readOne` add the one
   machine read, `seenAfter` keeps the read's own. A two-argument constructor takes the machines of the pushes and the
   silent as asked, so the existing tests and `Snapshot.NONE` stay as they are.
3. `ReviewEditor` and `Snapshot` are unchanged: `Unknown` already leaves the tab as it was.

All of it is unit level, over `WaitingTest`'s fakes; no VM is needed.

## Acceptance

- `WaitingTest` goes red on the removed machine before the change, and green after; the asked-and-empty case stays
  `Gone`.
- `build` and `verifyPlugin` pass.
