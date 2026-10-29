# The operator's test: reviewing in IntelliJ

This walk tests the plugin end to end on the laptop, against its own Porelo: the plugin installed from the
snapshot channel, two tasks that push, a push read in the tool window, one approved with the commit shown, one
rejected with a reason, a new push announced within 5 s, and an approval of a commit that has moved refused.

**What it needs, on the laptop:** IntelliJ IDEA 2026.1 or newer; Porelo 0.5.1 snapshot 350 or newer, set up in your
own account, with its daemon running, its Porelo-Vault there, and the agent `claude` installed; `python3`. Nothing
else is installed: the two scripts make a throwaway repository and take it out again.

**Windows:** IntelliJ IDEA with any project of yours open, since a review opens in a project's window; this Claude
session, for the commands prefixed with `!`, which ask for nothing; and two terminals of their own, *terminal 1* and
*terminal 2*, for the two tasks, since starting a task may ask for the Vault-Passphrase.

**The scripts** are in `~/.porelo/handover/porelo-intellij/walk-ij/` on the laptop, their digests in `SHA256SUMS`
beside them. `ij-walk-setup.sh` makes the upstream `~/.porelo/walk-ij/ij-walk.git` with one commit, adds it to
Porelo's project `default` as `ij-walk`, and makes the folders of the plugin's cache for that gate; it refuses to
start over anything an earlier walk left, and removes what it made when it fails or is interrupted.
`ij-walk-clean.sh` removes the two tasks, `ij-walk` with the gate's mirror that removing it keeps, the plugin's cache
of that gate and the folders the walk made, and `~/.porelo/walk-ij`; it removes nothing without that folder, and
nothing that names another address. Inside a task, the repository's own `walk-push.sh` makes one change and pushes
it: the subject goes into `README.md` and every file named, and is the commit's message.

**The plugin's name for the laptop** is `this machine`, the name the Porelo interface gives this computer's daemon;
the setup prints the name it found, and the dialogs below show it.

**Duration:** about 30 minutes, a guess. The first task builds its image: on the test VM that took 70 s, the second
task 20 s.

**What was run before:** every command was rehearsed on a test VM with Porelo 0.5.1 snapshot of 2026-10-25 and the
stub agent instead of `claude`, the approvals and the rejection made with `porelo gate` instead of the plugin, and the
plugin's client checked there against the walk's tasks: a new push was known within 5 s. The scripts' refusals and
their removal after a failure were made to happen there too. The steps in IntelliJ IDEA were not run, since they need
an IDE with a display. Each step says which.

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

    Expected: `OK    upstream …/.porelo/walk-ij/ij-walk.git at <commit>`, `OK    project 'default' has 'ij-walk'` and
    `OK    the plugin names this computer 'this machine'`. A line starting `FAILED in guide step 3` names what is in
    the way. *Seen on the test VM, where the name is `This computer`; the name `this machine` read on the laptop.*

4. In IntelliJ IDEA: *File* → *Settings*.

    Expected: the *Settings* dialog. *Not run.*

5. In *Settings*: click *Plugins* on the left.

    Expected: the *Plugins* page with the tabs *Marketplace* and *Installed*. *Not run.*

6. On that page: the gear icon → *Manage Plugin Repositories*.

    Expected: the dialog *Custom Plugin Repositories*. *Not run.*

7. In that dialog: click *+*.

    Expected: a field for a repository URL. *Not run.*

8. In that field: enter this URL:

    ```
    https://fuinorg.jfrog.io/artifactory/porelo-dist-intellij/snapshots/latest/updatePlugins.xml
    ```

    Expected: the URL in the field. *Not run.* The URL was read without an IDE: it names `org.fuin.porelo` at
    `0.5.0-SNAPSHOT.20` with `since-build="261"`.

9. Confirm the field with *OK*.

    Expected: the URL is in the list of *Custom Plugin Repositories*. *Not run.*

10. Close *Custom Plugin Repositories* with *OK*.

    Expected: the *Plugins* page again. *Not run.*

11. On the *Marketplace* tab: search for *Porelo*.

    Expected: *Porelo* in the results. *Not run.*

12. At *Porelo*: click *Install*.

    Expected: the button turns into *Installed* or *Restart IDE*. *Not run.*

13. On the *Installed* tab: find *Porelo*.

    Expected: *Porelo* at `0.5.0-SNAPSHOT.20` or newer. *Not run.*

14. Close *Settings* with *OK*.

    Expected: the IDE's window with your project, or a question whether to restart the IDE. *Not run.*

15. Only if the IDE asks: restart it.

    Expected: the IDE's window with your project again. *Not run.*

16. In IntelliJ IDEA: *View* → *Tool Windows* → *Porelo*.

    Expected: the tool window on the left, with nothing of `ij-walk`: *Nothing waits for review*, or the pushes of
    your other projects. A line *vm-drive does not answer: …* may stand there when `vm-drive`, the interface's other
    machine, is off; no such line names `this machine`. *Not run.*

17. Start task A, in terminal 1 on the laptop, in your home directory:

    ```
    porelo task start ij-walk-a -p default -r ij-walk --agent claude --attach shell
    ```

    Expected: it asks for the Vault-Passphrase if the vault is locked, builds the image, and ends at the prompt
    `porelo[default/ij-walk-a] /workspace$` above a status bar `[porelo]`. *Seen on the test VM with `--agent stub`;
    with `claude` not run.*

18. Push from task A, in terminal 1 at the task's prompt:

    ```
    bash walk-push.sh "First change of task A" build.gradle.kts
    ```

    Expected in terminal 1: ` * [new reference]   HEAD -> refs/porelo/incoming/ij-walk-a`. Expected in IntelliJ IDEA,
    within 5 s: a notice *Waits for review: First change of task A*, naming 1 far-reaching file. *The push seen on the
    test VM; the notice not run.*

19. Start task B, in terminal 2 on the laptop, in your home directory, once task A's prompt is there:

    ```
    porelo task start ij-walk-b -p default -r ij-walk --agent claude --attach shell
    ```

    Expected: the prompt `porelo[default/ij-walk-b] /workspace$`. *Seen on the test VM with `--agent stub`.* Two
    tasks of one repository started at the same moment can leave the second without a gate, a fault of Porelo's,
    which is why this waits for task A.

20. Push from task B, in terminal 2 at the task's prompt:

    ```
    bash walk-push.sh "First change of task B"
    ```

    Expected in terminal 2: ` * [new reference]   HEAD -> refs/porelo/incoming/ij-walk-b`. Expected in IntelliJ
    IDEA, within 5 s: a notice *Waits for review: First change of task B*. *The push seen on the test VM; the notice
    not run.*

21. In the *Porelo* tool window: click `ij-walk-a`.

    Expected: a tab *ij-walk-a: First change of task A*, its header naming `ij-walk-a` and the first 12 digits of the
    commit; on the left `build.gradle.kts` first, marked far-reaching, then `README.md`; on the right IntelliJ's diff
    of the selected file. *Not run.* The daemon's ranking was seen on the test VM: `build.gradle.kts` first, *a build
    script: it runs on every build*.

22. In that tab: click *Approve*.

    Expected: a dialog *Forward First change of task A (commit <40 digits>) from default / ij-walk on this machine
    onto the branch:*, with `porelo/ij-walk-a` filled in; the commit begins with the 12 digits of the header.
    *Not run.*

23. In that dialog: click *OK*.

    Expected: a notice *Forwarded …*; `ij-walk-a` leaves the tool window. *Not run.*

24. Read what arrived upstream, in this Claude session on the laptop:

    ```
    ! git -C ~/.porelo/walk-ij/ij-walk.git log -1 --format='%H %s' porelo/ij-walk-a
    ```

    Expected: the 40 digits the dialog of step 22 showed, and *First change of task A*. *Seen on the test VM.*

25. In the *Porelo* tool window: click `ij-walk-b`.

    Expected: a tab *ij-walk-b: First change of task B*. *Not run.*

26. In that tab: click *Reject*.

    Expected: a dialog *Why is … rejected? The task that pushed it is told.* *Not run.*

27. In that dialog: type `Keep the readme as it is.`

    Expected: the text in the dialog's field. *Not run.*

28. In that dialog: click *OK*.

    Expected: a notice *Rejected; its task was told*; `ij-walk-b` leaves the tool window. *Not run.*

29. Read task B's inbox, in terminal 2 at the task's prompt:

    ```
    cat /run/porelo/mail/inbox/new/*.json
    ```

    Expected: one message whose text ends *They said: Keep the readme as it is.* *Seen on the test VM.*

30. Drop the rejected commit from task B, in terminal 2 at the task's prompt:

    ```
    git reset -q --hard HEAD~1
    ```

    Expected: no output. *Seen on the test VM.*

31. Push from task B again, in terminal 2 at the task's prompt:

    ```
    bash walk-push.sh "Second change of task B"
    ```

    Expected in terminal 2: ` * [new reference]   HEAD -> refs/porelo/incoming/ij-walk-b`. Expected in IntelliJ
    IDEA, within 5 s, counted from that line: a notice *Waits for review: Second change of task B*. *The push seen on
    the test VM, and the plugin's client knew such a push there within 5 s; the notice not run.*

32. In the *Porelo* tool window: click `ij-walk-b`.

    Expected: a tab *ij-walk-b: Second change of task B*, its header naming the commit's first 12 digits. Leave the
    tab open. *Not run.*

33. Move task B's push to another commit, in terminal 2 at the task's prompt:

    ```
    bash walk-push.sh "Third change of task B"
    ```

    Expected in terminal 2: `   <old>..<new>  HEAD -> refs/porelo/incoming/ij-walk-b`. Expected in IntelliJ IDEA: a
    notice *Waits for review: Third change of task B* only at the plugin's own read, once a minute, so within 60 s;
    the daemon's stream shows a moved push by no count. *The push seen on the test VM; the notice not run.*

34. In the tab of step 32: click *Approve*.

    Expected: the dialog names the commit of *Second change of task B*, the one the tab shows. *Not run.*

35. In that dialog: click *OK*.

    Expected: an error notice *Approving ij-walk-b failed: ij-walk-b moved from <old> to <new> after it was shown;
    nothing was forwarded*, and the tab closes. *Not run.* The daemon's refusal was seen on the test VM: *moved since
    it was reviewed … nothing was forwarded*.

36. In the *Porelo* tool window: click `ij-walk-b`.

    Expected: a tab *ij-walk-b: Third change of task B*, its header naming the first 12 digits of `<new>`. *Not run.*

37. In that tab: click *Approve*.

    Expected: the dialog names the commit `<new>`. *Not run.*

38. In that dialog: click *OK*.

    Expected: a notice *Forwarded …*; `ij-walk-b` leaves the tool window. *Not run.*

39. Read what arrived upstream, in this Claude session on the laptop:

    ```
    ! git -C ~/.porelo/walk-ij/ij-walk.git log -3 --format='%h %s' porelo/ij-walk-b
    ```

    Expected: *Third change of task B*, *Second change of task B*, *Start the walk's repository*; the rejected *First
    change of task B* is not there. *Seen on the test VM.*

40. Remove what the walk made, in this Claude session on the laptop:

    ```
    ! bash ~/.porelo/handover/porelo-intellij/walk-ij/ij-walk-clean.sh
    ```

    Expected: `removed   porelo-default-ij-walk-a`, `removed   porelo-default-ij-walk-b`, and
    `OK    the walk's tasks, the repository 'ij-walk' and …/.porelo/walk-ij are gone`; the task shells in terminals 1
    and 2 end. A line starting `FAILED in guide step 40` names what is left, and running it again tries again.
    *Seen on the test VM, the shells ending not.*

## What to do with a finding

A step whose result differs from *Expected* goes to the room, with its number, what was seen, and the plugin's
version from the *Installed* tab. The plugin stays installed after the walk; *Settings* → *Plugins* removes it.
