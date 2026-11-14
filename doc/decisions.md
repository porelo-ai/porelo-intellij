# Decisions

| Subject | What holds | Detail |
|---|---|---|
| A host's daemon socket | The socket's directory, readable by its account alone, is the forward's whole guard | [A host's daemon socket](#a-hosts-daemon-socket) |
| The WSL way on Windows | WSL is the local way on a Windows machine, deliberately not ssh, so `WslBridge` stays though no Windows machine has run it yet | [The WSL way on Windows](#the-wsl-way-on-windows) |
| Three poms the digest check leaves out | Gradle reads them unchecked as version candidates and takes the release, whose files are checked | [Three poms the digest check leaves out](#three-poms-the-digest-check-leaves-out) |
| Which repository's snapshot wins | The first repository that holds a Porelo snapshot serves it - the host's registry before the central source - not the newest | [Which repository's snapshot wins](#which-repositorys-snapshot-wins) |
| Third-party jars in the zip | The plugin's zip bundles Porelo's own jars alone, so no third-party code ships without its license texts | [Third-party jars in the zip](#third-party-jars-in-the-zip) |
| The IDEs in the Actions cache | The cache keeps the IDEs' archives alone, under the IDE versions, and GitHub's eviction removes old entries | [The IDEs in the Actions cache](#the-ides-in-the-actions-cache) |

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

## The WSL way on Windows

On a Windows machine, Porelo running in WSL is local: the plugin reaches its daemon through `wsl.exe`, with no shell, no
port and no key. That is deliberately not ssh, which is for a host elsewhere. So `WslBridge` and a machine's `kind` stay,
though GitHub's Windows runner checks only the ssh way and no Windows machine has run the WSL way yet: that gap calls
for a trial on a real Windows machine, and is never a reason to remove the way.

## Which repository's snapshot wins

The build reads Porelo's snapshots from the host's registry, `MAVEN_SNAPSHOTS_URL`, before the central source, and
Gradle takes a snapshot from the first repository that holds it, not the newest as Maven does: a host's older snapshot
beats a newer central one. That stays because the host's registry holds what was built on that host, which a build there
is meant to use; a stale one is replaced by a newer Build on the host. A wrong `PORELO_PACKAGES_READ_TOKEN` fails the
build rather than falling through to the central source.

## Third-party jars in the zip

The plugin ships as a zip, not as a package, and its `lib/` holds `porelo-intellij` and `porelo-wire` alone. jspecify,
which `porelo-wire` pulls in, is left out of the runtime classpath: every IntelliJ IDEA the plugin supports carries it
itself, and its annotations change nothing at run time. `BundledJarsTest` reads the zip the build made and fails on any
other jar. The shared notices generator is not used, since it writes a JDK's section the zip does not ship and reads a
bill of materials this build does not make; a third-party jar the plugin must bundle would change the answer, and the
zip would then carry its license and notice texts.

## The IDEs in the Actions cache

The Build, the *Shared rules check* and the *Windows* job take the IntelliJ IDEA archives from the Actions cache, so a
build runs on an earlier run's downloads when JetBrains' download server does not answer. The cache holds
`caches/modules-2/files-2.1/idea` of Gradle's user home alone, some 4.5 GB on Linux: the IDEs unpacked as well were
some 17 GB, and restoring them once filled the runner's disk, so the transforms are made again in every run. The key is
the hash of `gradle.properties`, which names every IDE version, with no fallback key, so a version move never restores
the old archives beside the new ones; Gradle checks each restored archive against `gradle/verification-metadata.xml`
as it does a download. The *Shared rules check* restores the Build's entry and never saves one, since it resolves only
the IDE the plugin compiles against. No step deletes an entry, so no job holds a token that may write the cache: GitHub
removes an entry seven days after its last use and the oldest once the repository's cache passes its limit, 10 GB:
the repository keeps GitHub's defaults, and seven days is also the organization's maximum. A limit below the archives
of one key, or a runner without room for them, would change the answer.
