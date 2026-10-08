# IJ02 — Live notices and a guide

**Status:** Estimated.
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

- `doc/` says how to install the plugin from the custom repository URL, what the tool window and the review tab
  show, what is far-reaching and why it comes first, how approving and rejecting work, where the cache lives and
  what it never does (no checkout, no build, no credential stored), and which machines it reaches (the
  interface's list, read only).
- Every page is in `mkdocs.yml`'s navigation once, as `check-doc-site` holds.

## Plan

1. **`Gate.watch(Consumer<List<Project>>)`** over `VarlinkClient.callMore("WatchProjects", …)`, which
   `sokar-wire` has; it returns when the stream ends or the gate is closed. Red first in `GateTest`: a stand-in
   daemon sends two replies with `continues`, and both arrive in order.
2. **`SokarWatch`**: one virtual thread per reachable machine holds the stream; a reply whose `pending` differs
   from the last asks `Pending` for that repository and updates the snapshot, so the notice follows. A stream
   that ends marks the machine not answering and is opened again after a pause; the minute's read stays. Red
   first in a test of the change detection between two replies (a pure function, outside the IDE).
3. **`doc/`**: a chapter "Reviewing in IntelliJ" (install from the custom repository URL, the tool window, the
   review tab, far-reaching files, approve and reject, the cache, the machines), in `mkdocs.yml`'s navigation;
   it waits for [IJ01](README.md#ij01)'s published URL.
4. **Acceptance on a VM**, with a live test tagged `live` that times a push to its notice.

## Acceptance

- A push made in a task is announced in the IDE within 5 s, measured on a VM against a real daemon; with the
  stream cut, the next timed read still finds it.
- The chapter builds in the site's strict MkDocs build, and every step it describes has been done once as
  written.

