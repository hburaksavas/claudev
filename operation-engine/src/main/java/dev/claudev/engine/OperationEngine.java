package dev.claudev.engine;

import dev.claudev.domain.Operation;
import dev.claudev.domain.OperationDag;
import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;

import java.util.function.Consumer;

/**
 * Cancellable DAG execution: best-effort per independent branch, a failed node skips only its own
 * dependents, terminal status is {@code PARTIALLY_FAILED} with a structured summary rather than a
 * claimed all-or-nothing rollback over external side effects (D4).
 *
 * <p>Not yet implemented — this interface fixes the contract for M0 (see docs milestone plan);
 * the scheduler, retry/compensation policy, and event-bus wiring are the M0 backlog itself.
 */
public interface OperationEngine {

    Operation submit(String kind, OperationDag dag);

    void cancel(OperationId operationId);

    void subscribe(Consumer<OperationEvent> listener);
}
