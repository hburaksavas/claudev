package dev.claudev.engine;

import dev.claudev.domain.DesiredState;
import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.InstanceState;
import dev.claudev.domain.OwnerSid;
import dev.claudev.domain.PortSet;
import dev.claudev.domain.RuntimeDefinitionId;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.WorkspaceId;
import dev.claudev.persistence.InstanceRepository;
import dev.claudev.persistence.LaunchRecordRepository;
import dev.claudev.persistence.PragmaAppliedDataSource;
import dev.claudev.persistence.SqlitePragmaConfigurer;
import dev.claudev.persistence.WorkspaceRepository;
import dev.claudev.platform.windows.LaunchResult;
import dev.claudev.platform.windows.LaunchSpec;
import dev.claudev.platform.windows.WindowsProcessLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the reconciler end to end using WP1's real spawn primitive and WP2's real persistence
 * — no mocked process, no mocked repository. This is what "WP3 is the glue between WP1 and WP2"
 * means in practice.
 */
class ReconcilerTest {

    @Test
    void runningInstanceIsMarkedRunningAndInstanceWithNoLaunchRecordIsMarkedStopped(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        DataSource dataSource = migratedDataSource(tempDir.resolve("reconciler.db"));
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(new JdbcTemplate(dataSource));
        InstanceRepository instanceRepository = new InstanceRepository(new JdbcTemplate(dataSource));
        LaunchRecordRepository launchRecordRepository = new LaunchRecordRepository(new JdbcTemplate(dataSource));

        Path managedDir = tempDir.resolve("managed-bin");
        Files.createDirectories(managedDir);

        SeedResult seed = seedWorkspaceAndRuntimeDefinition(dataSource, workspaceRepository);

        Instance runningInstance = newInstance(seed.workspaceId(), seed.runtimeDefinitionId(), "will-be-running");
        Instance stoppedInstance = newInstance(seed.workspaceId(), seed.runtimeDefinitionId(), "never-launched");
        instanceRepository.insert(runningInstance);
        instanceRepository.insert(stoppedInstance);

        LaunchResult launch = launchDummyInto(managedDir);
        try {
            launchRecordRepository.save(runningInstance.id(), new dev.claudev.domain.LaunchRecord(
                    launch.pid(), launch.imagePath(), launch.fingerprintSha256(), launch.creationTime(),
                    UUID.randomUUID(), 0, managedDir.toString(), "test-command-hash"));

            Reconciler reconciler = new Reconciler(instanceRepository, launchRecordRepository, managedDir);
            ReconciliationReport report = reconciler.reconcile();

            assertThat(report.running()).containsExactly(runningInstance.id());
            assertThat(report.stopped()).containsExactly(stoppedInstance.id());
            assertThat(report.orphaned()).isEmpty();
            assertThat(report.untrackedPids()).isEmpty();

            assertThat(instanceRepository.findById(runningInstance.id()).orElseThrow().value().observedState())
                    .isEqualTo(InstanceState.RUNNING);
            assertThat(instanceRepository.findById(stoppedInstance.id()).orElseThrow().value().observedState())
                    .isEqualTo(InstanceState.STOPPED);
        } finally {
            launch.job().terminate(1);
            launch.job().close();
        }
    }

    @Test
    void deadProcessIsMarkedOrphanedAndItsStaleLaunchRecordIsDeleted(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        DataSource dataSource = migratedDataSource(tempDir.resolve("reconciler-orphan.db"));
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(new JdbcTemplate(dataSource));
        InstanceRepository instanceRepository = new InstanceRepository(new JdbcTemplate(dataSource));
        LaunchRecordRepository launchRecordRepository = new LaunchRecordRepository(new JdbcTemplate(dataSource));

        Path managedDir = tempDir.resolve("managed-bin");
        Files.createDirectories(managedDir);

        SeedResult seed = seedWorkspaceAndRuntimeDefinition(dataSource, workspaceRepository);
        Instance instance = newInstance(seed.workspaceId(), seed.runtimeDefinitionId(), "will-die");
        instanceRepository.insert(instance);

        LaunchResult launch = launchDummyInto(managedDir);
        launchRecordRepository.save(instance.id(), new dev.claudev.domain.LaunchRecord(
                launch.pid(), launch.imagePath(), launch.fingerprintSha256(), launch.creationTime(),
                UUID.randomUUID(), 0, managedDir.toString(), "test-command-hash"));

        Reconciler reconciler = new Reconciler(instanceRepository, launchRecordRepository, managedDir);

        // Sanity: it's alive first.
        assertThat(reconciler.reconcile().running()).containsExactly(instance.id());

        // Kill it out-of-band (simulating a crash the app never orchestrated), then reconcile again.
        ProcessHandle handle = ProcessHandle.of(launch.pid()).orElseThrow();
        launch.job().terminate(1);
        launch.job().close();
        waitUntilNotAlive(handle, Duration.ofSeconds(10));

        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.orphaned()).containsExactly(instance.id());
        assertThat(report.running()).isEmpty();
        assertThat(instanceRepository.findById(instance.id()).orElseThrow().value().observedState())
                .isEqualTo(InstanceState.ORPHANED);
        assertThat(launchRecordRepository.findByInstanceId(instance.id())).isEmpty();
    }

    @Test
    void aProcessUnderTheManagedDirectoryWithNoLaunchRecordIsReportedUntracked(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        DataSource dataSource = migratedDataSource(tempDir.resolve("reconciler-untracked.db"));
        InstanceRepository instanceRepository = new InstanceRepository(new JdbcTemplate(dataSource));
        LaunchRecordRepository launchRecordRepository = new LaunchRecordRepository(new JdbcTemplate(dataSource));

        Path managedDir = tempDir.resolve("managed-bin");
        Files.createDirectories(managedDir);

        // A process running from the managed directory that no Instance/LaunchRecord knows about —
        // e.g. left behind by a bug in a spawn path that skipped the D5 ordering invariant.
        LaunchResult stray = launchDummyInto(managedDir);
        try {
            Reconciler reconciler = new Reconciler(instanceRepository, launchRecordRepository, managedDir);
            ReconciliationReport report = reconciler.reconcile();

            assertThat(report.untrackedPids()).contains(stray.pid());
        } finally {
            stray.job().terminate(1);
            stray.job().close();
        }
    }

    private static LaunchResult launchDummyInto(Path managedDir) throws Exception {
        Path systemRoot = Path.of(System.getenv("SystemRoot"));
        Path realPing = systemRoot.resolve("System32").resolve("ping.exe");
        Path copiedPing = managedDir.resolve("dummy-" + UUID.randomUUID() + ".exe");
        Files.copy(realPing, copiedPing, StandardCopyOption.REPLACE_EXISTING);

        LaunchSpec spec = new LaunchSpec(
                copiedPing, List.of(copiedPing.toString(), "-n", "30", "127.0.0.1"),
                null, Map.of("SystemRoot", systemRoot.toString()),
                "claudev-reconciler-test-" + UUID.randomUUID(), true);
        return WindowsProcessLauncher.launch(spec);
    }

    private static void waitUntilNotAlive(ProcessHandle handle, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!handle.isAlive()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("Process " + handle.pid() + " still alive after " + timeout);
    }

    private static Instance newInstance(WorkspaceId workspaceId, RuntimeDefinitionId runtimeDefinitionId, String name) {
        return new Instance(
                InstanceId.newId(), workspaceId, runtimeDefinitionId, name,
                new PortSet(Set.of(), Set.of()), Path.of("C:\\data"), Path.of("C:\\logs"),
                DesiredState.STARTED, Optional.empty(), InstanceState.UNKNOWN);
    }

    private record SeedResult(WorkspaceId workspaceId, RuntimeDefinitionId runtimeDefinitionId) {
    }

    private static SeedResult seedWorkspaceAndRuntimeDefinition(DataSource dataSource, WorkspaceRepository workspaceRepository) throws Exception {
        Workspace workspace = new Workspace(
                WorkspaceId.newId(), "ws", Instant.now(), new OwnerSid("S-1-5-21-1-2-3-1001"), List.of(), List.of());
        workspaceRepository.insert(workspace);

        RuntimeDefinitionId runtimeDefinitionId = RuntimeDefinitionId.newId();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO runtime_definition (id, kind, source_json, config_schema_version) VALUES ('"
                            + runtimeDefinitionId.value() + "', 'RABBIT_MQ', '{}', 1)");
        }

        return new SeedResult(workspace.id(), runtimeDefinitionId);
    }

    private static DataSource migratedDataSource(Path dbFile) {
        DriverManagerDataSource driverDataSource = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        driverDataSource.setDriverClassName("org.sqlite.JDBC");
        DataSource dataSource = new PragmaAppliedDataSource(driverDataSource, new SqlitePragmaConfigurer(5000));
        new dev.claudev.persistence.MigrationRunner().migrate(dataSource);
        return dataSource;
    }
}
