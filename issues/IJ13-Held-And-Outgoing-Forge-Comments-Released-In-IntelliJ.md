# IJ13 — Held And Outgoing Forge Comments Released In IntelliJ

**Status:** Idea; blocked by [IJ04](README.md#ij04).

**What must be true.** A person reviewing waiting work in IntelliJ sees held inbound forge comments (`E0`, and `E1` where held by policy) and outgoing comments, replies, suggestions and verdicts waiting to go to a forge, each at its anchor in the review - beside its line through [IJ12](README.md#ij12)'s adapter, or in [IJ04](README.md#ij04)'s discussion panel for a file or the review - with its exact destination, operation, commit and content, and releases or drops each one alone through the daemon, never several at once; an edit invalidates a prior release. The interface shows these comments only as a count and releases none of them.

## Open questions

- Whether approving and merging a pull request on the central forge, which the interface no longer offers, moves into
  the plugin or is left to the forge's own pages.

## To be checked

- **Daemon contract and refusal codes:** Confirm the exact Varlink methods, parameters, and refusal codes that `Gate` calls on `org.fuin.porelo.Tasks1` (`Gate.INTERFACE`) to list, release, and drop held inbound items (`E0` comments, plus `E1` and `A` suggestions that wait for release) and pending outbound forge operations (create, reply, edit, delete, resolve, unresolve, and verdict), and how rate-limit holds or unsupported native action holds are reported.
- **Destination, commit, and content-digest binding on release:** Confirm how `Gate` passes the exact forge instance, destination repository, pull request, operation kind, target native or thread IDs, full commit SHA, and digest of the final serialized content on each release call so that any edit to a comment or suggestion or any change of destination invalidates a prior release and requires a fresh release.
- **Presentation in `ReviewTab` and `ReviewEditor`:** Confirm how `ReviewTab.Read` and `ReviewEditor` show the held inbound and pending outbound queues per push (including when a project rule permits `I1` replies to go out without manual release and when machine policy forbids that exception), keeping all forge credentials on the host and rendering held previews through [IJ04](README.md#ij04)'s safe, network-free Markdown subset while preserving the original `porelo/sourceLevel` after release.
- **Remote thread resolution authority:** Confirm how `ReviewEditor` distinguishes a forge thread resolved by an authenticated `I1` account (which clears its mapped gate finding) from one resolved by `E0`, `E1`, or `A` (which is shown as remote state only and never clears a gate finding or approves a commit).
