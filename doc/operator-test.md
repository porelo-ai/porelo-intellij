# The operator's test: reviewing in IntelliJ

This walk tests the plugin end to end on the laptop, against its own Porelo: the plugin installed from the
snapshot channel, two tasks that push, a push read in the tool window, one approved with the commit shown, one
rejected with a reason, a new push announced within 5 s, and an approval of a commit that has moved refused.

**What it needs, on the laptop:** IntelliJ IDEA 2026.1 or newer; Porelo 0.5.1 snapshot 350 or newer, set up in your
own account, with its daemon running, its Porelo-Vault there, and the agent `claude` installed. Nothing else is
installed: the two scripts make a throwaway repository and take it out again.

**Windows:** IntelliJ IDEA with any project of yours open, since a review opens in a project's window; this Claude
session, for the commands prefixed with `!`, which ask for nothing; and two terminals of their own, *terminal 1* and
*terminal 2*, for the two tasks, since starting a task may ask for the Vault-Passphrase.

**The scripts** are in `~/.porelo/handover/porelo-intellij/walk-ij/` on the laptop, their digests in `SHA256SUMS`
beside them. `ij-walk-setup.sh` makes the upstream `~/.porelo/walk-ij/ij-walk.git` with one commit and adds it to
Porelo's project `default` as `ij-walk`; it refuses to start over anything an earlier walk left, and removes what it
made when it fails. `ij-walk-clean.sh` removes the two tasks, `ij-walk` with the gate's mirror that removing it
keeps, the plugin's cache of that gate, and `~/.porelo/walk-ij`, and nothing else. Inside a task, the repository's
own `walk-push.sh` makes one change and pushes it: the subject goes into `README.md` and every file named, and is
the commit's message.

**Duration:** about 30 minutes, a guess. The first task builds its image: on the test VM that took 70 s, the second
task 20 s.

**What was run before:** every command was rehearsed on a test VM with Porelo 0.5.1 snapshot of 2026-10-25 and the
stub agent instead of `claude`, the approvals and the rejection made with `porelo gate` instead of the plugin, and the
plugin's client checked there against the walk's tasks: a new push was known within 5 s. The steps in IntelliJ IDEA
were not run, since they need an IDE with a display. Each step says which.

## The steps

1. Read Porelo's version, in this Claude session on the laptop:

    ```
    ! porelo --version
    ```

    Expected: `porelo 0.5.1~snapshot.350` or newer. *Seen on the laptop.*

2. Check the daemon and the vault, in this Claude session on the laptop:

    ```
    ! porelo doctor
    ```

    Expected: a line `vault there` and a line `daemon running, 0.5.1~snapshot.…`. *Seen on the laptop.*

3. Make the walk's repository, in this Claude session on the laptop:

    ```
    ! bash ~/.porelo/handover/porelo-intellij/walk-ij/ij-walk-setup.sh
    ```

    Expected: `OK    upstream …/.porelo/walk-ij/ij-walk.git at <commit>` and `OK    project 'default' has 'ij-walk'`. A
    line starting `FAILED in guide step 3` names what is in the way. *Seen on the test VM.*

4. In IntelliJ IDEA: *File* → *Settings* → *Plugins*.

    Expected: the *Plugins* page with the tabs *Marketplace* and *Installed*. *Not run.*

5. On that page: the gear icon → *Manage Plugin Repositories*.

    Expected: the list of custom plugin repositories. *Not run.*

6. In that list: *+*, enter the URL below, and *OK*:

    ```
    https://fuinorg.jfrog.io/artifactory/porelo-dist-intellij/snapshots/latest/updatePlugins.xml
    ```

    Expected: the URL is in the list. *Not run.* The URL was read without an IDE: it names `org.fuin.porelo` at
    `0.5.0-SNAPSHOT.20` with `since-build="261"`.

7. On the *Marketplace* tab: search *Porelo* and click *Install*.

    Expected: *Porelo* is installed; the button turns into *Installed* or *Restart IDE*. *Not run.*

8. On the *Installed* tab: find *Porelo*.

    Expected: *Porelo* at `0.5.0-SNAPSHOT.20` or newer. *Not run.*

9. Close *Settings* with *OK*, and restart the IDE if it asks.

    Expected: the IDE's window with your project. *Not run.*

10. In IntelliJ IDEA: *View* → *Tool Windows* → *Porelo*.

    Expected: the tool window on the left, with nothing of `ij-walk`: *Nothing waits for review*, or the pushes of
    your other projects. No line *The machines cannot be read*. *Not run.*

11. Start task A, in terminal 1 on the laptop, in your home directory:

    ```
    porelo task start ij-walk-a -p default -r ij-walk --agent claude --attach shell
    ```

    Expected: it asks for the Vault-Passphrase if the vault is locked, builds the image, and ends at the prompt
    `porelo[default/ij-walk-a] /workspace$` above a status bar `[porelo]`. *Seen on the test VM with `--agent stub`;
    with `claude` not run.*

12. Push from task A, in terminal 1 at the task's prompt:

    ```
    bash walk-push.sh "First change of task A" build.gradle.kts
    ```

    Expected in terminal 1: ` * [new reference]   HEAD -> refs/porelo/incoming/ij-walk-a`. Expected in IntelliJ IDEA,
    within 5 s: a notice *Waits for review: First change of task A*, naming 1 far-reaching file. *The push seen on the
    test VM; the notice not run.*

13. Start task B, in terminal 2 on the laptop, in your home directory, once task A's prompt is there:

    ```
    porelo task start ij-walk-b -p default -r ij-walk --agent claude --attach shell
    ```

    Expected: the prompt `porelo[default/ij-walk-b] /workspace$`. *Seen on the test VM with `--agent stub`.*

14. Push from task B, in terminal 2 at the task's prompt:

    ```
    bash walk-push.sh "First change of task B"
    ```

    Expected in terminal 2: ` * [new reference]   HEAD -> refs/porelo/incoming/ij-walk-b`. Expected in IntelliJ
    IDEA, within 5 s: a notice *Waits for review: First change of task B*. *The push seen on the test VM; the notice
    not run.*

15. In the *Porelo* tool window: click `ij-walk-a`.

    Expected: a tab *ij-walk-a: First change of task A*, its header naming `ij-walk-a` and the first 12 digits of the
    commit; on the left `build.gradle.kts` first, marked far-reaching, then `README.md`; on the right IntelliJ's diff
    of the selected file. *Not run.* The daemon's ranking was seen on the test VM: `build.gradle.kts` first, *a build
    script: it runs on every build*.

16. In that tab: click *Approve*.

    Expected: a dialog *Forward First change of task A (commit <40 digits>) from default / ij-walk on This computer
    onto the branch:*, with `porelo/ij-walk-a` filled in; the commit begins with the 12 digits of the header.
    *Not run.*

17. In that dialog: click *OK*.

    Expected: a notice *Forwarded …*; `ij-walk-a` leaves the tool window. *Not run.*

18. Read what arrived upstream, in this Claude session on the laptop:

    ```
    ! git -C ~/.porelo/walk-ij/ij-walk.git log -1 --format='%H %s' porelo/ij-walk-a
    ```

    Expected: the 40 digits the dialog of step 16 showed, and *First change of task A*. *Seen on the test VM.*

19. In the *Porelo* tool window: click `ij-walk-b`.

    Expected: a tab *ij-walk-b: First change of task B*. *Not run.*

20. In that tab: click *Reject*.

    Expected: a dialog *Why is … rejected? The task that pushed it is told.* *Not run.*

21. In that dialog: type `Keep the readme as it is.` and click *OK*.

    Expected: a notice *Rejected; its task was told*; `ij-walk-b` leaves the tool window. *Not run.*

22. Read task B's inbox, in terminal 2 at the task's prompt:

    ```
    cat /run/porelo/mail/inbox/new/*.json
    ```

    Expected: one message whose text ends *They said: Keep the readme as it is.* *Seen on the test VM.*

23. Drop the rejected commit from task B, in terminal 2 at the task's prompt:

    ```
    git reset -q --hard HEAD~1
    ```

    Expected: no output. *Seen on the test VM.*

24. Push from task B again, in terminal 2 at the task's prompt:

    ```
    bash walk-push.sh "Second change of task B"
    ```

    Expected in terminal 2: ` * [new reference]   HEAD -> refs/porelo/incoming/ij-walk-b`. Expected in IntelliJ
    IDEA, within 5 s, counted from that line: a notice *Waits for review: Second change of task B*. *The push seen on
    the test VM, and the plugin's client knew such a push there within 5 s; the notice not run.*

25. In the *Porelo* tool window: click `ij-walk-b`.

    Expected: a tab *ij-walk-b: Second change of task B*, its header naming the commit's first 12 digits. Keep the
    tab open. *Not run.*

26. Move task B's push to another commit, in terminal 2 at the task's prompt:

    ```
    bash walk-push.sh "Third change of task B"
    ```

    Expected in terminal 2: `   <old>..<new>  HEAD -> refs/porelo/incoming/ij-walk-b`. Expected in IntelliJ IDEA: no
    notice within seconds, since a push that only moves changes no count in the daemon's stream; within a minute, at
    the plugin's own read, a notice *Waits for review: Third change of task B*. *The push seen on the test VM; the
    notice not run.*

27. In the tab of step 25: click *Approve*.

    Expected: the dialog names the commit of *Second change of task B*, the one the tab shows. *Not run.*

28. In that dialog: click *OK*.

    Expected: an error notice *Approving ij-walk-b failed: ij-walk-b moved from <old> to <new> after it was shown;
    nothing was forwarded*, and the tab closes. *Not run.* The daemon's refusal was seen on the test VM: *moved since
    it was reviewed … nothing was forwarded*.

29. In the *Porelo* tool window: click `ij-walk-b`.

    Expected: a tab *ij-walk-b: Third change of task B*, its header naming the first 12 digits of `<new>`. *Not run.*

30. In that tab: click *Approve*.

    Expected: the dialog names the commit `<new>`. *Not run.*

31. In that dialog: click *OK*.

    Expected: a notice *Forwarded …*; `ij-walk-b` leaves the tool window. *Not run.*

32. Read what arrived upstream, in this Claude session on the laptop:

    ```
    ! git -C ~/.porelo/walk-ij/ij-walk.git log -3 --format='%h %s' porelo/ij-walk-b
    ```

    Expected: *Third change of task B*, *Second change of task B*, *Start the walk's repository*; the rejected *First
    change of task B* is not there. *Seen on the test VM.*

33. Remove what the walk made, in this Claude session on the laptop:

    ```
    ! bash ~/.porelo/handover/porelo-intellij/walk-ij/ij-walk-clean.sh
    ```

    Expected: `removed   porelo-default-ij-walk-a`, `removed   porelo-default-ij-walk-b`, and
    `OK    the walk's tasks, the repository 'ij-walk' and …/.porelo/walk-ij are gone`; the task shells in terminals 1
    and 2 end. A line starting `FAILED in guide step 33` names what is left. *Seen on the test VM, the shells ending
    not.*

## What to do with a finding

A step whose result differs from *Expected* goes to the room, with its number, what was seen, and the plugin's
version from the *Installed* tab. The plugin stays installed after the walk; *Settings* → *Plugins* removes it.
