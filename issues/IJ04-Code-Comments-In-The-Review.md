# IJ04 — Code comments in the review

**Status:** Estimated; blocked by `porelo` [B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166) (the comment model and the local review) and, for its second
part, by `porelo` [B171](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b171) (comments to and from a forge).
**Size:** XL - writing and showing threads inside IntelliJ's diff windows is several days alone; it splits into
part 1 (L, with [B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166)) and part 2 (M, its own issue once [B171](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b171)'s contract is written).

**What must be true.** A developer who reviews waiting work in IntelliJ comments on its lines there, as on a
colleague's change, and reads in the same place what agents and people on the forge said about it, each marked with
where it came from; and they release what goes out to a forge, and what comes in from someone not on the project's
list, one comment at a time.

## The shape

### 1. Comments in the review tab

- In the review tab's diff, a line or a range on the old or the new side takes a comment, with an optional
  suggestion that holds replacement lines for exactly that range. It is sent through the daemon's contract; the
  daemon checks the anchor against the commit shown and refuses one that is not there, and the plugin says so in its
  words.
- The comments on a push are shown in IntelliJ's diff beside their lines, as threads, each with its origin: the
  person, an agent (which task, in which role: the developer's replies, or a task that reviews), or a forge account.
  A thread is marked open or resolved.
- A suggestion is shown as a small diff inside its comment. The plugin never applies one: the agent does, in its
  task, and the result comes back through the gate as new work.
- A reject sends the open comments to the task with the reason, as [B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166) says.

### 2. What crosses to and from a forge

- Comments that wait to go out to a pull request are listed per push, with their text and anchor. Each is released or
  dropped by the person; where a project has switched the release off, they are shown as gone out.
- Comments from forge accounts not on the project's list are shown as held, apart from the delivered ones; each can be
  released to the task singly.
- Every release is the daemon's, through its contract; the plugin holds no forge token.
- The interface does the same in `porelo-frontend` [F107](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f107); both read the comments from the same contract and show the same
  origins.

## Plan

- **Part 1, with [B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166)'s contract:**
  1. **`Gate`** speaks [B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166)'s `Comment`, `Comments` and `ResolveThread`, with its `Anchor` (repository,
     commit, path, side `old` or `new`, first and last line, at most 100 lines), a suggestion of at most the anchor's
     lines plus 10, and each comment's `CommentOrigin`. `AnchorNotInCommit`, `SuggestionOutsideAnchor` and
     `CommentTooLarge` become exceptions in words, as `BranchExistsException` does. Red first in `GateTest` against
     a stand-in daemon.
  2. **`ReviewEditor`**: a `DiffExtension` on the `DiffRequestPanel`'s viewer adds a gutter action "Comment" on a
     line or a selection of either side, and shows each thread as a block inlay below its lines; a suggestion is
     a small read-only diff inside its inlay. Nothing writes to a document or a file.
  3. **Grouping threads to lines** is a pure function, tested outside the IDE. A thread stays on the commit it
     names, so one of an earlier push of the same task is shown apart, as on an earlier commit: red first with such
     a thread.
  4. **`Decisions`**: a reject needs nothing new, as the daemon delivers the open threads with the reason; the
     notice says what its `told` says.
  5. The acceptance on the VM against a daemon with [B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166), as a live test.
- **Part 2, with [B171](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b171)'s contract:** a list per push of what waits to go out and what is held, each with Release
  and Drop, through the gate; no forge token in the plugin.

## Acceptance

- On a VM against a real daemon: a comment written on a line in the review tab reaches the task; one on a line the
  commit does not have is refused in words; the agent's reply appears in the thread with its origin. Each seen to fail
  first against today's plugin.
- A suggestion is shown as a diff and the working tree of the project open in the IDE is unchanged afterwards.
- With [B171](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b171): a held forge comment released in the IDE reaches the task, and an outgoing one released there reaches the
  pull request; one dropped goes nowhere.

## To be checked

- Whether IntelliJ 2026.1's simple diff viewer takes block inlays through `DiffExtension`, or the review needs
  a viewer of its own: measured before step 2.
