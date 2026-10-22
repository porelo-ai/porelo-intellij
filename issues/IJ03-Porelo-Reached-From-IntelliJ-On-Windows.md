# IJ03 — Porelo reached from IntelliJ on Windows

**Status:** Implementing; both parts are built, and the Windows job ran green on GitHub's runner. That job
runs by hand only (`workflow_dispatch`), before a release or after a change to the ssh part, not on every Build; it
checks the ssh way, never the WSL way. The index row says "untested on a Windows machine" until someone runs
the test on a Windows machine below, then "tested on Windows on <version>", also once this issue is finished; that
test is welcome, not required.
**Size:** M - part 1 is built; part 2 is a relay over ssh in the shape of the WSL bridge, its tests, and a Windows
job on GitHub's runner.

**What must be true.** A developer whose Porelo runs in a WSL2 distribution on their Windows machine reviews its
waiting work in IntelliJ IDEA on Windows, as they would on Linux, with no ssh into the distribution and no port
opened for the daemon; one whose Porelo runs on another machine reaches it over Windows' own ssh, as on Linux.

## The shape

### 1. Seen on Windows

- On Windows the plugin reads `%APPDATA%\porelo\frontend.json`, reaches an entry `{"name", "kind": "wsl",
  "distribution"}` through a socket of its own under `%LOCALAPPDATA%\porelo-intellij`, and starts
  `wsl.exe -d <distribution> --exec porelo daemon connect` for each connection, as a process with its own byte
  streams and no shell in the distribution either.
  It refuses a distribution that is stopped, not WSL 2, or not there, in words, and starts none.
- A review fetches the push from `//wsl.localhost/<distribution><mirror>`, with `safe.directory` trusted for that
  call only, since Windows' git sees the distribution's user as a stranger.
- On Linux the bridge runs against a stand-in for `wsl.exe` and a real daemon's socket, and the Windows paths and the
  fetch address are tested as values; the trust for git's fetch is not tested anywhere. GitHub's Windows runner has
  no WSL 2 distribution with Porelo, so the socket under `%LOCALAPPDATA%`, `wsl.exe`'s list as it prints it, and
  git's fetch from `\\wsl.localhost` stay untested on Windows.

### 2. A machine over ssh from Windows

- On Windows an entry over ssh is shown as one the plugin does not reach yet: its tunnel is `ssh -L` to a unix
  socket, with `/dev/null` and POSIX permissions, which Windows does not have.
- It is reached as the WSL distribution is: each connection to the plugin's socket starts
  `ssh -o BatchMode=yes <host> porelo daemon connect --socket <remote socket>` as a process with its own byte
  streams, the key from Windows' OpenSSH Authentication Agent, as `porelo-frontend` [F108](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f108) does it.

## Plan

1. **Built:** `WslBridge` becomes a relay of any command (`Relay`): the probe, the socket, the pumps and ending the
   children on close stay; the WSL list check stays WSL's. `WslBridgeTest` keeps passing unchanged.
2. **Built:** `Relay.overSsh(machine, …)`: the command above, the remote socket quoted for the remote shell, the
   probe's failure in ssh's words. `LiveRelayOverSshTest` reads a real daemon through it, with a real machine's shell and
   `porelo daemon connect --socket` at the far end. Red first in a test with
   a stand-in `ssh` in the shape of `FakeWsl`, which records its arguments and relays to a real socket.
3. **Built:** `Machines` on Windows turns an ssh entry into a reachable machine instead of an unreachable one;
   `PoreloWatch.socketOf` opens the relay for it. Red first in `MachinesTest`'s Windows case, which expects it
   unreachable today.
4. **Built:** `ReviewCache` fetches over the same ssh: `GIT_SSH_COMMAND` names Windows' `ssh.exe`
   (`%SystemRoot%\System32\OpenSSH\ssh.exe`, `Machines.ssh()`), so git for Windows does not use its own ssh, which
   knows neither the agent nor the person's hosts.
5. **Built:** a Windows job, `windows.yml`, started by hand (the operator's decision of 2026-10-22, taken while the
   repository was private and Windows minutes were billed; a public repository's runners cost no minutes, so whether
   it runs on every Build is open again), on GitHub's Windows runner: the plugin's tests, and `WindowsSshRelayTest` (`./gradlew windowsSshTest`)
   through Windows' own `ssh.exe` to an OpenSSH server on the runner itself. Its remote shell is Git for Windows'
   bash, as a Linux machine's would be, and its `porelo` is `FakePorelo`, which joins `daemon connect` to a stand-in
   daemon's socket; red first with `PORELO_FAKE_DROP_NUL=1`, which drops the daemon's NUL bytes. The job runs on
   the runner's own Java 25, since `porelo-machines` pins a GraalVM for Linux only (the operator's decision of
   2026-10-22), and `verification-metadata.xml` holds the Windows IntelliJ IDEA (`idea-2026.1-win.zip`), checked
   against JetBrains' published SHA-256. It is made to work on the branch `ij03-windows`, pushed by the
   coordinator round by round, and reaches `main` as one commit once green. **Measured there:** the tests pass on
   Windows in about 19 minutes, most of it the IDE's download; those that need porelo-wire's `VarlinkServer`
   (an owner-only socket, which Windows cannot give), a tunnel (Linux only) or a Linux socket path are skipped
   there, each with its reason. The runner's OpenSSH server answers 6 s after it is set up. Through `ssh.exe` the
   daemon's NUL bytes arrive unchanged, seen red with the stand-in dropping them.
6. **Built:** `doc/` says that the ssh way is checked on GitHub's Windows runner, by hand before a release, and that
   the WSL way is untested on a Windows machine.

## Acceptance

- On GitHub's Windows runner, in the job started by hand: a machine over ssh reached through `ssh.exe` answers the
  plugin's gate, and the daemon's NUL bytes arrive unchanged; for an account the server does not know, so that no
  key in the agent is taken, with `BatchMode`, it is not reached, in ssh's words, and nothing waits. Each seen to
  fail first.
- On Linux, with a stand-in for `wsl.exe` and one for `ssh`: the relay, the refusal of a stopped, missing or WSL 1
  distribution, and closing the children with the relay, as today.
- **Untested on a Windows machine** until the test below is done, and said so in the index row and in `doc/`: the
  WSL way (the tool window with a
  distribution's pushes, its diff, approve and reject; a stopped distribution left stopped; another Windows user
  not reaching it) and git's fetch from `\\wsl.localhost`.

## A test on a Windows machine

What to install:

1. IntelliJ IDEA 2026.1 or newer for Windows, and Git for Windows.
2. A WSL 2 distribution with Porelo, installed as Porelo's own `doc/` says, and its daemon running
   (`porelo task list` answers in the distribution).
3. The plugin from the snapshot channel of `porelo-dist-intellij` (*Settings → Plugins → ⚙ → Manage Plugin
   Repositories*, with the channel's `updatePlugins.xml`); until that channel exists, the ZIP of the Build's
   latest run on `main`, through *Install Plugin from Disk*.
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

What failed, with the plugin's version, goes to the channel; when nothing failed, the index row reads "tested on
Windows on <version>".
