# IJ29 — An open review sees its push move within seconds

```json
{
  "$schema": "issue-1.json",
  "id": "IJ29",
  "title": "An open review sees its push move within seconds",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 7,
  "blockedBy": [],
  "covers": "An open review tab says its push moved to another commit within seconds, as a new push is announced, once the daemon's stream of projects names something that changes with any waiting push's commit; today only the once-a-minute read shows a move"
}
```

**What must be true.** A person with a review tab open learns within seconds that its push moved to another commit,
as they learn of a new push: the tab is marked and a notice is shown.

## Why

The daemon's stream of projects carries, per repository, only the count of waiting pushes. A push that moves to
another commit changes no count, so the stream sends nothing new and the tab is marked only at the next read, within a
minute. A push decided elsewhere already marks the tab within seconds, since the count changes.

## The shape

- It needs the daemon's stream of projects to carry, per repository, something that changes whenever any waiting push
  changes its commit: each waiting push's commit, or one value that changes with any of them.
- Once it does, `Waiting.pendingMoved` compares that field too, where the daemon sends it, and the watch reads that
  machine again within seconds of the move; `pendingMoved` red first on two replies that differ in that field alone.
