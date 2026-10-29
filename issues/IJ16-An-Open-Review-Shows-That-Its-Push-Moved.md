# IJ16 — An open review shows that its push moved

**Status:** Review.
**Size:** S - one pure function in `gate` with its tests, the review tab listening to the watch, one paragraph of
`doc/index.md`; no contract changes, and the faster half waits for the daemon.

**What must be true.** A review tab that is open while its push moves to another commit says so, as soon as the
plugin knows it: the tab is marked as showing a commit that no longer waits, and offers to read the push again.
Today its header stays at the commit it was opened with, *waiting 0m*, until *Approve* is refused.

## Why

A walk through the plugin found it: a task pushed again while its review was open, and the tab gave no sign until the
approval failed. The refusal keeps a moved commit from being forwarded, so nothing wrong goes through; the person
only learns late that they read an old commit.

## Plan

Read in today's code: `ReviewEditor` builds the tab once from the `Waiting.Entry` it was opened with and never looks
again; `PoreloWatch` publishes a `Snapshot` to listeners (`listen(Consumer<Snapshot>, Disposable)`) after every read -
once a minute, after every decision (`readNow`), and for one machine when its stream of projects shows a changed count;
each `Waiting.Entry` carries its push's commit.

1. **Red first, in `WaitingTest`:** a pure function `Waiting.now(Entry shown)` that says what the push of an open tab
   is in a newer read: *the same* (same machine, project, repository, name and commit), *moved* (same name, another
   commit, which it returns), *gone* (no push of that name, while its machine answered), or *unknown* (its machine did
   not answer, or nothing has been read). The tests that go red first: a moved commit is said with the new one; a
   push missing from a machine that did not answer is *unknown*, never *gone*; a push of the same name on another
   machine, project or repository is not taken for it.
2. **`ReviewEditor`** listens to the watch with the editor as the parent, so the listening ends with the tab. On
   *moved* or *gone* it shows, on the UI thread, a line above the header - *This push moved to `<12 digits>` after it
   was opened; what is shown no longer waits.* or *This push no longer waits: it was decided elsewhere.* - with *Read
   Again* beside it, and disables *Approve* and *Reject*, which the daemon would refuse. *Unknown* changes nothing.
3. **`doc/index.md`**, *Reading a push*: one sentence that an open review says when its push moved or was decided
   elsewhere, within a minute.
4. **Changelog:** one entry under `changelog/unreleased/`, type *changed*.

**What it can do with today's once-a-minute read:** the tab is marked at the next read, so within 60 s of the move,
or at once after any decision made in this IDE. The daemon still refuses an approval of the old commit before that.
A machine with an open review is read no more often than any other: the minute is enough, as decided, until the
daemon's stream names moves.

**What only comes when the daemon's stream of projects names what waits, not only how much:** today a reply of that
stream carries, per repository, the count of waiting pushes, and a push that moves to another commit changes no count,
so it sends nothing new. When a reply also carries something that changes whenever any waiting push of that
repository changes its commit - each waiting push's commit, or one value per repository that changes with any of
them - `Waiting.pendingMoved` compares that too, the watch reads that machine again within seconds of the move, and the
tab is marked and a notice shown within seconds, as for a new push. That part is an XS step of its own once the
daemon publishes such a field: the field read where present, and `pendingMoved` red first on two replies that differ
in it alone.

## Acceptance

- Measured on a VM against a real daemon: with a review tab open, the task pushes again; the tab says it moved within
  60 s, names the new commit, and *Approve* is disabled; *Read Again* shows the new commit.
- A push decided elsewhere, with `porelo gate reject` at the machine, marks an open tab *no longer waits* at the next
  read.
- With the machine's daemon stopped, an open tab stays as it was: no *moved*, no *gone*.

## Built differently from the plan

- **A push decided elsewhere offers *Close*, not *Read Again*:** nothing of it is left to read, and reading it
  again only failed.
- ***Read Again* opens the commit the push moved to**, from the line and from the toolbar. Before, it read the
  commit the tab was opened with, which the daemon refused once the push had moved. For a push decided
  elsewhere, the toolbar's *Read Again* is disabled too.
- **A tab is marked as it opens** when the watch already knows, so a push that moved while it was read, or a
  split of a marked tab, is not shown as decidable until the next read.
- **The mark lifts** when the push waits at the commit shown again, such as after a task reset and pushed the
  same commit, so a commit that waits is never shown as one that does not.

## Checked

On `fedora` against its daemon, in `runIde` on a private Xvfb display reached through an ssh tunnel to the
daemon's socket; screenshots in `~/.porelo/handover/porelo-intellij/ij16/`.

- **Moved:** a task pushed again while its review was open. The tab named the new commit and disabled *Approve*
  and *Reject* 33 s and 52 s after the push, in two tries. *Read Again* opened the new commit, with both decisions
  enabled again.
- **Decided elsewhere:** `porelo gate reject` at the machine marked the open tab *no longer waits* 2 s later,
  since the stream's count changed. *Close* closed it.
- **Daemon stopped:** `systemctl --user stop porelod` with a review open, and a read forced. The tool window said
  `fedora` does not answer; the tab stayed as it was for 83 s.
- **Each condition of `Waiting.now` and `Snapshot.now` has a test that goes red without it:** the name of the
  push, the silent machine being its own, and the guard against no read or a failed one. Each was broken
  once and seen red.
- **The listener ends with the tab:** it is registered with the editor as its `Disposable` parent, which
  IntelliJ disposes when the tab closes. Each mark is also dropped once the editor is disposed. No failed
  listener was logged after tabs were closed.

## Left open

- **A machine taken out of the interface's list while a review of it is open** is neither silent nor answering
  in the next read, so the tab would say *no longer waits*. A read does not record which machines it asked;
  that would take a field in `Waiting`, more than this S.
