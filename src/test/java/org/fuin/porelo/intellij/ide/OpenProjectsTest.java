package org.fuin.porelo.intellij.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Finds the open projects whose git roots name a gate's upstream as a remote. */
class OpenProjectsTest {

    private static final String UPSTREAM = "git@github.com:porelo-ai/porelo.git";

    private static OpenProjects.Root root(String project, String remote, String url) {
        return new OpenProjects.Root(project, Path.of("/w", project), Map.of(remote, url));
    }

    @Test
    @DisplayName(
            "A remote names the upstream whether it is written scp-like, as ssh:// or as https://, with or without .git or a trailing slash, and with the host in any case")
    void everyFormOfTheSameAddressMatches() {
        final List<String> forms =
                List.of(
                        "git@github.com:porelo-ai/porelo.git",
                        "git@github.com:porelo-ai/porelo",
                        "ssh://git@github.com/porelo-ai/porelo.git",
                        "ssh://git@GitHub.com:22/porelo-ai/porelo/",
                        "https://github.com/porelo-ai/porelo",
                        "https://github.com/porelo-ai/porelo.git/");

        for (final String form : forms) {
            assertThat(OpenProjects.holding(UPSTREAM, List.of(root("p", "origin", form))))
                    .extracting(OpenProjects.Match::remote)
                    .as("a remote written as " + form)
                    .containsExactly("origin");
        }
    }

    @Test
    @DisplayName(
            "Another repository, another owner or another host is no match, nor is a path that only ends the same")
    void anotherAddressDoesNotMatch() {
        final List<String> others =
                List.of(
                        "git@github.com:porelo-ai/porelo-intellij.git",
                        "git@github.com:someone/porelo.git",
                        "git@gitlab.com:porelo-ai/porelo.git",
                        "git@github.com:x/porelo-ai/porelo.git",
                        "https://github.com/porelo-ai/porelo/tree");

        for (final String other : others) {
            assertThat(OpenProjects.holding(UPSTREAM, List.of(root("p", "origin", other))))
                    .as("a remote written as " + other)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName(
            "Work that stays on the machine, with no upstream, is offered no project, even one with a remote")
    void noUpstreamMatchesNothing() {
        assertThat(OpenProjects.holding("", List.of(root("p", "origin", UPSTREAM))))
                .as("an upstream of ''")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Every open project that holds the upstream is offered, each with the remote that names it")
    void everyProjectThatHoldsItIsOffered() {
        final List<OpenProjects.Match> matches =
                OpenProjects.holding(
                        UPSTREAM,
                        List.of(
                                root("one", "origin", "https://github.com/porelo-ai/porelo"),
                                root("other", "origin", "git@github.com:porelo-ai/other.git"),
                                new OpenProjects.Root(
                                        "two",
                                        Path.of("/w/two"),
                                        Map.of(
                                                "fork",
                                                "git@github.com:me/porelo.git",
                                                "upstream",
                                                UPSTREAM))));

        assertThat(matches)
                .extracting(match -> match.project() + ":" + match.remote())
                .as("the projects offered and their remotes")
                .containsExactly("one:origin", "two:upstream");
    }

    @Test
    @DisplayName("A git root's remotes are read from the lines git config prints for them")
    void readsTheRemotesGitConfigPrints() {
        assertThat(
                        OpenProjects.remotes(
                                "remote.origin.url git@github.com:porelo-ai/porelo.git\n"
                                        + "remote.my.fork.url https://github.com/me/porelo\n"))
                .as("each remote's name and address")
                .containsExactly(
                        Map.entry("origin", "git@github.com:porelo-ai/porelo.git"),
                        Map.entry("my.fork", "https://github.com/me/porelo"));
    }
}
