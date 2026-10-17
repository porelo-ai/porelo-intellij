package org.fuin.porelo.intellij.machine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Stands in for {@code wsl.exe} in a separate process. {@code -l -v} prints the distributions of a file, in UTF-16LE
 * as {@code wsl.exe} does; {@code -d <distribution> --exec porelo daemon connect} joins standard input and output to a
 * daemon's socket as that command does, or says what {@code wsl.exe} says of a distribution it does not have.
 */
public final class FakeWsl {

    private FakeWsl() {
    }

    /**
     * Runs it.
     *
     * @param args {@code <file the distributions are read from> <the daemon's socket> <file the arguments go to>}
     *     followed by the arguments {@code wsl.exe} is given.
     * @throws IOException If a file or the socket cannot be used.
     */
    public static void main(String[] args) throws IOException {
        final List<String> given = List.of(args).subList(3, args.length);
        Files.write(Path.of(args[2]), given, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        if (given.equals(List.of("-l", "-v"))) {
            final String table = "  NAME      STATE           VERSION\r\n" + Files.readString(Path.of(args[0]));
            System.out.write(table.getBytes(StandardCharsets.UTF_16LE));
            System.out.flush();
            return;
        }
        final String distribution = given.get(given.indexOf("-d") + 1);
        if (!Files.readString(Path.of(args[0])).contains(" " + distribution + " ")) {
            System.err.println("There is no distribution with the supplied name.");
            System.exit(1);
        }
        try (SocketChannel daemon = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            try {
                daemon.connect(UnixDomainSocketAddress.of(args[1]));
            } catch (IOException ex) {
                // As 'porelo daemon connect' says it: on standard error, never on the stream a client decodes.
                System.err.println("porelo: no daemon answers at " + args[1]);
                System.exit(69);
            }
            final Thread in = Thread.ofPlatform().start(() -> copy(System.in, Channels.newOutputStream(daemon)));
            copy(Channels.newInputStream(daemon), System.out);
            in.interrupt();
        }
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

}
