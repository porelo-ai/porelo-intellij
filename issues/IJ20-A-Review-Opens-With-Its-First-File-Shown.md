# IJ20 — A review opens with its first file shown

```json
{
  "$schema": "issue-1.json",
  "id": "IJ20",
  "title": "A review opens with its first file shown",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 2,
  "blockedBy": [],
  "covers": "A review tab always opens with its first file selected and its diff shown; twice in eleven it opened with the file unselected and the diff empty"
}
```

**What must be true.** A review tab always opens with its first file, the most far-reaching, selected and its diff shown,
so a person never faces an empty diff and has to click a file to start reading.

## Why

Seen once, headless, with eleven pushes opened one after the other: two opened with their one file listed but not
selected and the diff empty, still so 7 s later and when brought to the front again; one click on the file showed the
right diff, and `idea.log` said nothing. Both were ordinary one-file changes. `ReviewEditor` selects the first file
with `invokeAfterRefresh` once the background tree is built, so a race there is a guess; whether it is random or tied
to a push was not measured.

## To be checked

- Reproduce it first: the same pushes opened repeatedly, to tell a race from a property of the push.
