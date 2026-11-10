package org.fuin.porelo.intellij;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reads the jars the plugin's zip bundles: a third-party jar in it reaches every person who
 * installs the plugin without the license and notice texts it must travel with, since the zip
 * carries none.
 */
class BundledJarsTest {

    @Test
    @DisplayName(
            "the plugin's zip bundles the plugin's own jar and porelo-wire, and no third-party jar")
    void theZipBundlesOnlyPorelosOwnJars() throws IOException {
        final String version = System.getProperty("porelo.version");
        final Path zip = Path.of(System.getProperty("porelo.zip"));

        final List<String> jars = jarsIn(zip);

        assertThat(jars)
                .as("the jars in the zip's lib/, each of which ships to whoever installs it")
                .containsExactlyInAnyOrder(
                        "porelo-intellij-" + version + ".jar",
                        "porelo-wire-" + catalogVersion("porelo-wire") + ".jar");
    }

    private static List<String> jarsIn(Path zip) throws IOException {
        final List<String> jars = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : Collections.list(file.entries())) {
                final String name = entry.getName();
                if (name.endsWith(".jar")) {
                    assertThat(name)
                            .as("a jar of the zip, which the IDE loads from the plugin's lib/")
                            .startsWith("porelo-intellij/lib/");
                    jars.add(name.substring("porelo-intellij/lib/".length()));
                }
            }
        }
        return jars;
    }

    private static String catalogVersion(String name) throws IOException {
        final Matcher version =
                Pattern.compile("(?m)^" + Pattern.quote(name) + " = \"([^\"]+)\"$")
                        .matcher(Files.readString(Path.of("gradle/libs.versions.toml")));
        assertThat(version.find())
                .as("gradle/libs.versions.toml names a version for " + name)
                .isTrue();
        return version.group(1);
    }
}
