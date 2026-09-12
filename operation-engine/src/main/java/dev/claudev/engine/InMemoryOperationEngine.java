package dev.claudev.engine;

import dev.claudev.domain.Operation;
import dev.claudev.domain.OperationDag;
import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.OperationStatus;
import dev.claudev.persistence.OperationEventRepository;
import dev.claudev.persistence.OperationRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The real {@link OperationEngine}: persists the plan, runs {@link DagScheduler} on a dedicated
 * background thread per submitted operation, persists and publishes every event as it happens
 * (the same event, not two representations — docs/ARCHITECTURE.md), and records the terminal
 * status. Deliberately framework-agnostic; {@code app-bootstrap} wires it as a bean, matching the
 * pattern already used for the adapters and for {@link Reconciler}.
 */
public final class InMemoryOperationEngine implements OperationEngine {

    private final OperationRepository operationRepository;
    private final OperationEventRepository operationEventRepository;
    private final int maxConcurrencyPerOperation;
    /**
     * A real bug found from a user-reported hang, not assumed: {@link Executors#newCachedThreadPool()}
     * uses the default thread factory, which creates non-daemon threads. A cached pool keeps an idle
     * thread alive for 60s after its last task (or indefinitely if a task — e.g. a RabbitMQ node's
     * multi-second boot wait, or a first-ever ~250MB binary download — is still running), so quitting
     * the app while any operation had run recently left the JVM process alive with no visible window,
     * looking exactly like a frozen app that needed a manual kill. Every other background executor in
     * this codebase (see {@code WorkspacesPane}, {@code ConnectionsPane}) already uses daemon threads
     * for this exact reason; this one didn't.
     */
    private final ExecutorService operationDriverPool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "claudev-operation-driver");
        thread.setDaemon(true);
        return thread;
    });

    private final Set<UUID> cancelledOperations = ConcurrentHashMap.newKeySet();
    private final List<Consumer<OperationEvent>> subscribers = new CopyOnWriteArrayList<>();

    public InMemoryOperationEngine(
            OperationRepository operationRepository,
            OperationEventRepository operationEventRepository,
            int maxConcurrencyPerOperation) {
        this.operationRepository = operationRepository;
        this.operationEventRepository = operationEventRepository;
        this.maxConcurrencyPerOperation = maxConcurrencyPerOperation;
    }

    @Override
    public OperationId submit(OperationPlan plan) {
        OperationId operationId = OperationId.newId();
        OperationDag dag = toDag(plan);
        UUID cancelToken = UUID.randomUUID();

        Operation operation = new Operation(
                operationId, plan.kind(), plan.targets(), dag,
                OperationStatus.RUNNING, List.of(), Instant.now(), Optional.empty(), cancelToken);
        operationRepository.insert(operation);

        operationDriverPool.submit(() -> runToCompletion(operationId, plan));

        return operationId;
    }

    private void runToCompletion(OperationId operationId, OperationPlan plan) {
        DagScheduler scheduler = new DagScheduler(maxConcurrencyPerOperation);
        EventSink sink = event -> {
            operationEventRepository.append(event);
            for (Consumer<OperationEvent> subscriber : subscribers) {
                subscriber.accept(event);
            }
        };

        OperationStatus finalStatus;
        try {
            finalStatus = scheduler.run(operationId, plan, sink, () -> cancelledOperations.contains(operationId.value()));
        } finally {
            cancelledOperations.remove(operationId.value());
        }

        operationRepository.updateStatus(operationId, finalStatus, Optional.of(Instant.now()));
    }

    @Override
    public void cancel(OperationId operationId) {
        cancelledOperations.add(operationId.value());
    }

    @Override
    public void subscribe(Consumer<OperationEvent> listener) {
        subscribers.add(listener);
    }

    @Override
    public Optional<Operation> find(OperationId operationId) {
        return operationRepository.findById(operationId).map(stored -> new Operation(
                stored.id(), stored.kind(), stored.targets(), stored.dag(), stored.status(),
                operationEventRepository.findByOperationId(operationId),
                stored.startedAt(), stored.endedAt(), stored.cancelToken()));
    }

    private static OperationDag toDag(OperationPlan plan) {
        List<String> nodeIds = plan.nodes().stream().map(OperationNode::id).toList();
        Map<String, List<String>> dependsOn = plan.nodes().stream()
                .collect(Collectors.toMap(OperationNode::id, OperationNode::dependsOn));
        return new OperationDag(nodeIds, dependsOn);
    }
}
