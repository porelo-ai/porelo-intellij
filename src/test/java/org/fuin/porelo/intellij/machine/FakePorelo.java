package org.fuin.porelo.intellij.machine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Stands in for {@code porelo daemon connect --socket <path>} and the daemon behind it, at the far
 * end of a real ssh: it answers the Varlink calls it reads on standard input on standard output,
 * each reply ended by a NUL byte, as the daemon's are. It needs nothing but the JDK, so it runs as
 * a source file where the plugin's classes are not. With {@code PORELO_FAKE_DROP_NUL=1} it drops
 * every NUL byte it sends, as a hop that treats the stream as text would, so a test can be seen to
 * fail on it.
 */
public final class FakePorelo {

    /** The one project the stand-in daemon has. */
    public static final String PROJECT = "stand-in";

    private static final String INFO =
            "{\"parameters\":{\"vendor\":\"stand-in\",\"product\":\"porelo\",\"version\":\"0\","
                    + "\"url\":\"\",\"interfaces\":[\"org.varlink.service\","
                    + "\"org.fuin.porelo.Tasks1\"]}}";

    private static final String PROJECTS =
            "{\"parameters\":{\"projects\":[{\"name\":\""
                    + PROJECT
                    + "\",\"securityClass\":\"guarded\","
                    + "\"file\":\"/home/a/.local/state/porelo/stand-in/project.yml\",\"mirror\":\"\","
                    + "\"pending\":0,\"tasks\":0,\"running\":0,\"prepared\":true,"
                    + "\"preparedState\":\"READY\",\"repositories\":[]}]}}";

    private FakePorelo() {}

    /**
     * Runs it.
     *
     * @param args {@code daemon connect --socket <path>}.
     * @throws IOException If standard output cannot be written.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 4
                || !List.of(args).subList(0, 3).equals(List.of("daemon", "connect", "--socket"))) {
            System.err.println("porelo: not what the stand-in answers: " + List.of(args));
            System.exit(64);
        }
        final boolean dropNul = "1".equals(System.getenv("PORELO_FAKE_DROP_NUL"));
        final InputStream in = System.in;
        final OutputStream out = System.out;
        String call;
        while ((call = next(in)) != null) {
            final String reply =
                    call.contains("\"org.varlink.service.GetInfo\"")
                            ? INFO
                            : call.contains("\"org.fuin.porelo.Tasks1.Projects\"")
                                    ? PROJECTS
                                    : "{\"error\":\"org.varlink.service.MethodNotFound\","
                                            + "\"parameters\":{}}";
            out.write(reply.getBytes(StandardCharsets.UTF_8));
            if (!dropNul) {
                out.write(0);
            }
            out.flush();
        }
    }

    // One call, up to its NUL byte; null once the input ends.
    private static String next(InputStream in) throws IOException {
        final ByteArrayOutputStream call = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) > 0) {
            call.write(read);
        }
        return read < 0 && call.size() == 0 ? null : call.toString(StandardCharsets.UTF_8);
    }
}
