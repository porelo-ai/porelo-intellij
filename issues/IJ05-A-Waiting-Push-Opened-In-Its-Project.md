# IJ05 — A waiting push opened in its project

**Status:** Analyzed.
**Size:** M - a pure match of open projects to a gate's upstream, a fetch into the person's repository that
touches no working tree, two actions and a notice, and a live acceptance on a VM; no contract change.

**What must be true.** A developer who has the push's repository open in IntelliJ reads a waiting push there with
the IDE's full help - navigation, search, the project's own code beside it - and after approving sees the forwarded
commit in that project without a fetch of their own; the review itself never needs it.

## The shape

- **Where the repository is open in an IDE window**, the notice and the review tab also offer *Open in
  <project>*: it brings that window forward and fetches the push there as `refs/remotes/porelo/<push>`. This is the
  one thing the plugin writes into a repository of the person's - a ref, never the working tree - and only when
  they choose it.
- **After approving**, the plugin fetches the remote that names the gate's upstream in that project, only when one
  is open for it, so the project sees the forwarded commit.
- **A decided push's ref goes**: after approve or reject, `refs/remotes/porelo/<push>` is deleted where the plugin
  wrote it; nothing else under `refs/remotes/porelo/` is touched.

## Plan

1. **`Waiting.Entry`** carries its repository's `upstream`, which `Project.Repository` already reads from the gate;
   an entry whose upstream is `""` (work that stays on the machine) is offered no *Open in*. Red first in
   `WaitingTest`.
2. **`OpenProjects`**, a pure function tested outside the IDE: given each open project's git roots with their remote
   URLs, it says which project and remote hold a gate's upstream. URLs are compared as one address: scp-like
   `git@host:path`, `ssh://git@host/path` and `https://host/path`, with or without `.git` or a trailing `/`, and
   host names case-blind. Two projects that hold it are both offered. Red first with each form.
   The remotes are read in the IDE by `git config --get-regexp ^remote\..*\.url$` in each root that
   `ProjectLevelVcsManager` names for git, so the plugin needs no dependency on IntelliJ's git plugin.
3. **`ReviewCache.fetchInto(root, url, name, commit)`**: `git -C <root> fetch --no-tags -- <url>
   +refs/porelo/incoming/<name>:refs/remotes/porelo/<name>`, with the same name and commit checks and the same
   `safe.directory` trust for a WSL mirror as `fetch`; it says when the ref now holds another commit than the one
   shown. Red first in `ReviewCacheTest` against a real repository with a working tree: the ref holds the commit,
   and `HEAD`, the index, the working tree and every other ref are unchanged afterwards.
4. **The actions**: *Open in <project>* in `PoreloWatch`'s notice and in `ReviewTab`, one per matching project;
   it brings the window forward (`ProjectUtil.focusProjectWindow`), fetches in the background and says in a
   notice where the push is now, with an action that shows it in the Git log.
5. **`Decisions`**: after `approve` returns, a background `git fetch --quiet <remote>` in each matching open
   project; after approve or reject, `git update-ref -d refs/remotes/porelo/<push>` where it exists. A failed fetch
   is a warning that says git's words; the decision stands.
6. **The acceptance on the VM** as a live test, tagged `live`, against a real daemon: a push fetched into a
   repository with a dirty working tree leaves its files and index as they were.

## Acceptance

- With the push's repository open in a second IDE window, the notice and the review tab offer *Open in <project>*;
  choosing it brings that window forward and the push's commit is reachable there as `porelo/<push>`; the working
  tree, the index and the checked-out branch are unchanged, also with uncommitted changes. Seen to fail first.
- With no window holding the repository, or with work that stays on the machine, nothing is offered.
- After approving, the forwarded commit is in the project's remote-tracking branch without a fetch of the person's,
  and `refs/remotes/porelo/<push>` is gone; after rejecting, the ref is gone too.
- A fetch that fails (the remote asks for a password, the mirror is not reached) is a warning in git's words; the
  review and the decision are not held up.

## To be checked

- Whether IntelliJ 2026.1's Git log shows a new ref under `refs/remotes/porelo/` without a refresh of the person's,
  or the plugin must ask `VcsLogManager` for one: measured before step 4.
- Whether a fetch through the person's ssh from Windows into a project's repository needs `GIT_SSH_COMMAND` as
  [IJ03](README.md#ij03)'s step 4 sets it for the cache: read when [IJ03](README.md#ij03)'s part 2 is built.
