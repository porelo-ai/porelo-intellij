# Decisions

| Subject | What holds | Detail |
|---|---|---|
| A host's daemon socket | The socket's directory, readable by its account alone, is the forward's whole guard | [A host's daemon socket](#a-hosts-daemon-socket) |

## A host's daemon socket

The plugin forwards a host's daemon socket over ssh, and the host's account keeps a login shell so that `porelo daemon
connect` and `git-upload-pack` run there. `sshd` limits a Unix-socket forward by no path, so the account could forward
any socket it can open. The exposure is that account's own sockets; it stays because the account is the person's own,
reached only with their key, and the daemon's socket lies in a directory only that account reads. A host account shared
between people, or an `sshd` able to limit a forward to one path, would change the answer.
