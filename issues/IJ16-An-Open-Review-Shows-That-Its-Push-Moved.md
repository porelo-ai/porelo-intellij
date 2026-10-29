# IJ16 — An open review shows that its push moved

**Status:** Analyzed.
**Size:** S - one pure function in `gate` with its tests, the review tab listening to the watch, one paragraph of
`doc/index.md`; no contract changes, and the faster half waits for the daemon.

**What must be true.** A review tab that is open while its push moves to another commit says so, as soon as the
plugin knows it: the tab is marked as showing a commit that no longer waits, and offers to read the push again.
Today its header stays at the commit it was opened with, *waiting 0m*, until *Approve* is refused.

## Why

The operator's walk found it: a task pushed again while its review was open, and the tab gave no sign until the
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

## To be checked

- That a listener registered with the editor as its parent is removed when the tab closes, read in IntelliJ's
  `Disposer` contract and seen in the running IDE.
