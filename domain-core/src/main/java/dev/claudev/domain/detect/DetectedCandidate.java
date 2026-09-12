package dev.claudev.domain.detect;

import java.nio.file.Path;

/**
 * One auto-detected, already-validated-compatible RabbitMQ+Erlang install found on the local
 * machine — crosses the UI/backend port boundary in place of the user typing paths by hand.
 * {@code compatible} is always {@code true} today (an incompatible pairing is never surfaced
 * as a candidate at all); the field is kept so a future "unverified but shown" tier doesn't
 * require reshaping this type.
 */
public record DetectedCandidate(
        String label,
        Path erlangHome,
        Path rabbitmqSbin,
        String rabbitMqVersion,
        String erlangVersion,
        String source,
        boolean compatible) {
}
