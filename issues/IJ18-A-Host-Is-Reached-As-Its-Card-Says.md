# IJ18 — A host is reached as its card says

**Status:** Review.
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

1. **`machine.Machine`, `machine.Machines`:** a machine over ssh carries the path of its `<host>.conf`, and `Machine`
   refuses a host without one. An entry whose host is no lower-case DNS label, or whose file is missing, goes to the
   machines not reached, naming why, and is never reached through the person's own ssh configuration.
2. **`machine.Tunnel`, `machine.Relay`:** both pass `-F <host>.conf` right after the ssh they are given and before
   their own options, so it is the last `-F` ssh reads, the one that counts.
3. **`gate.Waiting`, `cache.ReviewCache`, `ide.PoreloWatch`:** `fetch` and `fetchInto` take the machine's
   `<host>.conf`, and their `ls-remote` and `fetch` run with `GIT_SSH_COMMAND` set to the plugin's ssh with
   `-F <host>.conf` and `GIT_SSH` removed; `GIT_SSH_COMMAND` outranks `core.sshCommand`. `fetchRemote`, the person's
   own remote, and an address the gate names for a socket somebody else forwards keep the person's ssh.
4. **`doc/index.md`:** how a host is reached. `doc/operator-test.md` names no ssh configuration of its own, so it
   stays.
5. **Changelog:** one entry under `changelog/unreleased/`, type *changed*.

ssh reads `~/.ssh` from the account's home, not from `HOME`, so no test writes the `~/.ssh` of a machine it runs on.

- **Red first, against a connection diverted to a machine that is not the host:** the person's ssh configuration
  reaches the host and trusts its key, while the host card's configuration pins another key and includes the
  person's for the address and account. Today each call connects; afterwards each refuses. The relay runs so on
  GitHub's Windows runner in `windowsSshTest`, against the runner's own `~/.ssh/known_hosts`, which its workflow
  already writes; the tunnel and the relay in `liveTest` against the VM, with the person's configuration as the file
  the live tests hand their ssh. The tunnel's refusal names the machine, the relay's the host.
- **The review fetches:** in `build`, with a stand-in ssh that serves the mirror only when given `-F <host>.conf`,
  and the person's ssh set alone by `GIT_SSH_COMMAND`, `GIT_SSH` and `core.sshCommand` in turn; `fetch` and
  `fetchInto` each fetch the push, and the person's ssh is never run. Without the override the person's ssh is run
  and the fetch fails, seen red.
- **The same calls with the card naming the host's own key reach the daemon**, on the runner and in the live tests,
  so a refusal above comes from the key and not from a call that never works.
- **The fallback to the person's unchecked ssh:** a machine without its `<host>.conf`, or with a host name no card
  gives, is among those not reached, naming why; a unit test in `build`, seen red without the check.

## Acceptance

- On GitHub's Windows runner through `ssh.exe`, and as live tests on the VM, with the calls named above: the card's
  host key reaches the daemon; the diverted connection is refused, seen red first.
- In `build`: the review fetches go through the host's configuration alone, whichever way the person named an ssh,
  seen red without the override.
- A machine whose `<host>.conf` is missing is not connected to, and its refusal names the file.
- `build`, `verifyPlugin` and `documents` green.
