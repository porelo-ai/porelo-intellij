# IJ06 — The code read against its skills

**Status:** Analyzed.
**Size:** M - about 3,100 lines of main code in four packages, 1,500 lines of tests and 350 lines of build scripts,
workflows and `plugin.xml`, read against five skills; each finding fixed with its own test or declined here. It
grows to L if the findings need a second contract with the daemon or a test harness for the `ide` package.

**What must be true.** All of the plugin's code has been read against the skills `AGENTS.md` names, and every
finding is either fixed, with a test that was seen to fail first, or declined with its reason written here; the
code a later issue changes is then read against them before it is handed over.

## Plan

- **The skills, in the versions the reading uses**, each downloaded as `AGENTS.md` says and its SHA-256 compared
  with the one the repository publishes before it is loaded:

  | skill | version | SHA-256 of the package |
  |---|---|---|
  | `java-code-review` | 2026.906.3 | `802f86339442ed9fa9d5bca14b6d16e1f6420ae92a047b18bba11239e32c698b` |
  | `concurrency-review` | 2026.906.3 | `3a0aab612f25483a3fe777eb18fbcc434e936b19298708bf88beff8b22d79e56` |
  | `test-quality` | 2026.906.3 | `9c5d23ad7a6d9c6f5c3679f3cc44a7fcc2fb3cc1c0f0d0ba4a2c0f7812428bad` |
  | `security-audit` | 2026.906.3 | `a46fd1be07bd539b955291537dea0f24f65a2f8b15d40d50a6a9098bbd992747` |
  | `clean-code` | 2026.906.3 | `5cf777a24630298b4b801c934f599722978c21aeac53d61683243908fc041c7b` |

  A newer version published before the reading starts replaces its row, in the same commit as the first finding.
- **What is read against which skill:**
  - `java-code-review` and `clean-code`: all of `src/main` - `cache` (`ReviewCache`, `Changes`), `gate`
    (`Gate`, `Waiting`, `Review`, `Project`, `PendingPush` and the exceptions), `ide` (`SokarWatch`,
    `WaitingTree`, `ReviewTab`, `ReviewEditor`, `Decisions`, `Snapshot` and the factories) and `machine`
    (`Machines`, `Machine`, `Tunnel`, `WslBridge`).
  - `concurrency-review`: what runs off the event dispatch thread and what it shares with it - `SokarWatch`'s
    timed read, `WaitingTree` and `ReviewTab` handing results back, `Decisions`, and the processes `Tunnel`,
    `WslBridge` and `ReviewCache` start and wait for, with their timeouts and what an interrupt or a closed
    project leaves running.
  - `security-audit`: every value from outside that reaches a command line, a file or a decision - names,
    commits and paths from the daemon passed to `git`, `ssh` and `wsl.exe`, the machines file, the cache's
    directory - and `build.gradle.kts`, `settings.gradle.kts`, the workflows and `plugin.xml`.
  - `test-quality`: all of `src/test`, and which main code has no test at all (today every class in `ide`).
- **Steps:** one package at a time, `machine`, `gate`, `cache`, then `ide`, each skill in turn. A finding is
  first reproduced by a unit test that fails, then fixed; one that cannot be reproduced is written under
  *Findings* as a guess and declined with what was tried. Each fixed finding that changes what ships gets its
  changelog entry.
- **The test that goes red first** is each finding's own; there is none for the reading as a whole.

## Acceptance

- *Findings* below lists every finding by skill and file, each with its outcome: fixed (with the test's name) or
  declined (with the reason).
- Every fixing test was seen to fail on the code before its fix.
- `./gradlew build`, `./gradlew verifyPlugin` and `./gradlew documents` green, and the tests tagged `live` run if
  a fix changes how the plugin speaks to a daemon.
- The hand-over names each skill with the version and SHA-256 that were read.

## Findings

- None yet; the reading has not started.

## Open questions

- None.
