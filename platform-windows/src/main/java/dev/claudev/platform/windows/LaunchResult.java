package dev.claudev.platform.windows;

import java.time.Instant;

/**
 * What a caller needs to build a durable {@code LaunchRecord} (domain-core) immediately, before
 * doing anything else — per the D5 ordering invariant, this record must be persisted synchronously
 * before the spawn call is reported as successful to any other caller.
 *
 * <p>{@code job} stays open on return; the caller owns its lifecycle (holds it for the life of the
 * managed component, closes it to release this process's JVM-side handle — which does not affect
 * the OS-level job as long as no handle to it remains, and closing the app's own last handle to it
 * is exactly the {@code KILL_ON_JOB_CLOSE} crash-safety trigger).
 */
public record LaunchResult(
        int pid,
        Instant creationTime,
        String imagePath,
        String fingerprintSha256,
        WindowsJobObject job
) {
}
