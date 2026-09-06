package dev.claudev.domain;

/**
 * Observed (never desired) state of an {@link Instance}, re-derived by the reconciler on every
 * pass — a stored value is never trusted at face value (see docs/PROCESS_SAFETY.md).
 */
public enum InstanceState {
    STOPPED,
    STARTING,
    RUNNING,
    DEGRADED,
    STOPPING,
    /** Recorded as owned, but process identity/health no longer verifies. */
    ORPHANED,
    /** No record at all for a slot that should have one (e.g. fresh install, corrupt store). */
    UNKNOWN,
    /**
     * A process resolving under the app's managed-binaries directory is running but has no
     * matching {@link LaunchRecord} — a bookkeeping failure, not a legitimate orphan. Surfaced
     * distinctly per the D5 spawn/record ordering invariant so it reads as a bug signal.
     */
    UNTRACKED
}
