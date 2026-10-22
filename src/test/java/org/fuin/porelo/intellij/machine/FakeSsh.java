package org.fuin.porelo.intellij.machine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for {@code ssh} in a separate process: it writes the arguments it was given, then does
 * what its first argument says - binds the local end of {@code -L} as ssh would, joins standard
 * input and output to the socket a remote {@code porelo daemon connect --socket <path>} names, as a
 * remote shell would read that line, says what ssh says when a host key is unknown and ends with
 * 255, or does nothing at all.
 */
public final class FakeSsh {

    private FakeSsh() {}

    /**
     * Runs it.
     *
     * @param args {@code <forward|connect|refuse|hang> <file the arguments go to>} followed by the
     *     arguments ssh is given; {@code connect} adds them to the file, each run one after the
     *     other.
     * @throws IOException If the socket cannot be bound.
     * @throws InterruptedException Never expected; the process is destroyed.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        final List<String> given = List.of(args).subList(2, args.length);
        if (args[0].equals("connect")) {
            Files.write(
                    Path.of(args[1]),
                    given,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } else {
            Files.write(Path.of(args[1]), given, StandardCharsets.UTF_8);
        }
        switch (args[0]) {
            case "forward" -> forward(given);
            case "connect" -> connect(given.getLast());
            case "refuse" -> {
                System.err.println("Host key verification failed.");
                System.exit(255);
            }
            default -> Thread.sleep(Long.MAX_VALUE);
        }
    }

    private static void connect(String remoteCommand) throws IOException {
        final List<String> words = words(remoteCommand);
        if (!words.subList(0, 4).equals(List.of("porelo", "daemon", "connect", "--socket"))
                || words.size() != 5) {
            System.err.println("bash: not what porelo daemon connect is run as: " + remoteCommand);
            System.exit(127);
        }
        try (SocketChannel daemon = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            try {
                daemon.connect(UnixDomainSocketAddress.of(words.get(4)));
            } catch (IOException ex) {
                System.err.println("porelo: no daemon answers at " + words.get(4));
                System.exit(69);
            }
            final Thread in =
                    Thread.ofPlatform()
                            .start(() -> copy(System.in, Channels.newOutputStream(daemon)));
            copy(Channels.newInputStream(daemon), System.out);
            in.interrupt();
        }
    }

    // The words a POSIX shell reads from a line of plain words and single-quoted ones.
    private static List<String> words(String line) {
        final List<String> words = new ArrayList<>();
        final StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        boolean inWord = false;
        for (final char c : line.toCharArray()) {
            if (escaped) {
                word.append(c);
                escaped = false;
            } else if (c == '\'') {
                quoted = !quoted;
                inWord = true;
            } else if (c == '\\' && !quoted) {
                escaped = true;
                inWord = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (inWord) {
            words.add(word.toString());
        }
        return words;
    }

    private static void copy(InputStream from, OutputStream to) {
        try {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = from.read(buffer)) >= 0) {
                to.write(buffer, 0, read);
                to.flush();
            }
        } catch (IOException ex) {
            // One side ended; the other ends with it.
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
