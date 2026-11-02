# Decisions

| Subject | What holds | Detail |
|---|---|---|
| A host's daemon socket | The socket's directory, readable by its account alone, is the forward's whole guard | [A host's daemon socket](#a-hosts-daemon-socket) |
| Three poms the digest check leaves out | Gradle reads them unchecked as version candidates and takes the release, whose files are checked | [Three poms the digest check leaves out](#three-poms-the-digest-check-leaves-out) |

## A host's daemon socket

The plugin forwards a host's daemon socket over ssh, and the host's account keeps a login shell so that `porelo daemon
connect` and `git-upload-pack` run there. `sshd` limits a Unix-socket forward by no path, so the account could forward
any socket it can open. The exposure is that account's own sockets; it stays because the account is the person's own,
reached only with their key, and the daemon's socket lies in a directory only that account reads. A host account shared
between people, or an `sshd` able to limit a forward to one path, would change the answer.

## Three poms the digest check leaves out

Gradle checks every artifact the build resolves against `gradle/verification-metadata.xml`, but never a snapshot. Inside a version
range it reads the poms of `261.22158-EAP-CANDIDATE-SNAPSHOT` for `java-compiler-ant-tasks`, `test-framework` and
`test-framework-junit5`, then takes the release 261.22158.277, whose jars and poms have entries. So the exposure is those three poms,
served by JetBrains' repository, and not code: no jar of a snapshot is downloaded. Whether a pom alone can change what is resolved was
not tested. It stays because the IntelliJ Platform's plugin sets that range, and the release's own files are checked. A jar of a
snapshot among the downloads, or a way to name the release without the range, would change the answer.
