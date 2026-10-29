# IJ01 — Work is reviewed in IntelliJ

**Status:** Review; pushed and published as a snapshot, the operator walks the install section in an IDE.
**Size:** S - what is left is the operator's walk of the install section; the review, approve, reject, the notice, the
Gradle task that writes `updatePlugins.xml` and the Publish steps are built.

**What must be true.** A developer reviews an agent's waiting work in IntelliJ IDEA, as they would
review a colleague's change: they see what waits, read the diff in the IDE they know, and approve or
reject it - without typing a `git fetch`, knowing a mirror's path, or running anything the agent wrote.

## Why

Today a review happens in the interface, at the command line (`porelo gate review`), or by hand in a
clone with the fetch Porelo prints for a waiting push, opened in IntelliJ's safe mode. The last is the one a
developer wants for real code - but it asks them to copy a command, keep a clone, and remember not to
build. The plugin does those steps for them and keeps the safety the box gives.

## The shape

### 1. A tool window "Porelo", one list of what waits

- Every waiting push of every machine the person reaches, newest first: the task, the repository,
  the agent, how long it has waited, and how far-reaching the change is (Porelo's ranking).
- A notification when something new waits, like a pull request assigned.
- It works whatever project is open in the IDE, or none: a waiting push is reviewed without one.

### 2. Review without a project and without a working tree

- **The plugin keeps a cache of its own per repository**: a bare git repository, with no working
  tree, under the IDE's cache directory (say `~/.cache/porelo-intellij/<repository>.git`). A waiting
  push is fetched there (the fetch Porelo prints, run by the plugin over the person's ssh), never into a
  repository of the person's.
- **The diff is shown in IntelliJ's own diff windows**, which compare two versions of a file without a
  project: the commit list, the diff per file, side by side.
- So a notification can be acted on whatever is open, and **nothing is ever checked out**: with no
  working tree there is nothing IntelliJ could load as a project, sync, build or run - no Gradle or
  Maven import, no run configuration, no `.idea/` file of the agent's. The review writes nothing into a
  repository of the person's.
- The files Porelo ranks as far-reaching (build files, workflows, `.idea/`, hooks) come first and
  marked, as in the interface.

### 3. Approve or reject from the window

- *Approve* passes the **full commit id that was shown** (`Approve(… commit)`); a push that moved
  meanwhile is refused (`MovedSinceReview`) and the window reloads it for a new look.
- *Reject* asks for a reason, which reaches the task.

### 4. How it reaches a machine

- **Through the daemon's own contract**, not the CLI: the same Varlink interface the interface uses
  (`Pending`, `Approve`, `Reject`), over the ssh forward to the machine's socket. The client is
  `porelo-wire`, which `porelo` already publishes for Java - so the plugin speaks the contract with the
  code that defines it, not a copy.
- **Machines come from what the person already set up**: the interface's list of machines and their
  ssh hosts, read only. No second configuration to keep.
- No token, key or passphrase is stored by the plugin: ssh uses the person's agent, as the interface
  does.

### 5. Later, not first

- *Run its tests* on the waiting work, in a fresh container on the machine (`porelo gate try`), its
  output in IntelliJ's run window - never on the developer's computer.
- Starting work from IntelliJ ("let an agent work on this branch") - a separate requirement.

## Building and publishing

- **Built with Gradle and the IntelliJ Platform Gradle Plugin** - the one exception to "a Java
  repository builds through Maven", named in the repository's `AGENTS.md`: JetBrains supports
  building a plugin with Gradle only. As the plugin of `fuinorg/ddd-cqrs-dsl`
  (`intellij/`) is, which also shows the publishing: the plugin zip and an `updatePlugins.xml`
  uploaded to an Artifactory generic repository under `<version>/` and `latest/`, so a person adds
  one URL as a custom plugin repository and installs it from the Marketplace tab.
- **For Porelo:** the generic repository `porelo-dist-intellij` beside `porelo-dist-deb`/`-rpm`, set up with
  deploy for the Build's account, with the same two channels as the packages: `snapshots` from `main`,
  `releases` from a `v<version>` tag after every check passed (as for the packages). The version line
  follows the others (0.4.0).
- CI as in the other repositories: pinned actions, time limits per job, the plugin verifier against
  the supported IntelliJ versions, and the shared `AGENTS.md` block.

### 6. Settled

- **The IntelliJ versions** are those of the `ddd-cqrs-dsl` plugin: 2026.1 and newer.

## Plan

- **Built:** the tool window and its notice (`PoreloWatch`, `WaitingTree`), the bare cache (`ReviewCache`), the
  review tab in IntelliJ's diff windows (`ReviewEditor`, `ReviewTab`), approve with the commit shown and reject
  with a reason (`Decisions`, `Gate`), and the machines from the interface's list (`Machines`, `Tunnel`).
- **Publishing:**
  1. **Built:** `updatePluginsXml` beside `buildPlugin` writes `updatePlugins.xml` for the zip: the plugin's id and
     name from `plugin.xml`, its version, `since-build`, and the zip's address under `<channel>/<version>/`. An IDE
     offers a plugin only when its version is newer than the one installed, so a snapshot the Build publishes
     carries its run number, `-PsnapshotStamp`, as `0.5.0-SNAPSHOT.<run>`, in the zip's `plugin.xml` too.
  2. **Built:** *Publish the plugin* in `build.yml`'s job, on `main` and on `v<version>` tags only, after `build`
     and `verifyPlugin`, which on `main` build with `-PsnapshotStamp=<run number>`. The zip goes to the address
     `updatePlugins.xml` names, so the address is the build's alone, and that descriptor beside it and as
     `<channel>/latest/updatePlugins.xml`, each with `curl -T` and both checksums, the token `JF_ACCESS_TOKEN` handed
     to `curl -K -` on its standard input, never in a file or on a command line (`curl` in the step, until
     a shared upload command replaces it). A release already published, or one whose state cannot be read, is
     refused before anything is written. *The plugin is published, as an IDE reads it* reads both back with no
     token and compares them with what was built. Run on the host against a stand-in `curl`: the three uploads and
     their checksums, the token on standard input and in no file of the runner's, seen red with the earlier step that wrote one; each refusal made to happen (no token, an
     address outside `porelo-dist-intellij`, a release already there, one whose state answered 503), and the read
     back red on a changed upload. Its acceptance is the first Publish on `main`. A release from a `v<version>` tag cannot run yet:
     `build.yml` starts on a push to `main` and on pull requests only, so a tag trigger comes with the first
     release.
  3. **Built:** `UpdatePluginsTest` reads the `updatePlugins.xml` the build writes (id, version, URL, since-build);
     seen red without the task and with a wrong channel in the URL.
  4. **Built:** *Installing it* in `doc/index.md` says how to install the plugin from the custom repository URL, for
     the snapshot channel: in IntelliJ IDEA's *Settings* - *Plugins* - *Manage Plugin Repositories*, the URL added,
     then the plugin installed from the Marketplace tab. Each step is done once as written in the operator's walk.

## The operator's walk

One walk covers `doc/index.md` as written: this issue's install section and [IJ02](README.md#ij02)'s chapter. It
needs IntelliJ IDEA 2026.1 or newer, and a machine in the Porelo interface's list with a task that can push.
**Checked by me without an IDE:** the URL answers with an `updatePlugins.xml` that names the plugin's id, its
version and `since-build="261"`, and the zip it names downloads, its `plugin.xml` naming *Porelo* at that version.
**Not run by me**, since they need an IDE with a display: every step below.

1. *Settings* → *Plugins* → the gear icon → *Manage Plugin Repositories*; add
   `https://fuinorg.jfrog.io/artifactory/porelo-dist-intellij/snapshots/latest/updatePlugins.xml`.
2. *Marketplace* tab: search *Porelo*, install it, restart if asked. *Installed* shows it at `0.5.0-SNAPSHOT.<run>`.
3. Open the *Porelo* tool window: every waiting push of the listed machines, grouped by project and repository; a
   machine that does not answer says why.
4. Let a task push once: a notice comes within seconds.
5. *Review* in the notice: the files on the left, IntelliJ's diff on the right; far-reaching files first and marked.
6. Reject one push with a reason (the task is told), approve another (its commit is on the branch named).
7. With the push's repository open in a second window: *Open in* that project brings it forward, and `porelo/<push>`
   is in its Git log; the working tree is unchanged.
8. Once a newer snapshot is published, *Plugins* offers it as an update.

What does not match `doc/index.md` goes to the channel, with the step and the plugin's version.

## Acceptance

- With the plugin installed from the custom repository URL, a developer sees a waiting push in the
  tool window - whatever project is open, or none - reads its diff in IntelliJ's change view, and
  approves it; the commit arrives at the upstream. Nothing was checked out, built or run on their
  computer, and no repository of theirs was written to.
- A push that moved after it was shown is refused at approval and shown again.
- Reject sends the reason to the task.
- The plugin stores no credential, and works with the machines the person already has.
