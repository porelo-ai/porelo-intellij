# IJ11 — Tried on a Windows machine

```json
{
  "$schema": "issue-1.json",
  "id": "IJ11",
  "title": "Tried on a Windows machine",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 5,
  "blockedBy": [],
  "covers": "The WSL way and the ssh way tried once on a real Windows machine, with what to install and what to try; what failed goes to the channel, what passed into `doc/` with the plugin's version"
}
```

**What must be true.** The ways the plugin reaches Porelo from Windows have been tried once on a real Windows machine:
the WSL way, which no Windows machine has run yet, and the ssh way, which only GitHub's Windows runner checks. What
failed is known, and what passed is said in `doc/` with the plugin's version.

The WSL way stays whatever this trial finds: on a Windows machine it is the local way, deliberately not ssh, and what
fails is fixed, not removed.

## The shape

What to install:

1. IntelliJ IDEA 2026.1 or newer for Windows, and Git for Windows.
2. A WSL 2 distribution with Porelo, installed as Porelo's own `doc/` says, and its daemon running
   (`porelo task list` answers in the distribution).
3. The plugin from the snapshot channel of `porelo-dist-intellij` (*Settings → Plugins → ⚙ → Manage Plugin
   Repositories*, with the URL of `snapshots/latest/updatePlugins.xml`).
4. `%APPDATA%\porelo\frontend.json` with `{"machines": [{"name": "wsl", "kind": "wsl", "distribution": "<name>"}]}`
   and, for the ssh way, an entry `{"name", "host", "socket", "remoteSocket"}` as the interface writes it, for a
   machine with Porelo that Windows' ssh reaches with the key in the OpenSSH Authentication Agent. Its `host` is one
   lower-case DNS label.
5. For the ssh entry, `%APPDATA%\porelo\ssh\<host>.conf` beside that file, the ssh configuration the interface writes
   from the host's card; without it the plugin lists the machine as one it does not reach.

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
