# IntelliJ plugin

The Sokar plugin for IntelliJ IDEA lets a developer review an agent's waiting work in the IDE they know, as they
would review a colleague's change: they see what waits at Sokar's gate, read its diff in IntelliJ's own diff
windows, and approve or reject it. Nothing the agent wrote is checked out, built or run on their computer.

It supports IntelliJ IDEA 2026.1 and newer. On Windows it reaches Sokar in a running WSL 2 distribution the
interface lists, through `wsl.exe` and `sokar daemon connect`, with no ssh and no port opened; it never starts a
distribution that is stopped. This is untested on a Windows machine: the WSL way is built and tested on Linux
against stand-ins only.

## What it does not do

- **It never checks out a waiting push.** It fetches into a cache of its own with no working tree, so there is
  nothing the IDE could open as a project, import, build or run.
- **It stores no credential.** It reaches a machine over the person's own ssh, with their ssh agent.
