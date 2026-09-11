package dev.claudev.engine;

/**
 * What a {@link NodeAction} gets to interact with the scheduler through. Cancellation is
 * cooperative: a long-running action is expected to check {@link #isCancelled()} periodically (or
 * between sub-steps) and stop early — the scheduler cannot forcibly interrupt arbitrary work, only
 * ask for it to stop and record whatever outcome the action reports.
 */
public interface NodeExecutionContext {

    String nodeId();

    boolean isCancelled();

    /** Emits a {@code STEP_PROGRESS} event for this node — for anything more specific, emit via {@link #log}. */
    void progress(String message);

    /** Emits a {@code LOG_LINE} event for this node. */
    void log(String message);
}
