package dev.claudev.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable proof of "this specific process is the one I started." Every kill/signal action must
 * verify against this before acting — {@code pid} alone is never sufficient, since Windows
 * recycles PIDs quickly under load (docs/PROCESS_SAFETY.md).
 *
 * <p>Per the D5 spawn/record ordering invariant, the row backing this record must be written to
 * persistence synchronously before the spawn call reports success to any caller, so "process
 * running but record not yet durable" is impossible by construction.
 */
public record LaunchRecord(
        long pid,
        String exePath,
        String exeFingerprintSha256,
        Instant processCreationTime,
        UUID instanceToken,
        long jobHandleId,
        String workingDir,
        String commandHash
) {}
