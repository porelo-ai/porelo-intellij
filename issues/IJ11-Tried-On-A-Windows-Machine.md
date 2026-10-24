# IJ11 — Tried on a Windows machine

**Status:** Idea.

**What must be true.** The ways the plugin reaches Porelo from Windows have been tried once on a real Windows machine:
the WSL way, which no Windows machine has run yet, and the ssh way, which only GitHub's Windows runner checks. What
failed is known, and what passed is said in `doc/` with the plugin's version.

## The shape

What to install:

1. IntelliJ IDEA 2026.1 or newer for Windows, and Git for Windows.
2. A WSL 2 distribution with Porelo, installed as Porelo's own `doc/` says, and its daemon running
   (`porelo task list` answers in the distribution).
3. The plugin from the snapshot channel of `porelo-dist-intellij` (*Settings → Plugins → ⚙ → Manage Plugin
   Repositories*, with the URL of `snapshots/latest/updatePlugins.xml`).
4. `%APPDATA%\porelo\frontend.json` with `{"machines": [{"name": "wsl", "kind": "wsl", "distribution": "<name>"}]}`
   and, for the ssh way, an entry `{"name", "host", "socket", "remoteSocket"}` as the interface writes it, for a
   machine with Porelo that Windows' ssh reaches with the key in the OpenSSH Authentication Agent.

What to try, each for the WSL entry and the ssh entry:

- Start a task in Porelo that pushes; the push is listed in the tool window, and its diff opens in IntelliJ's diff
  windows.
- Reject one with a reason and approve another; each reaches the daemon (`porelo task list`).
- Stop the distribution (`wsl --terminate <name>`); the tool window says it is stopped, and it stays stopped.
- Log in as another Windows user; the plugin there does not reach the first user's distribution.
- For the ssh entry: remove the key from the agent; the machine is shown as not reached, and nothing waits.
- Leave the IDE open for some hours with the WSL entry: the stream of projects through `porelo daemon connect`
  stays open, or is opened again within the minute's read when it drops; a push made after hours is still
  announced within seconds.

What failed, with the plugin's version, goes to the channel; when nothing failed, this issue is done.
