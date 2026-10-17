package org.fuin.porelo.intellij;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * NullAway checks only what is {@code @NullMarked}, so a package without the mark compiles unchecked and silently.
 */
class NullMarkedPackagesTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    @Test
    @DisplayName("Every package with main code is @NullMarked in its package-info.java")
    void everyMainPackageIsNullMarked() throws IOException {
        final List<Path> packages = packages();

        // A guard over no packages passes forever: assert that it reaches them, then what it checks.
        assertThat(packages).extracting(path -> MAIN.relativize(path).toString())
                .as("the main packages the guard reads").contains("org/fuin/porelo/intellij/machine");
        assertThat(packages).filteredOn(dir -> !nullMarked(dir.resolve("package-info.java")))
                .as("packages with main code whose package-info.java is missing or not @NullMarked").isEmpty();
    }

    private static List<Path> packages() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                    .map(Path::getParent).distinct().sorted().toList();
        }
    }

    private static boolean nullMarked(Path packageInfo) {
        if (!Files.isRegularFile(packageInfo)) {
            return false;
        }
        try {
            return Files.readString(packageInfo, StandardCharsets.UTF_8).contains("@NullMarked");
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + packageInfo, ex);
        }
    }

}
