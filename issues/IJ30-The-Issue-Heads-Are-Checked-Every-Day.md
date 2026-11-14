# IJ30 — The issue heads are checked every day

```json
{
  "$schema": "issue-1.json",
  "id": "IJ30",
  "title": "The issue heads are checked every day",
  "state": "Backlog",
  "size": "XS",
  "priority": "Low",
  "rank": 4,
  "blockedBy": [],
  "covers": "The *Shared rules check* reads every issue's head in `issues/` once a day with the shared tooling's `issues check issues`, from the released porelo-release, and fails naming the issue whose head is wrong"
}
```

**Why this size.** one step in one workflow and a version move of the shared tooling; no code and no contract.

**What must be true.** An issue whose head the shared tooling would refuse - a state, size, priority or rank it does
not know, or an index row that does not match - is found within a day, by name, not only when someone reads it.

## Plan

Read in today's code: `.github/workflows/shared-rules.yml` runs `./gradlew documents` and, on its daily schedule,
`check-commits` through the `release` task, which runs porelo-release at the version `porelo-buildtools` names in
`gradle/libs.versions.toml`. That version, 0.5.1, does not know `issues` and refuses it as an unknown command.

1. **`gradle/libs.versions.toml`:** `porelo-buildtools` moved to the first release whose porelo-release knows
   `issues check`, with `gradle/verification-metadata.xml` renewed from an empty Gradle home as `AGENTS.md` says.
2. **`shared-rules.yml`:** a step *The issue heads* running `./gradlew --no-daemon release -Prelease.args="issues check
   issues"` on every run, schedule, pull request and by hand.
3. No unit test: the change is to a workflow only. Its proof is the command run locally once on today's `issues/`, and
   once on a copy with one head broken, seen to fail naming it.

## Acceptance

- `./gradlew release -Prelease.args="issues check issues"` passes on `main` and fails, naming the issue, on a head with
  an unknown state.
- The *Shared rules check* run on GitHub shows the step green.
