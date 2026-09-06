package dev.claudev.provider.runtime;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Value data only — the {@code pid}/{@code processCreationTime}/{@code instanceToken} triple is
 * exactly what a caller needs to build a domain {@code LaunchRecord}, but this type itself carries
 * no live OS handle.
 */
public record StartInstanceOutcome(
        String instanceId,
        long pid,
        String exeFingerprintSha256,
        Instant processCreationTime,
        UUID instanceToken,
        Set<Integer> boundPorts
) {
    public StartInstanceOutcome {
        boundPorts = Set.copyOf(boundPorts);
    }
}
