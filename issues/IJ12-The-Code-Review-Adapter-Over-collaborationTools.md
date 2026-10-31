# IJ12 — The Code Review Adapter Over collaborationTools

**Status:** Idea.
**Size:** M - one new package of about eight classes, a diff extension, one key set in `ReviewEditor`, a test task
of its own with the platform's test framework, and the verification metadata renewed; no contract changes. The
spike built and ran all of it against stand-in threads.

**What must be true.** The IntelliJ plugin attaches inline comment threads, gutter controls, and multi-line range
commenting to Porelo's diff viewers through one narrow, replaceable pure-Java adapter package over the platform's
`intellij.platform.collaborationTools` module, without exposing experimental JetBrains types to the rest of the
plugin, depending on `qodana-sarif` or internal JetBrains classes, or altering non-Porelo diffs.

## The shape

A spike built the adapter in pure Java against stand-in threads and ran it headless. What it measured:

- **Wiring.** `bundledModule("intellij.platform.collaborationTools")` in `build.gradle.kts` is enough; `plugin.xml`
  needs no `<dependencies>` entry, as for `intellij.platform.vcs.impl`: the module is a public content module of the
  core plugin. Gradle refuses the build until `gradle/verification-metadata.xml` names the module's jar and its
  generated Ivy file; the jar's SHA-256 equals the jar's in the IntelliJ IDEA distribution the build already checks.
- **Verifier.** `./gradlew verifyPlugin` reads *Compatible* against 2026.1.5 and 2026.2.3, with 22 usages of
  experimental API, every one in the adapter package, and no internal API. The adapter's signatures are byte-identical
  in 2026.1 and 2026.1.5; 2026.2.3 adds only default bridge methods for `getLine` and `isVisible`, which a Java class
  compiled against 2026.1 still satisfies.
- **The entry point** is `DiffViewerUtilKt.showCodeReview(DiffViewerBase, Function6, Function2, Continuation)`: it
  asks for one model per editor (the left and the right editor of a side-by-side viewer, the one editor of a unified
  viewer, which gets `side == null`) and hands each the functions from a diff location (`Side`, line) to the editor's
  line and back, so the adapter never maps unified lines itself. It switches to the UI thread itself and returns only
  when its scope is cancelled.
- **Java bridge.** No Kotlin source is needed. A suspending call is started with `BuildersKt.launch` on a
  `CoroutineScope(SupervisorJob + Dispatchers.Default)` whose block passes its `Continuation` on to
  `showCodeReview`; flows are `StateFlowKt.MutableStateFlow`; the interfaces' Kotlin default methods are Java default
  methods. The plugin bundles no Kotlin library: `kotlin.*` always comes from the platform.
- **Only Porelo's diffs.** A `DiffExtension` attaches only when the request carries `ReviewContext.KEY` (project,
  repository, base and head commit, path), which `ReviewEditor.showSelected` sets on each `SimpleDiffRequest`. The
  test shows a diff without it - side by side and unified - gets no model and no inlay, and turns red when the
  check is removed.
- **Lines.** A SARIF region (1-based, both ends included, `old` or `new`) becomes `LineRange(start - 1, end)` on
  `Side.LEFT` or `Side.RIGHT` and back; anything off its side is refused. Columns (`unicodeCodePoints`) do not reach
  the viewer: threads are placed by line. In the test, a thread on the pushed side's line 4 is shown after that line
  side by side and after the same text in the unified viewer.
- **Disposal.** The scope is cancelled when the viewer is disposed, which a file switch (`diff.setRequest`), *Read
  Again* (the editor closed, a new one opened) and a closed tab (the `ReviewEditor` the diff panel is registered with)
  all come to. Cancellation ends on the UI thread after `dispose` returns: the test waits for every follower to stop,
  then finds as many editors as before and the platform's leak hunter finds none. It turns red when the scope is
  not cancelled.
- **SARIF libraries.** `porelo-wire` depends on `jspecify` alone, so `java-sarif` and its Jackson are bundled by the
  plugin itself. The plugin's class loader loads only `java.*` and `kotlin.*` from the platform first and everything
  else from the plugin's own jars, so a bundled Jackson is the one Porelo's classes see, beside the IDE's Jackson
  2.19.0, without shading.
- **Test framework.** `TestFrameworkType.Platform` and `JUnit5` add about forty components to the verification
  metadata, among them `assertj-core` 4.0.0-M1 and JUnit 5.13.4 beside the build's own. On the `test` task's class
  path its thread-leak check fails `GateTest`, which passes without it, on the JDK's virtual-thread poller. A test
  JVM started from a Gradle daemon that has `WAYLAND_DISPLAY` loads the Wayland toolkit even headless.

## Plan

- `build.gradle.kts`: the bundled module; a source set `ideTest` with its own task for the tests that start the
  headless IDE, so `test` keeps its class path, with `WAYLAND_DISPLAY` and `DISPLAY` removed and `java.awt.headless`
  set; `plugin.xml`: `diff.DiffExtension`.
- A `@NullMarked` package `org.fuin.porelo.intellij.review`, the only one that imports `com.intellij.collaboration`
  or `kotlinx.coroutines`: `ReviewContext` with its key, `Lines`, `CommentThread`, `Threads` (where the threads of
  a file come from and new ones go; [IJ04](README.md#ij04) implements it over the daemon), `Coroutines`,
  `EditorModel` with its inlays and gutter controls, and `ReviewDiffExtension`. Until [IJ04](README.md#ij04) the
  extension attaches nothing, so no stand-in ships.
- `ReviewEditor.showSelected` puts the `ReviewContext` on every request.
- Red first: `LinesTest`, then `ReviewDiffExtensionTest` in `ideTest` (another diff gets nothing; a switch, a close
  and a dispose leave no follower and no editor), each broken on purpose once.
- `gradle/verification-metadata.xml` renewed from an empty Gradle home, as [IJ09](README.md#ij09) does it.

## Acceptance

- `./gradlew build`, `ideTest` and `verifyPlugin` pass against the IDE versions in `gradle.properties`, the verifier
  reporting experimental API only in `org.fuin.porelo.intellij.review` and no internal API.
- A diff that is not a review's shows no thread and no gutter control; the test that says so fails when the key is not
  checked.
- After a file switch, *Read Again* and a closed tab no follower of a file's threads is left and no editor leaked; the
  test that says so fails when the scope is not cancelled.

## To be checked

- A range selected in the gutter reaches `requestNewComment(LineRange)` with the lines the person marked: the method
  is built and verified, but no test drags across lines yet.
- Whether the platform's JUnit 5 test framework runs in `ideTest` with the build's JUnit and AssertJ versions, or
  pins its own.
