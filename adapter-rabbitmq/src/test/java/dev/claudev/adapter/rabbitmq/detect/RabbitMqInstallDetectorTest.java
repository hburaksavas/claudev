package dev.claudev.adapter.rabbitmq.detect;

import dev.claudev.domain.detect.DetectedCandidate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RabbitMqInstallDetectorTest {

    private final RabbitMqDetector rabbitMqDetector = mock(RabbitMqDetector.class);
    private final ErlangDetector erlangDetector = mock(ErlangDetector.class);
    private final RabbitMqInstallDetector detector =
            new RabbitMqInstallDetector(rabbitMqDetector, erlangDetector, new RabbitMqErlangCompatibility());

    @Test
    void pairsCompatibleCandidatesOnly() {
        Path rabbitSbin = Path.of("C:\\RabbitMQ\\sbin");
        Path erlangHome27 = Path.of("C:\\erl-27.3.4.17");
        Path erlangHome29 = Path.of("C:\\erl-29.0.6");

        when(rabbitMqDetector.detect()).thenReturn(List.of(
                new RabbitMqCandidate(rabbitSbin, "4.3.5", DetectionSource.REGISTRY)));
        when(erlangDetector.detect()).thenReturn(List.of(
                new ErlangCandidate(erlangHome27, "27.3.4.17", DetectionSource.REGISTRY),
                new ErlangCandidate(erlangHome29, "29.0.6", DetectionSource.GLOB)));

        List<DetectedCandidate> results = detector.detectValidCandidates();

        assertThat(results).hasSize(1);
        DetectedCandidate only = results.get(0);
        assertThat(only.erlangHome()).isEqualTo(erlangHome27);
        assertThat(only.rabbitmqSbin()).isEqualTo(rabbitSbin);
        assertThat(only.compatible()).isTrue();
        assertThat(only.label()).contains("4.3.5").contains("27.3.4.17");
    }

    @Test
    void dropsARabbitMqCandidateEntirelyWhenNoErlangCandidateIsCompatible() {
        when(rabbitMqDetector.detect()).thenReturn(List.of(
                new RabbitMqCandidate(Path.of("C:\\RabbitMQ\\sbin"), "3.12.0", DetectionSource.REGISTRY)));
        when(erlangDetector.detect()).thenReturn(List.of(
                new ErlangCandidate(Path.of("C:\\erl-27.3.4.17"), "27.3.4.17", DetectionSource.REGISTRY)));

        assertThat(detector.detectValidCandidates()).isEmpty();
    }

    @Test
    void returnsEmptyWhenNothingDetected() {
        when(rabbitMqDetector.detect()).thenReturn(List.of());
        when(erlangDetector.detect()).thenReturn(List.of());

        assertThat(detector.detectValidCandidates()).isEmpty();
    }
}
