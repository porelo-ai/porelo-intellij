# IJ10 — Relays and connections end cleanly

**Status:** Idea.

**What must be true.** A relay, a WSL bridge or a tunnel the plugin raises never outlives its use, never leaves its
socket file or a child process behind, and never answers with half of what its probe read, also when a far end
keeps its output open, a call is interrupted, or the IDE closes while a connection is being raised.

## To be checked

What a reading of the code against the skills named, each older than the relay over ssh and carried into `Relay`
unchanged; each needs a test that reproduces it before it is fixed:

- `Relay.open` binds the socket before it makes it its owner's alone, and does not close the bound socket when
  that fails, so the next open meets "something already answers".
- `Relay.run` reads the probe's buffers after a reader that timed out may still write to them.
- A connection's pump stays while the far end keeps its output open after its input ended.
- `Connections.socketOf` can raise a connection after `close` has gathered the ones it ends.
- `Relay.close` leaves the socket file when the closing thread is interrupted.
- `Relay.end` and `Relay.run` end the child they started, not its descendants.
- A machine whose kind changes under the same name keeps its earlier tunnel or bridge on the socket path the new
  one needs.
