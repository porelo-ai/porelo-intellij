# IJ34 — A review reads a WSL mirror owned by the Linux account

```json
{
  "$schema": "issue-1.json",
  "id": "IJ34",
  "title": "A review reads a WSL mirror owned by the Linux account",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Reading, and opening in a project, a push that waits in a WSL distribution works from the IDE on Windows: git's upload-pack reading the gate's mirror at its `//wsl.localhost` path trusts that one repository on its own command line, so Windows' git no longer refuses it as of dubious ownership, and the person's global git configuration is left alone"
}
```

**Why this size.** One class, `cache/ReviewCache`, with its tests and a changelog entry; no contract with the daemon
changes.

**What must be true.** A person whose Porelo runs in WSL and whose IDE runs on Windows reads every waiting push in the
Porelo tool window and opens it in a project, without adding a `safe.directory` exception to their own git
configuration.

## Plan

Read on `main`: on Windows, the review fetches from a WSL distribution's mirror by the path Windows reaches it at,
`//wsl.localhost/<distribution><mirror>` (`Waiting.Entry.source`), since a WSL distribution usually runs no ssh server
and the daemon's `ssh://` address would need one. The mirror belongs to the distribution's Linux account, whom Windows'
git sees as another owner, and it refuses to read it:

    Cannot read example-task:
    git ls-remote --symref -- //wsl.localhost/Ubuntu/home/user/mirrors/default/example-project.git HEAD ended with 128:
    fatal: detected dubious ownership in repository at '//wsl.localhost/Ubuntu/home/user/mirrors/default/example-project.git'

`ReviewCache.run` already means to trust the mirror for the one call, by `safe.directory=*` in `GIT_CONFIG_COUNT`
when an argument starts with `//wsl.localhost/`. That never reaches the process that opens the mirror: for a local
path, `git ls-remote` and `git fetch` start `git upload-pack` with the configuration of their own environment and
command line removed (git's `local_repo_env`). Measured with git 2.53: a setting given by `GIT_CONFIG_COUNT` or `-c` to
`ls-remote` does not reach its `upload-pack`; one given in `--upload-pack='git -c <key>=<value> upload-pack'` does.

Steps:

1. `ReviewCache`'s three calls that reach a mirror, `defaultBranch`'s `ls-remote`, `fetched`'s `fetch` and
   `fetchInto`'s `fetch`, pass `--upload-pack` with `git -c safe.directory=<path> -c safe.directory=%(prefix)/<path>
   upload-pack` when the address is a path rather than an `ssh://` address: the mirror alone is trusted, by both forms
   Git for Windows matches a path that begins with `//`, for that one process. A path holding a `'` is refused, since
   `upload-pack` is started through a shell.
2. The `GIT_CONFIG_COUNT` `safe.directory=*` is removed: it trusted every repository the outer git opened and helped
   none.
3. A changelog entry under *Fixed*.

The test that goes red first is in `ReviewCacheTest`: a mirror on a local path, the cache given
`GIT_TEST_ASSUME_DIFFERENT_OWNER=1` (git's own switch that makes it see every repository as somebody else's) and
`GIT_TRACE` into a file; the test expects `upload-pack` started with `safe.directory` naming that mirror, and a review
read from it. The plain test task runs on Windows' runner too, where Git for Windows' `upload-pack` checks ownership.

## Acceptance

- The red-first test fails on `main` and passes after; it fails again when the `--upload-pack` is dropped.
- A test that `fetchInto` passes the same trust, and that an `ssh://` address gets none.
- `./gradlew build`, `verifyPlugin` and the Windows workflow pass.
- The operator's Windows machine with Porelo in WSL reads and opens a waiting push in the Porelo tool window.

## To be checked

- Whether Git for Windows' `upload-pack` refuses a mirror under `GIT_TEST_ASSUME_DIFFERENT_OWNER=1` on the Windows
  runner, so the red-first test is red there on `main`; git 2.53 on Linux does not check ownership in `upload-pack`.
- Which of the two `safe.directory` forms Git for Windows matches for a `//wsl.localhost` path; the other is dropped
  once measured.
