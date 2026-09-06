package dev.claudev.domain;

import java.time.Instant;

/**
 * The single representation forwarded live to the UI and appended to the audit trail — never two
 * hand-maintained copies of "what happened."
 */
public record OperationEvent(
        OperationId operationId,
        String nodeId,
        Kind kind,
        String message,
        Instant timestamp
) {
    public enum Kind {
        STARTED,
        STEP_PROGRESS,
        LOG_LINE,
        WARNING,
        FAILED,
        COMPLETED
    }
}
