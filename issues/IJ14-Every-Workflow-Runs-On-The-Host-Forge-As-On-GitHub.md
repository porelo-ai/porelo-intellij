# IJ14 — Every Workflow Runs On The Host's Forge As On GitHub

**Status:** Idea.

**What must be true.** This repository's workflows run unchanged on the move's host's Forgejo and on GitHub, with
only the values of variables and secrets differing, as porelo-host's
[local registry and publication settings](https://github.com/porelo-ai/porelo-host/blob/main/doc/local-forge.md#the-local-registry-and-publication-settings)
name them. On the forge the plugin is built, tested, checked and verified; publishing and the Windows job run on
GitHub alone, each switched by a variable present on both forges, never by a branch in a workflow.

## What to read before it is analyzed

- **`build.yml`:** `runs-on: ubuntu-26.04`, the pinned GraalVM through `JAVA_HOME_25_X64` and `actions/cache`, and a
  first step that makes room for the IDEs with `sudo rm -rf` and `docker image prune`, which a forge runner may not
  allow. `verifyPlugin` downloads and unpacks a whole IntelliJ IDEA from JetBrains for every version
  `gradle.properties` names, some 17 GB in all, so the runner needs that disk and that way out.
- **Publish** puts the plugin into `porelo-dist-intellij` with `JF_ACCESS_TOKEN`; on the forge it is switched off by
  `PORELO_PUBLISH`, as porelo-message-sluice does.
- **Porelo's snapshots:** the build resolves `porelo-wire` from Sonatype's snapshots unless `-PporeloSnapshots` names
  another repository. `porelo` moves last of the builders, so whether the forge's build reads them from Sonatype or
  from the forge's registry (`MAVEN_SNAPSHOTS_URL`, with the publisher's token, since the organization is private)
  is decided here.
- **`windows.yml`:** GitHub's Windows runner only, switched by a variable present on both forges.
- **`delete-runs.yml`** calls GitHub's API by hand only; **`shared-rules.yml`**'s scheduled step reads GitHub's
  activity with the shared rules
  check, which reads GitHub's activity only; **`workflows.yml`** needs Python with PyYAML on the runner.
- **Seen to fail first:** today's `build.yml` on the host's Forgejo.
