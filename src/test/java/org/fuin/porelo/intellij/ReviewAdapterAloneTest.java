package org.fuin.porelo.intellij;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The platform's code-review API is experimental and may change with any IDE: one package over it
 * is what is replaced then, which a use anywhere else would make a search through the plugin.
 */
class ReviewAdapterAloneTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static final Path REVIEW =
            MAIN.resolve(Path.of("org", "fuin", "porelo", "intellij", "review"));

    /** The experimental code-review API, Kotlin's coroutines and Kotlin's own types. */
    private static final Pattern ADAPTED =
            Pattern.compile(
                    "^import\\s+(static\\s+)?(com\\.intellij\\.collaboration|kotlinx?)\\.",
                    Pattern.MULTILINE);

    @Test
    @DisplayName(
            "Only the review package uses the platform's code-review API or Kotlin's types and coroutines")
    void onlyTheReviewPackageUsesTheAdaptedApi() throws IOException {
        final List<Path> using = using();

        // A guard that finds no use passes forever: assert that it finds the adapter's, then where.
        assertThat(using)
                .as("the main files that use the code-review API")
                .contains(REVIEW.resolve("ReviewDiffExtension.java"));
        assertThat(using)
                .filteredOn(path -> !path.getParent().equals(REVIEW))
                .as("files outside the review package that use it")
                .isEmpty();
    }

    private static List<Path> using() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> ADAPTED.matcher(read(path)).find())
                    .sorted()
                    .toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + file, ex);
        }
    }
}
