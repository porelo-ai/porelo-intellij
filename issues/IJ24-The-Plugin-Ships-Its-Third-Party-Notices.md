# IJ24 — The plugin ships its third-party notices

```json
{
  "$schema": "issue-1.json",
  "id": "IJ24",
  "title": "The plugin ships its third-party notices",
  "state": "Implementing",
  "size": "XS",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The plugin's zip ships no third-party code without its license texts: jspecify, which the IDE carries itself, leaves the zip, so only Porelo's own jars remain, and a test fails once any other jar enters it, which is when the shared notices generator would be adopted"
}
```

**Why this size.** one build file and one unit test, no contract.

**What must be true.** Whoever installs the plugin never receives third-party code without its license and notice
texts: the plugin's zip bundles Porelo's own jars alone, and a build that would bundle any other jar fails, naming it.

## How it stands

- Backlog (operator, 2026-11-10).
- The plugin ships as a zip, not as a `.deb` or `.rpm`. Its `lib/` holds three jars, read from the zip `buildPlugin`
  makes and from `dependencies --configuration runtimeClasspath`:
  - `porelo-intellij-<version>.jar`, the plugin itself.
  - `porelo-wire-0.5.0.jar`, Porelo's own client, `GPL-3.0-or-later` by its pom.
  - `jspecify-1.0.0.jar`, `Apache-2.0`, pulled in by `porelo-wire`; the jar holds no `LICENSE` or `NOTICE`, only the
    license's URL in its manifest. Apache-2.0 asks a redistribution to give a copy of the license, which the zip does
    not.
- IntelliJ IDEA 2026.1, 2026.1.5 and 2026.2.3, the ones the build resolves, carry jspecify themselves in
  `lib/intellij.libraries.jspecify.jar`; the plugin's own code takes it `compileOnly` already. Its annotations change
  nothing at run time, so the plugin runs the same without its copy.
- The shared notices generator in `porelo-buildtools` is made for a native executable: it always writes the runtime's
  section from a JDK home, which the plugin does not ship, and it reads a CycloneDX bill, which this build does not
  make. With no third-party jar left in the zip, there is nothing for it to write.

## Plan

- `build.gradle.kts`: the plugin's `runtimeClasspath` leaves out `org.jspecify`, so `buildPlugin` bundles
  `porelo-intellij` and `porelo-wire` alone; compilation and the tests keep jspecify.
- Red first: `BundledJarsTest`, a unit test beside `UpdatePluginsTest`, reads the zip the build made (`porelo.zip`) and
  asserts that its `lib/` holds exactly the plugin's jar at the build's version and `porelo-wire` at the version
  `gradle/libs.versions.toml` names. It fails today on `jspecify-1.0.0.jar`.
- `ideTest` and `verifyPlugin` show that the plugin still loads and resolves against 2026.1 and newer.
- A changelog entry: the zip no longer carries jspecify.

## Acceptance

- The zip `buildPlugin` makes holds `porelo-intellij` and `porelo-wire` in `lib/` and nothing else.
- `BundledJarsTest` was seen red on today's zip, and goes red when a third-party jar is added to the plugin's runtime
  dependencies.
- `build`, `verifyPlugin` and `documents` pass.
