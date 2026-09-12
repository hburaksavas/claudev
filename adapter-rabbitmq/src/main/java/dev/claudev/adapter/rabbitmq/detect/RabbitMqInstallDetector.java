package dev.claudev.adapter.rabbitmq.detect;

import dev.claudev.domain.detect.DetectedCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level auto-detection entry point: cross-joins every discovered RabbitMQ install against
 * every discovered Erlang install, keeps only the compatible pairings ({@link
 * RabbitMqErlangCompatibility}), and maps survivors to {@link DetectedCandidate}s for the UI.
 *
 * <p>A RabbitMQ candidate with no compatible Erlang candidate is dropped entirely — callers only
 * ever see pickable, valid candidates, never an "incompatible" entry (per the auto-detection
 * requirement to "only offer valid pairs").
 */
public final class RabbitMqInstallDetector {

    private final RabbitMqDetector rabbitMqDetector;
    private final ErlangDetector erlangDetector;
    private final RabbitMqErlangCompatibility compatibility;

    public RabbitMqInstallDetector() {
        this(new RabbitMqDetector(), new ErlangDetector(), new RabbitMqErlangCompatibility());
    }

    RabbitMqInstallDetector(RabbitMqDetector rabbitMqDetector, ErlangDetector erlangDetector, RabbitMqErlangCompatibility compatibility) {
        this.rabbitMqDetector = rabbitMqDetector;
        this.erlangDetector = erlangDetector;
        this.compatibility = compatibility;
    }

    public List<DetectedCandidate> detectValidCandidates() {
        List<RabbitMqCandidate> rabbitMqCandidates = rabbitMqDetector.detect();
        List<ErlangCandidate> erlangCandidates = erlangDetector.detect();

        List<DetectedCandidate> results = new ArrayList<>();
        for (RabbitMqCandidate rabbitMq : rabbitMqCandidates) {
            for (ErlangCandidate erlang : erlangCandidates) {
                if (compatibility.isCompatible(rabbitMq.version(), erlang.version())) {
                    results.add(toDetectedCandidate(rabbitMq, erlang));
                }
            }
        }
        return results;
    }

    private DetectedCandidate toDetectedCandidate(RabbitMqCandidate rabbitMq, ErlangCandidate erlang) {
        String label = "RabbitMQ " + rabbitMq.version() + " + Erlang/OTP " + erlang.version()
                + " (" + rabbitMq.source().label() + ")";
        String source = rabbitMq.source() == erlang.source()
                ? rabbitMq.source().label()
                : rabbitMq.source().label() + " + " + erlang.source().label();
        return new DetectedCandidate(
                label, erlang.erlangHome(), rabbitMq.rabbitmqSbin(),
                rabbitMq.version(), erlang.version(), source, true);
    }
}
