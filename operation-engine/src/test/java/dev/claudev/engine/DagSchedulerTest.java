package dev.claudev.engine;

import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.OperationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure, fast, no I/O — exercises the graph/concurrency algorithm itself in isolation from persistence. */
class DagSchedulerTest {

    private final OperationId operationId = OperationId.newId();

    @Test
    void linearChainRunsInDependencyOrderAndSucceeds() {
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        OperationPlan plan = new OperationPlan("test", List.of(), List.of(
                node("a", List.of(), ctx -> executionOrder.add("a")),
                node("b", List.of("a"), ctx -> executionOrder.add("b")),
                node("c", List.of("b"), ctx -> executionOrder.add("c"))));

        List<OperationEvent> events = new CopyOnWriteArrayList<>();
        OperationStatus status = new DagScheduler(4).run(operationId, plan, events::add, () -> false);

        assertThat(status).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(executionOrder).containsExactly("a", "b", "c");
        assertThat(events).filteredOn(e -> e.kind() == OperationEvent.Kind.COMPLETED).hasSize(3);
    }

    @Test
    void aFailedNodeSkipsOnlyItsTransitiveDependentsNotUnrelatedBranches() {
        Set<String> ran = ConcurrentHashMap.newKeySet();
        OperationPlan plan = new OperationPlan("test", List.of(), List.of(
                node("fails", List.of(), ctx -> {
                    throw new RuntimeException("boom");
                }),
                node("dependent-of-failure", List.of("fails"), ctx -> ran.add("dependent-of-failure")),
                node("grandchild-of-failure", List.of("dependent-of-failure"), ctx -> ran.add("grandchild-of-failure")),
                node("unrelated", List.of(), ctx -> ran.add("unrelated"))));

        List<OperationEvent> events = new CopyOnWriteArrayList<>();
        OperationStatus status = new DagScheduler(4).run(operationId, plan, events::add, () -> false);

        assertThat(status).isEqualTo(OperationStatus.PARTIALLY_FAILED);
        assertThat(ran).containsExactly("unrelated");

        List<String> skippedNodeIds = events.stream()
                .filter(e -> e.kind() == OperationEvent.Kind.SKIPPED)
                .map(OperationEvent::nodeId)
                .toList();
        assertThat(skippedNodeIds).containsExactlyInAnyOrder("dependent-of-failure", "grandchild-of-failure");
    }

    @Test
    void allNodesFailingIsPlainFailedNotPartiallyFailed() {
        OperationPlan plan = new OperationPlan("test", List.of(), List.of(
                node("only", List.of(), ctx -> {
                    throw new RuntimeException("boom");
                })));

        OperationStatus status = new DagScheduler(2).run(operationId, plan, e -> { }, () -> false);

        assertThat(status).isEqualTo(OperationStatus.FAILED);
    }

    @Test
    @Timeout(10)
    void cancellingStopsPendingNodesAndNeverStartsThem() throws InterruptedException {
        CountDownLatch runningLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        AtomicBoolean pendingNodeStarted = new AtomicBoolean(false);
        AtomicBoolean cancelRequested = new AtomicBoolean(false);

        OperationPlan plan = new OperationPlan("test", List.of(), List.of(
                node("blocks", List.of(), ctx -> {
                    runningLatch.countDown();
                    releaseLatch.await(10, TimeUnit.SECONDS);
                }),
                node("never-starts", List.of("blocks"), ctx -> pendingNodeStarted.set(true))));

        List<OperationStatus> observedStatus = new CopyOnWriteArrayList<>();
        Thread runner = new Thread(() ->
                observedStatus.add(new DagScheduler(4).run(operationId, plan, e -> { }, cancelRequested::get)));
        runner.start();

        assertThat(runningLatch.await(5, TimeUnit.SECONDS)).isTrue();
        cancelRequested.set(true);
        releaseLatch.countDown();
        runner.join(10_000);

        assertThat(observedStatus).containsExactly(OperationStatus.CANCELLED);
        assertThat(pendingNodeStarted.get()).isFalse();
    }

    @Test
    void respectsBoundedConcurrency() {
        int maxConcurrency = 2;
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);

        List<OperationNode> nodes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            nodes.add(node("n" + i, List.of(), ctx -> {
                int current = concurrentCount.incrementAndGet();
                maxObserved.updateAndGet(prev -> Math.max(prev, current));
                Thread.sleep(50);
                concurrentCount.decrementAndGet();
            }));
        }
        OperationPlan plan = new OperationPlan("test", List.of(), nodes);

        OperationStatus status = new DagScheduler(maxConcurrency).run(operationId, plan, e -> { }, () -> false);

        assertThat(status).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(maxObserved.get()).isLessThanOrEqualTo(maxConcurrency);
    }

    @Test
    void rejectsADanglingDependency() {
        OperationPlan plan = new OperationPlan("test", List.of(), List.of(
                node("a", List.of("nonexistent"), ctx -> { })));

        assertThatThrownBy(() -> new DagScheduler(2).run(operationId, plan, e -> { }, () -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void rejectsADuplicateNodeId() {
        OperationPlan plan = new OperationPlan("test", List.of(), List.of(
                node("dup", List.of(), ctx -> { }),
                node("dup", List.of(), ctx -> { })));

        assertThatThrownBy(() -> new DagScheduler(2).run(operationId, plan, e -> { }, () -> false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(10)
    void aCyclicDependencyFailsLoudlyInsteadOfHanging() {
        OperationPlan plan = new OperationPlan("test", List.of(), List.of(
                node("a", List.of("b"), ctx -> { }),
                node("b", List.of("a"), ctx -> { })));

        OperationStatus status = new DagScheduler(2).run(operationId, plan, e -> { }, () -> false);

        assertThat(status).isEqualTo(OperationStatus.FAILED);
    }

    private static OperationNode node(String id, List<String> dependsOn, NodeAction action) {
        return new OperationNode(id, dependsOn, action);
    }
}
