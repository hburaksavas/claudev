package dev.claudev.provider.runtime;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries the same identity fields the adapter was given at start, so it can refuse to signal a
 * process whose creation-time/fingerprint no longer match (PID reuse guard).
 */
public record StopInstanceCommand(
        String instanceId,
        long pid,
        Instant expectedProcessCreationTime,
        UUID expectedInstanceToken,
        boolean graceful
) {}
