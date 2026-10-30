package org.fuin.porelo.intellij.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Writes an ssh configuration shaped as the interface writes one from a host card: the host's key
 * pinned in a known hosts file of its own, and nothing accepted that is not in it.
 */
public final class PinnedConfig {

    /** The public half of a key no host holds, standing for the key a host card names. */
    private static final String OTHER_HOSTS_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIB+CJGYXTsSlbGVePFbhb4N2Cznt7S3PDL5L88YjmQtJ";

    private PinnedConfig() {}

    /**
     * Writes a configuration whose card names another key than the one the host answers with.
     *
     * @param dir Where its files go.
     * @param reaching The ssh configuration that otherwise reaches the host, or {@code null}.
     * @return The configuration.
     * @throws IOException If a file cannot be written.
     */
    public static Path ofAnotherHost(Path dir, @Nullable Path reaching) throws IOException {
        final Path known = dir.resolve("other.known_hosts");
        Files.writeString(known, "* " + OTHER_HOSTS_KEY + "\n", StandardCharsets.UTF_8);
        return write(dir.resolve("other.conf"), known, reaching);
    }

    /**
     * Writes a configuration whose card names the keys a known hosts file holds.
     *
     * @param dir Where its files go.
     * @param known The known hosts file that holds the host's own key.
     * @param reaching The ssh configuration that otherwise reaches the host, or {@code null}.
     * @return The configuration.
     * @throws IOException If a file cannot be written.
     */
    public static Path ofTheHost(Path dir, Path known, @Nullable Path reaching) throws IOException {
        final Path copy = dir.resolve("host.known_hosts");
        Files.copy(known, copy);
        return write(dir.resolve("host.conf"), copy, reaching);
    }

    private static Path write(Path config, Path known, @Nullable Path reaching) throws IOException {
        // ssh takes the first value it reads of each option, so these hold over the included
        // configuration's own.
        final StringBuilder text =
                new StringBuilder()
                        .append("Host *\n")
                        .append("  UserKnownHostsFile \"")
                        .append(slashed(known))
                        .append("\"\n")
                        .append("  GlobalKnownHostsFile none\n")
                        .append("  StrictHostKeyChecking yes\n");
        // Its text, not an Include, which ssh refuses for a file others may write, as -F does not.
        if (reaching != null) {
            text.append(Files.readString(reaching, StandardCharsets.UTF_8)).append('\n');
        }
        Files.writeString(config, text, StandardCharsets.UTF_8);
        return config;
    }

    private static String slashed(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
