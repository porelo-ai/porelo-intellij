# IJ32 — The docs say what the code does

```json
{
  "$schema": "issue-1.json",
  "id": "IJ32",
  "title": "The docs say what the code does",
  "state": "Backlog",
  "size": "S",
  "priority": "Highest",
  "rank": 2,
  "blockedBy": [],
  "covers": "Every sentence of the user-facing documents - `README.md`, `CONTRIBUTING.md`, `SECURITY.md` and `doc/` - read against the code on main, and each one the code does not bear out fixed in the document, or filed as a bug where the document is right and the code wrong; no behavior changes"
}
```

**Why this size.** About 190 lines of documents read against the plugin's code; only documents change, and a bug the
reading finds becomes an issue of its own.

**What must be true.** A person who installs and uses the plugin by its documentation finds every command, path,
default, state, message and behavior it names as the plugin has it.

## Plan

1. A reader on another model reads `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `doc/index.md` and
   `doc/decisions.md` page by page against the code and the build on main, and lists every claim the code does not
   bear out, with the document's line, the claim, and the code that contradicts it.
2. Each finding is checked in the code; only a confirmed one is changed. Where the code is right, the document is
   fixed; where the document is right, the code's defect is filed as its own issue, Backlog, estimated.
3. No test: only documents change. The tests tagged `documents`, `check-citations` and `check-commits` pass.

## Acceptance

- Every finding is answered: confirmed and fixed, filed as a bug, or rejected with the code that bears the document
  out; the hand-over gives the counts.
