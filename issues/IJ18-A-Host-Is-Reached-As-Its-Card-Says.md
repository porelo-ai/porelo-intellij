# IJ18 — A host is reached as its card says

**Status:** Implementing; Estimated and Backlog (operator, 2026-10-30, sprint 6m).
**Size:** M - one contract another repository writes and this one reads, ssh calls in two packages wired through
four, harm tests on the Windows runner and as live tests, the guide and the documentation.

**What must be true.** The plugin reaches a host exactly as the host's card says: its address, port, account and SSH
host key. A host that answers with another host key is refused, naming the machine or its host, and the person's own ssh
configuration cannot redirect a host the plugin reaches.

## Why

Hosts are reached over SSH alone, with no VPN: each host's card names its address, SSH port and SSH host key, and each
person reaches it with their one SSH key from any machine. Today the plugin reaches a host by the name the interface
lists, through whatever the person's `~/.ssh/config` and known hosts make of it, so the card's host key would protect
nothing here.

## The shape

- For each host it knows from a host card, the Porelo interface writes one ssh configuration file,
  `$XDG_CONFIG_HOME/porelo/ssh/<host>.conf` (on Windows under `APPDATA`), holding `Host <host>`, `HostName`, `Port`,
  `User`, `IdentitiesOnly yes` with `IdentityFile` naming the person's public key, `UserKnownHostsFile` naming
  `<host>.known_hosts` in the same folder, which holds the card's host key, and `StrictHostKeyChecking yes`.
- The machine's entry in the interface's `frontend.json` keeps `host` as that same `<host>`.
- The host's account keeps a login shell, so `porelo daemon connect` and `git-upload-pack` run; the daemon's socket
  lies in a directory only that account reads. A Unix-socket forward has no per-path limit in `sshd`, so that
  directory's owner and mode are its whole guard.

## Plan

Read in today's code:

- `machine.Machines` reads `frontend.json` into `machine.Machine` records (`name`, `socket`, `host`, `remoteSocket`).
- `machine.Tunnel.raise` starts `ssh -N … -L <own socket>:<remote socket> -- <host>`, and its refusal names the
  machine; `machine.Relay.overSsh` starts `ssh -T … <host> porelo daemon connect --socket <path>` per connection on
  Windows.
- `gate.Waiting.source` makes a review's address `ssh://<host><mirror>`. `cache.ReviewCache` runs `git ls-remote`,
  then `git fetch`, on it (`fetch`, and `fetchInto` into the person's repository), and sets `GIT_SSH_COMMAND` only when
  the person set none of `GIT_SSH_COMMAND`, `GIT_SSH` or `core.sshCommand`. `ide.PoreloWatch` calls both.
  `fetchRemote` fetches the person's own upstream remote.

Steps:

1. **`machine.Machine`, `machine.Machines`:** a machine over ssh carries the path of its `<host>.conf`. An entry
   whose file is missing goes to the machines not reached, naming the missing file, and is never reached through the
   person's own ssh configuration.
2. **`machine.Tunnel`, `machine.Relay`:** both pass `-F <host>.conf` right after the ssh they are given and before
   their own options, so it is the last `-F` ssh reads, the one that counts.
3. **`gate.Waiting`, `cache.ReviewCache`, `ide.PoreloWatch`:** a review's address from a host reached this way carries
   its `<host>.conf`, and `ls-remote` and `fetch` in `fetch` and `fetchInto` run ssh with `-F <host>.conf`, over any
   `GIT_SSH_COMMAND`, `GIT_SSH` or `core.sshCommand` of the person's. `fetchRemote`, the person's own remote, and an
   address the gate names for a socket somebody else forwards keep the person's ssh.
4. **`.github/workflows/windows.yml`:** the runner's `~/.ssh/config` gains an entry diverting `<host>` to its sshd,
   and the sshd serves a mirror for `fetch` and `fetchInto`; the test key stays in Windows' agent, as today.
5. **`doc/index.md`, `doc/operator-test.md`:** the hand-made `~/.ssh/config` entry goes; the guide's steps take the
   interface's file.
6. **Changelog:** one entry under `changelog/unreleased/`, type *changed*.

ssh reads `~/.ssh` from the account's home, not from `HOME`, so no test writes the `~/.ssh` of a machine it runs
on. The tests that run ssh run on GitHub's Windows runner in `windowsSshTest`, whose workflow already writes the
runner's own `~/.ssh` and which is thrown away afterwards, and as live tests from the host against the VM, which stand
the person's own ssh configuration as the file they hand the plugin's ssh, as they do today. `build` runs none of
them.

- **Red first, against a connection diverted to a machine that is not the host:** the person's ssh configuration
  (the runner's `~/.ssh`, or the live test's file) points `<host>` at an sshd and trusts its key, while
  `<host>.known_hosts` holds another key, the card's. Today each call connects; afterwards each refuses. The calls
  are the relay, `fetch` and `fetchInto` on the Windows runner, and the tunnel, the relay, `fetch` and `fetchInto` in
  the live tests; `fetch` and `fetchInto` each three times, with `GIT_SSH_COMMAND`, `GIT_SSH` (a script running the
  person's ssh) or `core.sshCommand` set alone, since git takes only one of them. The tunnel's refusal names the
  machine, the relay's and the fetch's the host.
- **The same calls with `<host>.known_hosts` holding the sshd's own key reach the daemon and fetch the review**, so a
  refusal above comes from the key and not from a call that never works.
- **The fallback to the person's unchecked ssh:** a machine without its `<host>.conf` is among those not reached,
  naming the missing file, and is never connected to; a unit test in `build`, since it starts no ssh.

## Acceptance

- On GitHub's Windows runner through `ssh.exe`, and as live tests on the VM, each with the calls named above: the
  card's host key reaches the daemon and fetches a review; the diverted connection is refused by every call, seen red
  first.
- A machine whose `<host>.conf` is missing is not connected to, and its refusal names the file.
- `build`, `verifyPlugin` and `documents` green.
