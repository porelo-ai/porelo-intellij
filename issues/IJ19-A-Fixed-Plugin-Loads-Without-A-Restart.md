# IJ19 — A Fixed Plugin Loads Without A Restart

```json
{
  "$schema": "issue-1.json",
  "id": "IJ19",
  "title": "A Fixed Plugin Loads Without A Restart",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 3,
  "blockedBy": [],
  "covers": "A fixed plugin unloads and loads into the running IDE without a restart; today the IDE refuses it as not unload-safe and is left without Porelo"
}
```

**Severity.** Medium.

**What must be true.** A fixed Porelo plugin loads into the running IntelliJ IDEA without a restart: the old one
unloads, the new one loads and reads again, and nothing open in the IDE has to be closed. A small fix during an
operator test does not cost a restart of the IDE.

## Why

Today every fix to the plugin needs the IDE restarted. The IDE's own reload of a changed plugin (`runIde` sets
`idea.auto.reload.plugins`) noticed the new jar in the sandbox, then refused it with `Plugin org.fuin.porelo is not
unload-safe because class loader cannot be unloaded` and offered a restart. After that refusal neither the old nor the
new plugin was loaded, so the IDE had no Porelo until it restarted. A plain *Disable* in the plugin manager unloaded
the plugin, but it does not check that the class loader can go.

Something the plugin starts still holds its classes when the IDE wants to drop them. The likely holders are
`PoreloWatch`'s scheduled reads, its stream threads and its daemon connections. That is not yet shown.

The reading once a minute starts only on `AppLifecycleListener.appFrameCreated`, when the IDE starts, so a plugin
loaded into a running IDE would not read on its own until *Read Again*.

## Acceptance

- In `runIde`, with the Porelo tool window open and a machine streaming, `prepareSandbox_runIde` followed by a file
  refresh reloads the plugin: the IDE logs that it reloaded successfully, the tool window shows the new code, and the
  reading once a minute goes on.
- A failed unload is seen to fail first, then to pass, for each holder the fix removes.

## To be checked

- What holds Porelo's class loader on a failed unload, read from the heap snapshot the IDE writes for it.
- Whether the IDE notices a changed jar without a manual refresh once its window regains focus on a desktop.
