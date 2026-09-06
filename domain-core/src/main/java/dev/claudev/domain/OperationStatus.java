package dev.claudev.domain;

/**
 * {@code PARTIALLY_FAILED} is the expected terminal state for a workspace-wide operation where an
 * independent DAG branch failed — a failed node skips only its own dependents, and no
 * transactionality is claimed over external side effects like a completed git pull (D4).
 */
public enum OperationStatus {
    PENDING,
    RUNNING,
    PARTIALLY_FAILED,
    FAILED,
    SUCCEEDED,
    CANCELLED
}
