package org.fuin.porelo.intellij.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How the watch's own waits follow the factors its machines' daemons answered. */
class PoreloWatchTest {

    @Test
    @DisplayName("Without a factor read, every machine is read once a minute")
    void readsOnceAMinuteWithoutAFactor() {
        assertThat(PoreloWatch.every(List.of()))
                .as("the delay between reads")
                .isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("Every machine is read as seldom as the largest polling factor asks")
    void readsByTheLargestPollingFactor() {
        assertThat(
                        PoreloWatch.every(
                                List.of(
                                        LimitFactors.parse("polling=2.0"),
                                        LimitFactors.parse("polling=0.5"))))
                .as("the delay between reads")
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("A stream that ended is opened again after ten seconds times the retries factor")
    void reopensAStreamByTheRetriesFactor() {
        assertThat(PoreloWatch.reopen(LimitFactors.parse("retries=3.0")))
                .as("the pause before the stream is opened again")
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "Only the factors of machines still listed count, so a machine taken out no longer slows the others")
    void countsOnlyTheMachinesStillListed() {
        assertThat(
                        PoreloWatch.ofListed(
                                java.util.Map.of(
                                        "kept", LimitFactors.parse("polling=2.0"),
                                        "gone", LimitFactors.parse("polling=100")),
                                java.util.Set.of("kept")))
                .as("the factors that count")
                .containsExactly(LimitFactors.parse("polling=2.0"));
    }

    @Test
    @DisplayName("A limit that serves no one machine takes, of each factor, the largest answered")
    void takesTheLargestOfEachFactor() {
        assertThat(
                        PoreloWatch.widest(
                                List.of(
                                        LimitFactors.parse("services=2.0,polling=0.5"),
                                        LimitFactors.parse("machines=3.0,retries=0.25"))))
                .as("the largest of each")
                .isEqualTo(LimitFactors.parse("services=2.0,machines=3.0,polling=1.0,retries=1.0"));
    }
}
