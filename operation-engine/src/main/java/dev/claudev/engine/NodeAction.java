package dev.claudev.engine;

/**
 * The actual work behind one {@link OperationNode}. Throwing marks the node {@code FAILED}
 * (its transitive dependents are then {@code SKIPPED}, never attempted); returning normally marks
 * it {@code SUCCEEDED}. An action that supports cancellation should check
 * {@link NodeExecutionContext#isCancelled()} and return early — throwing or returning from a
 * cancelled context is still treated as whatever it reports, the scheduler does not second-guess it.
 */
@FunctionalInterface
public interface NodeAction {

    void run(NodeExecutionContext context) throws Exception;
}
