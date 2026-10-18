# IJ08 — Reformatted to the shared style

**Status:** Review; pushed in `8475ca8`, its Build green (run 39080719988).
**Size:** S - one build script, the versions file and the shared `.editorconfig`, then one mechanical reformatting
of 44 Java files (4,718 lines, 439 of them over 100 columns), checked to change whitespace and line breaks only.

**What must be true.** The plugin's Java code follows the shared style, Google Java Style in its AOSP variant, and
the build refuses a file that does not, as every Maven repository's `validate` does; so a change never carries
formatting noise, and `git blame` skips the one commit that reformatted everything.

## Plan

- **Today:** no `.editorconfig`, no formatter and no check in the build; the Java code indents by four and wraps at
  120 columns because every change copied what it saw.
- **The build:** Spotless's Gradle plugin (`com.diffplug.spotless`) in `gradle/libs.versions.toml` beside the other
  plugins, with `google-java-format` at the version `porelo-parent` pins, and in `build.gradle.kts`
  `spotless { java { googleJavaFormat(<version>).aosp() } }`, with the same options the parent sets. Spotless
  makes `check`, and so `build` and the Build's workflow, run `spotlessCheck`; `spotlessApply` fixes a file. The
  Kotlin build scripts are not formatted, as the parent formats only Java.
- **The `.editorconfig`** taken byte for byte from `porelo-buildtools`, and `checkShared` compares it, as the
  tool does in the other repositories. `.gitignore` excepts it and `.git-blame-ignore-revs` from its `.*`.
- **The other files, 4 spaces and no tabs:** the seven YAML files at 2 today (the three workflows, the
  `pinned-jdk` action, `dependabot.yml`, `changelog/logchange-config.yml` and `mkdocs.yml`) are indented by 4 in
  the reformatting, each checked to load to the same data. The Kotlin build scripts and `plugin.xml` are at 4
  already. `gradlew` and `gradlew.bat` stay exactly as the Gradle Wrapper generates them, so that a wrapper update
  stays a plain replacement. There is no shell script here, so neither `shfmt` nor `shellcheck` has anything to read.
- **The reformatting**, a commit of its own with nothing else: `./gradlew spotlessApply` once, with
  `google-java-format`'s defaults, so it also rewraps Javadoc and sorts imports. Every file is checked to hold the
  same words as before, with whitespace removed and imports compared as a set. The commit's hash goes into
  `.git-blame-ignore-revs` in the commit after it, since a commit cannot name its own hash.
- **Red first:** a Java file planted with a wrong indent fails `./gradlew build`, naming the file, before the
  reformatting is applied and after; `spotlessApply` makes it pass.

## Acceptance

- The planted file seen to fail `build` and `spotlessCheck`, naming the file, and pass after `spotlessApply`.
- The reformatting commit changes only whitespace, line breaks (Javadoc rewrapped too) and the order of imports,
  measured per file, and is listed in `.git-blame-ignore-revs`.
- Every YAML file the reformatting touches loads to the same data as before.
- A copy of `.editorconfig` that differs from the shared one fails `checkShared`.
- `./gradlew build`, `./gradlew verifyPlugin` and `./gradlew documents` green on the VM on both commits.

