package org.fuin.sokar.intellij.machine;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Stands in for {@code ssh} in a separate process: it writes the arguments it was given, then does what its first
 * argument says - binds the local end of {@code -L} as ssh would, says what ssh says when a host key is unknown and
 * ends with 255, or does nothing at all.
 */
public final class FakeSsh {

    private FakeSsh() {
    }

    /**
     * Runs it.
     *
     * @param args {@code <forward|refuse|hang> <file the arguments go to>} followed by the arguments ssh is given.
     * @throws IOException If the socket cannot be bound.
     * @throws InterruptedException Never expected; the process is destroyed.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        final List<String> given = List.of(args).subList(2, args.length);
        Files.write(Path.of(args[1]), given, StandardCharsets.UTF_8);
        switch (args[0]) {
            case "forward" -> forward(given);
            case "refuse" -> {
                System.err.println("Host key verification failed.");
                System.exit(255);
            }
            default -> Thread.sleep(Long.MAX_VALUE);
        }
    }

    private static void forward(List<String> given) throws IOException {
        final String local = given.get(given.indexOf("-L") + 1).split(":", 2)[0];
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(local));
            while (true) {
                try (SocketChannel accepted = server.accept()) {
                    // Each probe of the socket only needs to have been answered.
                }
            }
        }
    }

}
