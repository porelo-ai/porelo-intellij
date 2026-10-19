# IntelliJ plugin

The Porelo plugin for IntelliJ IDEA lets a developer review an agent's waiting work in the IDE they know, as they
would review a colleague's change: they see what waits at Porelo's gate, read its diff in IntelliJ's own diff
windows, and approve or reject it. Nothing the agent wrote is checked out, built or run on their computer.

It supports IntelliJ IDEA 2026.1 and newer. On Windows it reaches Porelo in a running WSL 2 distribution the
interface lists, through `wsl.exe` and `porelo daemon connect`, with no ssh and no port opened; it never starts a
distribution that is stopped. This is untested on a Windows machine: the WSL way is built and tested on Linux
against stand-ins only.

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
