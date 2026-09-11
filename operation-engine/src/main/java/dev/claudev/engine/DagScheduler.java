package dev.claudev.engine;

import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.OperationStatus;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * Executes an {@link OperationPlan} as a dependency DAG with bounded concurrency: a failed node's
 * transitive dependents are {@code SKIPPED}, never attempted; unrelated branches run to completion
 * regardless (best-effort semantics — D4/ADR-007). A plain, synchronous, blocking method;
 * {@link InMemoryOperationEngine} is what runs it on a background thread per submitted operation.
 *
 * <p>Package-private by design — {@code OperationEngine}, {@code OperationPlan}, {@code
 * OperationNode}, and {@code NodeAction}/{@code NodeExecutionContext} are this module's public
 * surface; the scheduling algorithm itself is an implementation detail.
 */
final class DagScheduler {

    private final int maxConcurrency;

    DagScheduler(int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
        this.maxConcurrency = maxConcurrency;
    }

    OperationStatus run(OperationId operationId, OperationPlan plan, EventSink events, BooleanSupplier cancelled) {
        Map<String, OperationNode> nodesById = new HashMap<>();
        for (OperationNode node : plan.nodes()) {
            if (nodesById.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate node id in plan: " + node.id());
            }
        }
        for (OperationNode node : plan.nodes()) {
            for (String dep : node.dependsOn()) {
                if (!nodesById.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "Node '" + node.id() + "' depends on unknown node '" + dep + "'");
                }
            }
        }

        Map<String, Set<String>> remainingDeps = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, NodeStatus> status = new HashMap<>();
        for (OperationNode node : plan.nodes()) {
            remainingDeps.put(node.id(), new HashSet<>(node.dependsOn()));
            dependents.computeIfAbsent(node.id(), id -> new ArrayList<>());
            status.put(node.id(), NodeStatus.PENDING);
        }
        for (OperationNode node : plan.nodes()) {
            for (String dep : node.dependsOn()) {
                dependents.get(dep).add(node.id());
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrency);
        try {
            CompletionService<NodeOutcome> completionService = new ExecutorCompletionService<>(pool);
            Map<Future<NodeOutcome>, String> inFlight = new HashMap<>();

            Deque<String> ready = new ArrayDeque<>();
            for (Map.Entry<String, Set<String>> entry : remainingDeps.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    ready.add(entry.getKey());
                }
            }

            int remainingToFinish = plan.nodes().size();

            while (remainingToFinish > 0) {
                if (cancelled.getAsBoolean()) {
                    for (String id : status.keySet()) {
                        if (status.get(id) == NodeStatus.PENDING) {
                            status.put(id, NodeStatus.CANCELLED);
                            events.emit(nodeEvent(operationId, id, OperationEvent.Kind.SKIPPED, "cancelled before it started"));
                            remainingToFinish--;
                        }
                    }
                    ready.clear();
                    if (inFlight.isEmpty()) {
                        break;
                    }
                }

                while (!ready.isEmpty() && inFlight.size() < maxConcurrency) {
                    String id = ready.poll();
                    if (status.get(id) != NodeStatus.PENDING) {
                        continue; // may already have been swept to CANCELLED
                    }
                    status.put(id, NodeStatus.RUNNING);
                    OperationNode node = nodesById.get(id);
                    events.emit(nodeEvent(operationId, id, OperationEvent.Kind.STARTED, "started"));
                    Future<NodeOutcome> future = completionService.submit(() -> executeNode(operationId, node, events, cancelled));
                    inFlight.put(future, id);
                }

                if (inFlight.isEmpty()) {
                    // Nothing ready, nothing running, but nodes remain unresolved — only reachable
                    // via a dependency cycle (dangling references are already rejected above).
                    // Fail the remainder loudly rather than leaving it silently stuck PENDING.
                    for (String id : status.keySet()) {
                        if (status.get(id) == NodeStatus.PENDING) {
                            status.put(id, NodeStatus.FAILED);
                            events.emit(nodeEvent(operationId, id, OperationEvent.Kind.FAILED, "cyclic or unresolvable dependency"));
                        }
                    }
                    break;
                }

                Future<NodeOutcome> completed;
                try {
                    completed = completionService.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for a node to complete", e);
                }
                String finishedId = inFlight.remove(completed);
                remainingToFinish--;

                NodeOutcome outcome;
                try {
                    outcome = completed.get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    outcome = NodeOutcome.ofFailure(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                }

                if (outcome.succeeded()) {
                    status.put(finishedId, NodeStatus.SUCCEEDED);
                    events.emit(nodeEvent(operationId, finishedId, OperationEvent.Kind.COMPLETED, "succeeded"));

                    for (String dependent : dependents.get(finishedId)) {
                        Set<String> deps = remainingDeps.get(dependent);
                        deps.remove(finishedId);
                        if (deps.isEmpty() && status.get(dependent) == NodeStatus.PENDING) {
                            ready.add(dependent);
                        }
                    }
                } else {
                    status.put(finishedId, NodeStatus.FAILED);
                    events.emit(nodeEvent(operationId, finishedId, OperationEvent.Kind.FAILED, outcome.failureMessage()));
                    remainingToFinish -= skipTransitiveDependents(finishedId, dependents, status, events, operationId);
                }
            }

            return computeFinalStatus(status, cancelled.getAsBoolean());
        } finally {
            pool.shutdownNow();
        }
    }

    private int skipTransitiveDependents(
            String failedId, Map<String, List<String>> dependents, Map<String, NodeStatus> status,
            EventSink events, OperationId operationId) {
        int skipped = 0;
        Deque<String> queue = new ArrayDeque<>(dependents.get(failedId));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            if (status.get(id) == NodeStatus.PENDING) {
                status.put(id, NodeStatus.SKIPPED);
                events.emit(nodeEvent(operationId, id, OperationEvent.Kind.SKIPPED, "skipped: dependency " + failedId + " failed"));
                skipped++;
                queue.addAll(dependents.get(id));
            }
        }
        return skipped;
    }

    private OperationStatus computeFinalStatus(Map<String, NodeStatus> status, boolean wasCancelled) {
        if (wasCancelled && status.containsValue(NodeStatus.CANCELLED)) {
            return OperationStatus.CANCELLED;
        }
        boolean anyFailedOrSkipped = status.containsValue(NodeStatus.FAILED) || status.containsValue(NodeStatus.SKIPPED);
        if (!anyFailedOrSkipped) {
            return OperationStatus.SUCCEEDED;
        }
        boolean anySucceeded = status.containsValue(NodeStatus.SUCCEEDED);
        return anySucceeded ? OperationStatus.PARTIALLY_FAILED : OperationStatus.FAILED;
    }

    private static NodeOutcome executeNode(OperationId operationId, OperationNode node, EventSink events, BooleanSupplier cancelled) {
        NodeExecutionContext context = new NodeExecutionContext() {
            @Override
            public String nodeId() {
                return node.id();
            }

            @Override
            public boolean isCancelled() {
                return cancelled.getAsBoolean();
            }

            @Override
            public void progress(String message) {
                events.emit(nodeEvent(operationId, node.id(), OperationEvent.Kind.STEP_PROGRESS, message));
            }

            @Override
            public void log(String message) {
                events.emit(nodeEvent(operationId, node.id(), OperationEvent.Kind.LOG_LINE, message));
            }
        };

        try {
            node.action().run(context);
            return NodeOutcome.ofSuccess();
        } catch (Exception e) {
            return NodeOutcome.ofFailure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static OperationEvent nodeEvent(OperationId operationId, String nodeId, OperationEvent.Kind kind, String message) {
        return new OperationEvent(operationId, nodeId, kind, message, Instant.now());
    }

    private record NodeOutcome(boolean succeeded, String failureMessage) {
        static NodeOutcome ofSuccess() {
            return new NodeOutcome(true, null);
        }

        static NodeOutcome ofFailure(String message) {
            return new NodeOutcome(false, message);
        }
    }
}
