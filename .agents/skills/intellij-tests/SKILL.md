---
name: intellij-tests
description: porelo-intellij's test tasks: documents, live tests against a real daemon with their PORELO_LIVE_* variables, and the windows-ssh tests of the Windows workflow. Use before writing or running tests, or handing over a change to how the plugin speaks to a daemon.
---

# intellij-tests

## The tests

- **The tests tagged `documents` run alone, with the checks on the documents, as `./gradlew documents`.**
- **The tests tagged `live` run against a real daemon, as `./gradlew liveTest`, never in the build**: a machine
  with a task that pushed, named by `PORELO_LIVE_HOST`, `PORELO_LIVE_SOCKET`, `PORELO_LIVE_TASK`, `PORELO_LIVE_COMMIT`
  and `PORELO_LIVE_SSH_CONFIG`, `PORELO_LIVE_FREE_BRANCH` for the taken-branch test, and `PORELO_LIVE_WSL`,
  `PORELO_LIVE_WSL_DISTRIBUTION` and `PORELO_LIVE_PROJECT` for the WSL bridge, and `PORELO_LIVE_HOLD_MINUTES` and
  `PORELO_LIVE_HOLD_LOG` for holding the stream of projects for hours, as each test's comment says. The
  taken-branch test forwards the push, so it runs after the others. A change to how the plugin speaks to a daemon is
  handed over only after they ran.
- **The tests tagged `windows-ssh` run only in the *Windows* workflow, as `./gradlew windowsSshTest`**, on every
  push to main and every pull request, beside the Build; Publish does not wait for it. They reach a stand-in daemon
  through Windows' own `ssh.exe` and an OpenSSH server on the same runner. That job runs on the runner's own Java 25
  (`JAVA_HOME_25_X64`), the one exception to the pinned GraalVM: `porelo-machines` pins one for Linux only, and the
  job publishes nothing.
