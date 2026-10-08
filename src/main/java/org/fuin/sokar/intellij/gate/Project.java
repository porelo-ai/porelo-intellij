package org.fuin.sokar.intellij.gate;

import java.util.List;

/**
 * A project a machine knows, with the repositories its gate is asked about.
 *
 * @param name The project's name, which every gate call takes.
 * @param repositories Its repositories, its own first; empty when the daemon could not read the project's file,
 *     which is not a project with one repository.
 * @param pending How many pushes wait in all of them.
 */
public record Project(String name, List<Repository> repositories, int pending) {

    /**
     * Copies the list.
     *
     * @param name The project's name.
     * @param repositories Its repositories.
     * @param pending How many pushes wait.
     */
    public Project {
        repositories = List.copyOf(repositories);
    }

    /**
     * One repository of a project, whose gate has its own mirror and its own pushes.
     *
     * @param name What the gate calls take as {@code repository}.
     * @param own Whether it is the project's own repository.
     * @param upstream Where approved work goes, or {@code ""} when it stays on the machine.
     * @param pending How many pushes wait in its mirror.
     */
    public record Repository(String name, boolean own, String upstream, int pending) {
    }

}
