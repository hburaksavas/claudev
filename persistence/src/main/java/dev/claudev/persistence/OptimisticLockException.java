package dev.claudev.persistence;

/**
 * Thrown when an {@code update} targets a revision that no longer matches the stored row — either
 * a concurrent writer got there first, or the caller is working from stale data. The row is left
 * exactly as the winning writer left it; this class never triggers a retry or merge on its own.
 */
public final class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(String aggregateType, String id, long expectedRevision) {
        super(aggregateType + " " + id + " was not at expected revision " + expectedRevision
                + " (concurrent write, or stale caller data)");
    }
}
