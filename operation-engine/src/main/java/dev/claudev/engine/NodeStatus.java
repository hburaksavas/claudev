package dev.claudev.engine;

/**
 * Runtime status of one {@link OperationNode} during a {@link DagScheduler} run. Deliberately not
 * persisted as its own column anywhere — per docs/MILESTONES.md's WP4 acceptance criterion, the
 * persisted {@code OperationEvent} stream alone must be enough to reconstruct what happened, so
 * this enum only needs to exist in memory while a run is in progress.
 */
public enum NodeStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** Never attempted because a dependency failed. */
    SKIPPED,
    /** Never attempted because the operation was cancelled before this node became eligible. */
    CANCELLED
}
