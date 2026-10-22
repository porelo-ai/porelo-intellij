# IntelliJ plugin

The Porelo plugin for IntelliJ IDEA lets a developer review an agent's waiting work in the IDE they know, as they
would review a colleague's change: they see what waits at Porelo's gate, read its diff in IntelliJ's own diff
windows, and approve or reject it. Nothing the agent wrote is checked out, built or run on their computer.

It supports IntelliJ IDEA 2026.1 and newer. On Windows it reaches Porelo in a running WSL 2 distribution the
interface lists, through `wsl.exe` and `porelo daemon connect`, with no ssh and no port opened; it never starts a
distribution that is stopped. A machine over ssh it reaches through Windows' own `ssh.exe`, with the key in Windows'
OpenSSH Authentication Agent, which runs `porelo daemon connect` there for each connection. The ssh way is checked on
GitHub's Windows runner on every change; the WSL way is untested on a Windows machine, built and tested on
Linux against stand-ins only.

## What waits

The *Porelo* tool window lists every push that waits at the gates of the machines you reach, grouped by project and
repository, the newest first, and says how many of each push's files reach far. A push that starts to wait is
announced in a notice within seconds: the plugin follows each machine's list of projects as the daemon streams it,
and reads it again every minute besides. A machine that does not answer is said, with why, and never shown as having
nothing waiting. The list is there whatever project is open, or none.

## Reading a push

*Review* in the notice, or a click on it in the tool window, opens the push as an editor tab: the files it changes
on the left, IntelliJ's diff of the selected one on the right, against where the push and its branch last met, so
the branch's later work is not shown as removed. The files the daemon ranks far-reaching - a workflow, a build file,
IDE settings, anything that runs or decides what runs - come first and are marked. A file the daemon ranks that the
diff does not show is refused rather than left out, so what is approved is never more than what was shown. *Read
Again* reads the push anew from its machine.

## Approving and rejecting

*Approve* forwards exactly the commit that was shown onto the branch you name, `porelo/<push>` unless you name
another; a branch that already holds other work is said, and the next free name offered, never forced. A push that
moved since it was shown is refused and shown again to be read. *Reject* asks why, and that reason reaches the task
that pushed it.

## The machines and the cache

The plugin reaches the machines the Porelo interface lists in its own settings, which it reads and never writes, and
this computer's daemon whether the list names it or not. A machine elsewhere is reached through a tunnel over your
own ssh, from Windows through its own `ssh.exe`; a WSL 2 distribution on Windows through `porelo daemon connect`. A review is fetched into the plugin's own
cache, `porelo-intellij` under `$XDG_CACHE_HOME` or `~/.cache`, one repository with no working tree for each gate.

## In the project you work in

Where the push's repository is open in an IntelliJ window - a clone whose remote names the repository the gate
forwards to - the notice and the review also offer *Open in* that project, named after it. Choosing it brings that
window forward and fetches the push there as `porelo/<push>`, so it can be read with the IDE's navigation and search
beside the project's own code. That ref is the one thing the plugin writes into a repository of yours, and only when
you choose it: the checked-out branch, the index and the working tree stay as they are, uncommitted changes
included. Once the push is approved or rejected the ref is deleted again, and after an approval the plugin fetches
that remote, so the forwarded commit is there without a fetch of yours. Work that stays on the machine, with no
upstream, is offered no project.

Reading it there is reading it, not running it: do not build the push or run its tests before it is approved.

## What it does not do

- **It never checks out a waiting push.** It reads it from a cache of its own with no working tree, so there is
  nothing the IDE could open as a project, import, build or run; *Open in* a project writes a ref, never a file.
- **It stores no credential.** It reaches a machine over the person's own ssh, with their ssh agent.
