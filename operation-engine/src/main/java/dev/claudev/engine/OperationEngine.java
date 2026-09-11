package dev.claudev.engine;

import dev.claudev.domain.Operation;
import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Cancellable DAG execution: best-effort per independent branch, a failed node skips only its own
 * transitive dependents, terminal status is {@code PARTIALLY_FAILED} with the persisted event
 * stream as the reconstructable summary — never a claimed all-or-nothing rollback over external
 * side effects (D4/ADR-007).
 *
 * <p>{@link #submit} returns as soon as the {@link Operation} is durably recorded — execution runs
 * asynchronously in the background; poll {@link #find} or {@link #subscribe} for progress. This is
 * a deliberate change from an earlier draft of this interface that returned a fully-realized
 * {@code Operation} synchronously, which is incompatible with meaningful cancellation.
 */
public interface OperationEngine {

    OperationId submit(OperationPlan plan);

    /** Cooperative: requests that not-yet-started nodes stop being scheduled; already-running nodes are asked, not forced, to stop. */
    void cancel(OperationId operationId);

    void subscribe(Consumer<OperationEvent> listener);

    Optional<Operation> find(OperationId operationId);
}
