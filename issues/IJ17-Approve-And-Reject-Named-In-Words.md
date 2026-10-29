# IJ17 — Approve and Reject named in words

**Status:** Analyzed.
**Size:** XS - one file of the plugin, its guide and a changelog entry; no contract, and no test, since it changes only
what reads wrong.

**What must be true.** A person reading a review tab for the first time finds *Approve* and *Reject* without
hovering: the two decisions are named in words at the top of the tab, not only as a check mark and a cross whose
tooltips say *Approve* and *Reject*.

## Why

The operator's walk found that the two decisions show as icons only, beside the tab's other actions.
`doc/operator-test.md` now names the icons; the tab itself does not.

## Plan

Read in today's code: `ReviewEditor.header` puts *Approve*, *Reject*, *Open in …* and *Read Again* as `Act` actions
into one `ActionToolbar`, which shows an action's icon and its text only as a tooltip.

1. **`ReviewEditor`:** *Approve* and *Reject* show their text beside their icon, through the template presentation's
   `ActionUtil.SHOW_TEXT_IN_TOOLBAR`, a public key of the platform; *Open in …* and *Read Again* stay icons with
   tooltips.
2. **`doc/operator-test.md`:** the steps that name the check mark and the cross name the buttons *Approve* and *Reject*
   again.
3. **Changelog:** one entry under `changelog/unreleased/`, type *changed*.

No test is written: a label reads wrong or right and does no harm, as `AGENTS.md` says.

## Acceptance

- In the development IDE (`./gradlew runIde`), an open review tab shows *Approve* and *Reject* in words beside their
  icons; a screenshot of the tab's header is attached to the handover.
- `verifyPlugin` passes against every IntelliJ IDEA version `gradle.properties` names.

## To be checked

- That `verifyPlugin` reports no internal or experimental use for `ActionUtil.SHOW_TEXT_IN_TOOLBAR`.
