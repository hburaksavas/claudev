package dev.claudev.adapter.rabbitmq.detect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqErlangCompatibilityTest {

    private final RabbitMqErlangCompatibility compatibility = new RabbitMqErlangCompatibility();

    @Test
    void thePinnedKnownGoodPairIsCompatible() {
        assertThat(compatibility.isCompatible("4.3.5", "27.3.4.17")).isTrue();
    }

    @Test
    void erlangMajor26IsAcceptedForRabbitMq4() {
        assertThat(compatibility.isCompatible("4.1.0", "26.2.5")).isTrue();
    }

    @Test
    void theProvenBrokenPairIsRejectedEvenThoughItsInTheWindow() {
        assertThat(compatibility.isCompatible("4.3.5", "29.0.6")).isFalse();
    }

    @Test
    void anUncoveredRabbitMqMajorIsNotOffered() {
        assertThat(compatibility.isCompatible("3.12.0", "25.3.2")).isFalse();
    }

    @Test
    void anUnparsableVersionIsNotOffered() {
        assertThat(compatibility.isCompatible("", "27.3.4.17")).isFalse();
        assertThat(compatibility.isCompatible("4.3.5", "")).isFalse();
    }
}
