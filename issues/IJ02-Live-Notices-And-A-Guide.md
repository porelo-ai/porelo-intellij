# IJ02 — Live notices and a guide

**Status:** Implementing.
**Size:** M - a stream per machine in the gate and the watch, a `doc/` chapter, and a measured acceptance on a
VM.

**What must be true.** A push that starts to wait is announced within seconds, not at the next minute's read; and
how to install and use the plugin is written down where the other chapters are.

## The shape

### 1. Notices that come when the work does

- The watch follows `WatchProjects` with `more` and asks `Pending` only for a repository whose count changed, so
  a new push is announced within seconds; the timed read every minute stays as the fallback and for a machine
  whose daemon does not stream.
- A stream that ends or a tunnel that drops is opened again, and the machine is shown as not answering meanwhile,
  never as having nothing.

### 2. A chapter for people

- `doc/` says what the tool window and the review tab show, what is far-reaching and why it comes first, how
  approving and rejecting work, where the cache lives and what it never does (no checkout, no build, no credential
  stored), and which machines it reaches (the interface's list, read only).
- Every page is in `mkdocs.yml`'s navigation once, as `check-doc-site` holds.

## Plan

1. **`Gate.watch(Consumer<List<Project>>)`** over `VarlinkClient.callMore("WatchProjects", …)`, which
   `porelo-wire` 0.5.0 has; it returns when the stream ends or the gate is closed. Red first in `GateTest`: a
   stand-in daemon sends two replies with `continues`, and both arrive in order. Read in porelo's daemon: called
   with `more`, `WatchProjects` scans every 3 s and sends the whole list only when it changed, so a push reaches
   the plugin at most 3 s after it arrives; asked `Pending` and `Review` for that one repository, the notice fits
   in the 5 s the acceptance names on a machine that answers in a second.
2. **`PoreloWatch`**: one virtual thread per reachable machine holds the stream; a reply whose `pending` differs
   from the last asks `Pending` for that repository and updates the snapshot, so the notice follows. A stream
   that ends marks the machine not answering and is opened again after a pause; the minute's read stays. Red
   first in a test of the change detection between two replies (a pure function, outside the IDE).
3. **`doc/index.md`**, the chapter that is already in `mkdocs.yml`'s navigation, grows by what a person does:
   the tool window, the review tab, far-reaching files, approve and reject, opening a push in its project, the
   cache, the machines. How to install the plugin from the custom repository URL is
   [IJ01](README.md#ij01)'s, where that URL is made (the operator's decision of 2026-10-19).
4. **Acceptance on a VM**, with a live test tagged `live` that times a push to its notice.

## To be checked

- That a stream held for hours through the plugin's ssh tunnel and through `porelo daemon connect` on Windows stays
  open, or is opened again within the minute's read when it drops: measured on the VM with the live test.

## Acceptance

- A push made in a task is announced in the IDE within 5 s, measured on a VM against a real daemon; with the
  stream cut, the next timed read still finds it.
- The chapter builds in the site's strict MkDocs build, and every step it describes has been done once as
  written.

