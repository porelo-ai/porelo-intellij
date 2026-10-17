package org.fuin.porelo.intellij;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The repository was renamed by a script; a word it left behind, or one written later out of habit, names something
 * that is no longer there. The old name is spelled here in two pieces, so that this file does not say it.
 */
@Tag("documents")
class NoOldNameTest {

    private static final String OLD = "so" + "kar";

    /**
     * Paths that keep the old name, name and text: the rename's issue, the changelog entry that announces it, and once
     * it is released, every released version's entries and summary and the changelog generated from them.
     */
    private static final Pattern KEPT_PATHS = Pattern.compile(
            "issues/IJ07-[^/]+\\.md|changelog/unreleased/renamed-porelo\\.yml|changelog/v[^/]+/.*|CHANGELOG\\.md"); // not-a-citation

    /** Lines that keep it: the rename's row in the issue index, which links to the issue's file by its name. */
    private static final Pattern KEPT_LINES = Pattern.compile(".*<a id=\"ij07\"></a>.*");

    @Test
    @DisplayName("No tracked file says the old name in its path or its text, but the kept ones")
    void noTrackedFileSaysTheOldName() throws IOException, InterruptedException {
        final List<String> files = tracked();
        final List<String> found = new ArrayList<>();
        for (final String file : files) {
            if (KEPT_PATHS.matcher(file).matches()) {
                continue;
            }
            if (file.toLowerCase(Locale.ROOT).contains(OLD)) {
                found.add(file + ": its path");
            }
            final List<String> lines = lines(Path.of(file));
            for (int i = 0; i < lines.size(); i++) {
                if (!KEPT_LINES.matcher(lines.get(i)).matches()
                        && lines.get(i).toLowerCase(Locale.ROOT).contains(OLD)) {
                    found.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }

        // A guard that reads nothing passes forever: assert what it read, then what it checks.
        assertThat(files).as("the tracked files the guard reads")
                .contains("build.gradle.kts", "AGENTS.md", "issues/README.md", "src/main/resources/META-INF/plugin.xml");
        assertThat(found).as("tracked files that say the old name").isEmpty();
    }

    private static List<String> tracked() throws IOException, InterruptedException {
        final Process git = new ProcessBuilder("git", "ls-files", "-z").redirectErrorStream(true).start();
        final String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("exit code of git ls-files, which printed: %s", out).isZero();
        return List.of(out.split("\0"));
    }

    private static List<String> lines(final Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (final MalformedInputException ex) {
            // Not text, such as the Gradle Wrapper's jar: nothing in it is read as a word.
            return List.of();
        } catch (final IOException ex) {
            throw new UncheckedIOException("Cannot read " + path, ex);
        }
    }

}
