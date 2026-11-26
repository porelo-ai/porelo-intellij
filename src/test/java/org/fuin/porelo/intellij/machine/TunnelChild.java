package org.fuin.porelo.intellij.machine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The IDE's stand-in for a test that kills it: raises a tunnel, says so on standard output and then
 * lives until it is killed.
 */
public final class TunnelChild {

    private TunnelChild() {}

    /**
     * Runs it.
     *
     * @param args The runtime directory, then the ssh command.
     * @throws InterruptedException Never expected; the process is killed.
     */
    public static void main(String[] args) throws InterruptedException {
        final Machine machine =
                new Machine(
                        "build 1",
                        Path.of("/run/user/1000/porelo-tunnel-build-1.sock"),
                        "build1",
                        "/run/user/1001/porelo/porelod.sock",
                        Path.of("/home/dev/.config/porelo/ssh/build1.conf"));
        final Tunnel tunnel =
                Tunnel.raise(
                        machine,
                        Path.of(args[0]),
                        List.of(args).subList(1, args.length),
                        Duration.ofSeconds(10));
        System.out.println("up " + tunnel.socket());
        Thread.sleep(Long.MAX_VALUE);
    }
}
