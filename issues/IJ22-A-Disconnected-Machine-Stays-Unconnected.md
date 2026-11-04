# IJ22 — A disconnected machine stays unconnected

**Status:** Idea.

**What must be true.** A person who ended the connection to a machine in the interface finds the plugin no longer
connected to it, at once if the IDE is open, and it stays so over a restart of either until they press *Connect* in the
interface. The plugin still lists that machine, and says why it does not reach it.

## The shape

What was read in the code; nothing is built or run.

- **The mark:** the interface already writes and reads `"disconnected": true` on a machine's entry in `frontend.json`.
  Absent, or anything but `true`, means connected. The entry stays otherwise as it is. This computer's own entry never
  carries it, as the plugin would add its own beside it. The file is a contract with the interface, changed only by
  agreement in both repositories.
- **The promise this breaks today:** the interface's design says that nothing connects to a disconnected machine until
  the person connects it again, also after a restart. The plugin does not read the mark: it goes on raising its ssh to
  that machine, reading it each minute and holding its stream.
- **Reading it:** `Machines` keeps a marked entry as one it does not reach, with a reason, so that neither a read nor
  the stream of projects raises an ssh for it. *Connect* needs nothing more: the file is read again on each read.
- **Ending what is open:** an open stream and tunnel do not end when an entry leaves the list. `PoreloWatch` checks the
  list only after a stream has ended, and `Connections` closes its connections only all at once, when the IDE closes. So
  one machine's stream, and its tunnel, relay or bridge, must be closed when its entry leaves the list or gains the mark.

## Acceptance

- A marked entry stands among those the plugin does not reach, with its reason, and no ssh is started for it.
- With the IDE open and a stream running, marking the entry ends that stream and its ssh; other machines' streams go on.
- Taking the mark away reaches the machine again at the next read.

## To be checked

- How soon the plugin should notice a mark with no read due, as it reads each minute: whether it watches the file.
- Whether a login by the plugin's ssh can affect a host's vault. The plugin holds no vault call, by reading; it is not
  measured.
