package dev.claudev.adapter.rabbitmq.detect;

import java.util.List;
import java.util.Optional;

/**
 * Conservative RabbitMQ/Erlang compatibility rule — no published matrix exists, so this codebase
 * only trusts what it has first-hand evidence for: the {@code RabbitMqPinnedPair} known-good pin
 * (RabbitMQ 4.3.5 / Erlang 27.3.4.17) and the known-bad data point from the WP6 spike (OTP-29.0.6
 * fails RabbitMQ 4.3.5 with {@code horus/extraction_denied}).
 *
 * <p>Anything outside the allow-list below is treated as "not offered" rather than "offered but
 * flagged unverified" — under-detecting a valid-but-unproven pair is preferable to risking a
 * repeat of a proven failure.
 */
final class RabbitMqErlangCompatibility {

    /** RabbitMQ major version -> inclusive [min, max] Erlang/OTP major version window. */
    private static final List<CompatWindow> ALLOW_LIST = List.of(
            new CompatWindow(4, 26, 27));

    /** Exact known-bad pairs, checked independently of the window so tuning the window can't silently readmit them. */
    private static final List<ExactExclusion> KNOWN_BAD = List.of(
            new ExactExclusion(4, 29));

    boolean isCompatible(String rabbitMqVersion, String erlangVersion) {
        Optional<Integer> rabbitMajor = VersionExtraction.majorVersion(rabbitMqVersion);
        Optional<Integer> erlangMajor = VersionExtraction.majorVersion(erlangVersion);
        if (rabbitMajor.isEmpty() || erlangMajor.isEmpty()) {
            return false;
        }

        for (ExactExclusion exclusion : KNOWN_BAD) {
            if (exclusion.rabbitMajor == rabbitMajor.get() && exclusion.erlangMajor == erlangMajor.get()) {
                return false;
            }
        }

        for (CompatWindow window : ALLOW_LIST) {
            if (window.rabbitMajor == rabbitMajor.get()
                    && erlangMajor.get() >= window.minErlangMajor
                    && erlangMajor.get() <= window.maxErlangMajor) {
                return true;
            }
        }
        return false;
    }

    private record CompatWindow(int rabbitMajor, int minErlangMajor, int maxErlangMajor) {
    }

    private record ExactExclusion(int rabbitMajor, int erlangMajor) {
    }
}
