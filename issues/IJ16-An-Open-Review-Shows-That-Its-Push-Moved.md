# IJ16 — An open review shows that its push moved

**Status:** Idea.

**What must be true.** A review tab that is open while its push moves to another commit says so, as soon as the
plugin knows it: the tab is marked as showing a commit that no longer waits, and offers to read the push again.
Today its header stays at the commit it was opened with, *waiting 0m*, until *Approve* is refused.

## Why

The operator's walk found it: a task pushed again while its review was open, and the tab gave no sign until the
approval failed. The refusal keeps a moved commit from being forwarded, so nothing wrong goes through; the person
only learns late that they read an old commit.

## To be checked

- How quickly the plugin can know: the daemon's stream of projects carries a count of waiting pushes per repository,
  which a push that only moves does not change, so the plugin learns of it at its read once a minute. Whether the
  daemon's contract can carry what changes when a push moves, such as each waiting push's commit, is asked of
  `porelo`'s agent; with it, the tab could be marked within seconds.
