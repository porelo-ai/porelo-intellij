# IJ31 — The plugin overview says what it does

```json
{
  "$schema": "issue-1.json",
  "id": "IJ31",
  "title": "The plugin overview says what it does",
  "state": "Backlog",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "Settings > Plugins shows Porelo from the custom plugin repository with what it does, its vendor, a link to the documentation and change notes, since the repository's `updatePlugins.xml` carries the built plugin.xml's description, change notes and vendor; today its Overview, What's New and Additional Info are empty"
}
```

**Why this size.** One Gradle task, the plugin descriptor and one test class; no code of the plugin and no contract
another repository reads.

**What must be true.** A person who adds Porelo's custom plugin repository and opens Porelo in Settings > Plugins >
Marketplace reads what the plugin does, who publishes it, where its documentation is and where its changes are, before
installing it.

## Plan

Read in today's code: `src/main/resources/META-INF/plugin.xml` carries a `<description>` and a `<vendor>`, no
`<change-notes>`. The IDE shows a plugin of a custom repository from that repository's `updatePlugins.xml`, not from
the zip it has not downloaded yet, and the `updatePluginsXml` task in `build.gradle.kts` writes only `<name>` and
`<idea-version>` there.

1. **Red first:** `UpdatePluginsTest` asserts that the built plugin's `META-INF/plugin.xml`, read from the jar in the
   zip, carries a description, change notes and a vendor with its URL, and that `updatePlugins.xml` carries the same
   three; it fails today on the missing `<description>` of `updatePlugins.xml`.
2. **`plugin.xml`:** the description says what the plugin does and links to the documentation; `<change-notes>`
   link to the changelog.
3. **`updatePluginsXml`:** reads the patched descriptor, the one the zip carries, and writes its description and
   change notes as CDATA and its vendor into the `<plugin>` entry, failing the build when one is missing or a text
   holds `]]>`.

## Acceptance

- `UpdatePluginsTest` is seen red on today's `updatePluginsXml` and green after it.
- A snapshot installed from the custom repository in IntelliJ IDEA shows the description, the vendor and the change
  notes in Settings > Plugins.
