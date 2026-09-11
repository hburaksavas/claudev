package dev.claudev.engine;

import dev.claudev.domain.Operation;
import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.OperationStatus;
import dev.claudev.persistence.MigrationRunner;
import dev.claudev.persistence.OperationEventRepository;
import dev.claudev.persistence.OperationRepository;
import dev.claudev.persistence.PragmaAppliedDataSource;
import dev.claudev.persistence.SqlitePragmaConfigurer;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.runtime.StartInstanceCommand;
import dev.claudev.provider.runtime.StopInstanceCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests against the real {@link InMemoryOperationEngine}: real SQLite persistence (WP2)
 * and, for the spawn-carrying scenarios, a real process launched via WP1's
 * {@link dev.claudev.platform.windows.WindowsProcessLauncher} through {@link DummyRuntimeProvider}.
 * Nothing here is mocked.
 */
class InMemoryOperationEngineTest {

    @Test
    void aPlanWithARealSpawnNodeSucceedsAndPersistsExactlyTheEventsThatWereEmittedLive(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        DataSource dataSource = migratedDataSource(tempDir.resolve("engine.db"));
        OperationRepository operationRepository = new OperationRepository(new JdbcTemplate(dataSource));
        OperationEventRepository eventRepository = new OperationEventRepository(new JdbcTemplate(dataSource));
        InMemoryOperationEngine engine = new InMemoryOperationEngine(operationRepository, eventRepository, 4);

        List<OperationEvent> liveEvents = new CopyOnWriteArrayList<>();
        engine.subscribe(liveEvents::add);

        DummyRuntimeProvider provider = new DummyRuntimeProvider();
        AtomicBoolean computeRan = new AtomicBoolean(false);
        String instanceId = "instance-1";

        OperationPlan plan = new OperationPlan("start-workspace", List.of(instanceId), List.of(
                new OperationNode("spawn-dummy", List.of(), ctx -> {
                    ProviderResult<?> started = provider.start(new StartInstanceCommand(
                            instanceId, "rt-def-1", tempDir.resolve("data"), tempDir.resolve("logs"), Set.of(), Map.of()));
                    if (!started.isOk()) {
                        throw new IllegalStateException("dummy provider failed to start: " + started);
                    }
                    provider.stop(new StopInstanceCommand(instanceId, 0, Instant.now(), null, true));
                }),
                new OperationNode("compute", List.of("spawn-dummy"), ctx -> computeRan.set(true))));

        OperationId operationId = engine.submit(plan);
        Operation finalOperation = waitUntilTerminal(engine, operationId, Duration.ofSeconds(15));

        assertThat(finalOperation.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(computeRan.get()).isTrue();

        List<OperationEvent> persisted = eventRepository.findByOperationId(operationId);
        assertThat(persisted).isNotEmpty();
        assertThat(persisted).containsExactlyElementsOf(liveEvents);
        assertThat(finalOperation.events()).containsExactlyElementsOf(persisted);
    }

    @Test
    void aPlanWithAFailingNodeEndsPartiallyFailedAndIsFullyReconstructableFromPersistedEvents(@TempDir Path tempDir) {
        DataSource dataSource = migratedDataSource(tempDir.resolve("engine-partial-fail.db"));
        OperationRepository operationRepository = new OperationRepository(new JdbcTemplate(dataSource));
        OperationEventRepository eventRepository = new OperationEventRepository(new JdbcTemplate(dataSource));
        InMemoryOperationEngine engine = new InMemoryOperationEngine(operationRepository, eventRepository, 4);

        OperationPlan plan = new OperationPlan("test", List.of(), List.of(
                new OperationNode("fails", List.of(), ctx -> {
                    throw new RuntimeException("deliberate failure");
                }),
                new OperationNode("skipped-dependent", List.of("fails"), ctx -> { }),
                new OperationNode("unrelated-succeeds", List.of(), ctx -> { })));

        OperationId operationId = engine.submit(plan);
        Operation finalOperation = waitUntilTerminal(engine, operationId, Duration.ofSeconds(15));

        assertThat(finalOperation.status()).isEqualTo(OperationStatus.PARTIALLY_FAILED);

        List<OperationEvent> persisted = eventRepository.findByOperationId(operationId);
        assertThat(persisted).extracting(OperationEvent::nodeId, OperationEvent::kind)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("fails", OperationEvent.Kind.FAILED),
                        org.assertj.core.groups.Tuple.tuple("skipped-dependent", OperationEvent.Kind.SKIPPED),
                        org.assertj.core.groups.Tuple.tuple("unrelated-succeeds", OperationEvent.Kind.COMPLETED));

        // find() reads back purely from the database — proves persistence alone reconstructs this,
        // not any in-memory state left over from the run itself.
        Operation reloaded = engine.find(operationId).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(OperationStatus.PARTIALLY_FAILED);
        assertThat(reloaded.events()).containsExactlyElementsOf(persisted);
    }

    @Test
    @Timeout(20)
    void cancellingAfterSubmitStopsThePendingNodeAndLeavesNoOrphanedProcess(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        DataSource dataSource = migratedDataSource(tempDir.resolve("engine-cancel.db"));
        OperationRepository operationRepository = new OperationRepository(new JdbcTemplate(dataSource));
        OperationEventRepository eventRepository = new OperationEventRepository(new JdbcTemplate(dataSource));
        InMemoryOperationEngine engine = new InMemoryOperationEngine(operationRepository, eventRepository, 4);

        DummyRuntimeProvider provider = new DummyRuntimeProvider();
        AtomicBoolean neverStartsRan = new AtomicBoolean(false);
        String instanceId = "instance-cancel";

        OperationPlan plan = new OperationPlan("start-workspace", List.of(instanceId), List.of(
                new OperationNode("blocks-until-cancelled", List.of(), ctx -> {
                    ProviderResult<?> started = provider.start(new StartInstanceCommand(
                            instanceId, "rt-def-1", tempDir.resolve("data"), tempDir.resolve("logs"), Set.of(), Map.of()));
                    if (!started.isOk()) {
                        throw new IllegalStateException("dummy provider failed to start: " + started);
                    }
                    while (!ctx.isCancelled()) {
                        Thread.sleep(50);
                    }
                    provider.stop(new StopInstanceCommand(instanceId, 0, Instant.now(), null, true));
                }),
                new OperationNode("never-starts", List.of("blocks-until-cancelled"), ctx -> neverStartsRan.set(true))));

        OperationId operationId = engine.submit(plan);

        // Give the first node a moment to actually spawn its process before requesting cancellation.
        Instant deadline = Instant.now().plusSeconds(10);
        while (!provider.isStillAlive(instanceId) && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(provider.isStillAlive(instanceId)).as("dummy process should be alive before cancelling").isTrue();

        engine.cancel(operationId);
        Operation finalOperation = waitUntilTerminal(engine, operationId, Duration.ofSeconds(15));

        assertThat(finalOperation.status()).isEqualTo(OperationStatus.CANCELLED);
        assertThat(neverStartsRan.get()).isFalse();
        assertThat(provider.isStillAlive(instanceId)).as("no orphaned process after cancellation").isFalse();
    }

    private static Operation waitUntilTerminal(OperationEngine engine, OperationId operationId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<Operation> operation = engine.find(operationId);
            if (operation.isPresent() && isTerminal(operation.get().status())) {
                return operation.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Operation " + operationId + " did not reach a terminal status within " + timeout);
    }

    private static boolean isTerminal(OperationStatus status) {
        return status == OperationStatus.SUCCEEDED || status == OperationStatus.FAILED
                || status == OperationStatus.PARTIALLY_FAILED || status == OperationStatus.CANCELLED;
    }

    private static DataSource migratedDataSource(Path dbFile) {
        DriverManagerDataSource driverDataSource = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        driverDataSource.setDriverClassName("org.sqlite.JDBC");
        DataSource dataSource = new PragmaAppliedDataSource(driverDataSource, new SqlitePragmaConfigurer(5000));
        new MigrationRunner().migrate(dataSource);
        return dataSource;
    }
}
