package dev.claudev.app;

import dev.claudev.adapter.dummy.DummyRuntimeProvider;
import dev.claudev.adapter.rabbitmq.detect.RabbitMqInstallDetector;
import dev.claudev.domain.Instance;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.detect.DetectedCandidate;
import dev.claudev.engine.InMemoryOperationEngine;
import dev.claudev.engine.OperationEngine;
import dev.claudev.engine.Reconciler;
import dev.claudev.persistence.InstanceRepository;
import dev.claudev.persistence.LaunchRecordRepository;
import dev.claudev.persistence.MigrationRunner;
import dev.claudev.persistence.OperationEventRepository;
import dev.claudev.persistence.OperationRepository;
import dev.claudev.persistence.PragmaAppliedDataSource;
import dev.claudev.persistence.RuntimeDefinitionRepository;
import dev.claudev.persistence.SqlitePragmaConfigurer;
import dev.claudev.persistence.WorkspaceRepository;
import dev.claudev.provider.runtime.RuntimeProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP10d: {@link SpringWorkspaceControlPort#scanForRabbitMqCandidates()} delegation and
 * {@link RabbitMqProviderHolder#configureImported} accept/reject behavior through the full port.
 */
class SpringWorkspaceControlPortRabbitMqDetectionTest {

    private static final Path SPIKE_ERLANG_HOME = Path.of("D:\\dev\\workspace\\claudev-spike\\otp27");
    private static final Path SPIKE_RABBITMQ_SBIN =
            Path.of("D:\\dev\\workspace\\claudev-spike\\rabbitmq\\rabbitmq_server-4.3.5\\sbin");

    private static DataSource migratedDb(Path dbFile) {
        DriverManagerDataSource driver = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        driver.setDriverClassName("org.sqlite.JDBC");
        DataSource pragmaApplied = new PragmaAppliedDataSource(driver, new SqlitePragmaConfigurer(5000));
        new MigrationRunner().migrate(pragmaApplied);
        return pragmaApplied;
    }

    private SpringWorkspaceControlPort newPort(Path tempDir, RabbitMqProviderHolder rabbitMqProviderHolder) {
        DataSource dataSource = migratedDb(tempDir.resolve("detection-port.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        InstanceRepository instanceRepository = new InstanceRepository(jdbcTemplate);
        LaunchRecordRepository launchRecordRepository = new LaunchRecordRepository(jdbcTemplate);
        RuntimeDefinitionRepository runtimeDefinitionRepository = new RuntimeDefinitionRepository(jdbcTemplate);
        RuntimeProvider dummyRuntimeProvider = new DummyRuntimeProvider();
        OperationEngine operationEngine = new InMemoryOperationEngine(
                new OperationRepository(jdbcTemplate), new OperationEventRepository(jdbcTemplate), 4);
        Reconciler reconciler = new Reconciler(instanceRepository, launchRecordRepository, tempDir.resolve("managed-binaries"));

        return new SpringWorkspaceControlPort(
                workspaceRepository, instanceRepository, launchRecordRepository, runtimeDefinitionRepository,
                dummyRuntimeProvider, rabbitMqProviderHolder, new RabbitMqInstallDetector(), operationEngine, reconciler,
                tempDir.resolve("instances").toString());
    }

    @Test
    void scanForRabbitMqCandidatesRunsTheRealDetectorWithoutThrowing(@TempDir Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        SpringWorkspaceControlPort port = newPort(tempDir,
                new RabbitMqProviderHolder(tempDir.resolve("unused-managed-dir").toString(), "", ""));

        List<DetectedCandidate> candidates = port.scanForRabbitMqCandidates();

        assertThat(candidates).isNotNull();
    }

    @Test
    void createRabbitMqInstanceWithACandidateConfiguresTheProviderHolder(@TempDir Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(SPIKE_ERLANG_HOME.resolve("bin").resolve("erl.exe")),
                "WP6 spike cache not present on this machine — skipping");

        RabbitMqProviderHolder holder = new RabbitMqProviderHolder(
                tempDir.resolve("unused-managed-dir").toString(), "", "");
        SpringWorkspaceControlPort port = newPort(tempDir, holder);

        Workspace workspace = port.createWorkspace("detection-workspace");
        DetectedCandidate candidate = new DetectedCandidate(
                "RabbitMQ 4.3.5 + Erlang/OTP 27 (test)", SPIKE_ERLANG_HOME, SPIKE_RABBITMQ_SBIN,
                "4.3.5", "27.3.4.17", "test", true);

        Instance instance = port.createRabbitMqInstance(workspace.id(), "detected-instance", candidate);

        assertThat(instance).isNotNull();
        assertThat(holder.isImportedConfigured()).isTrue();
        assertThat(holder.describedSourcePath()).isEqualTo(SPIKE_RABBITMQ_SBIN);
    }

    @Test
    void createRabbitMqInstanceRejectsAConflictingCandidateOnceAProviderIsActive(@TempDir Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(SPIKE_ERLANG_HOME.resolve("bin").resolve("erl.exe")),
                "WP6 spike cache not present on this machine — skipping");

        RabbitMqProviderHolder holder = new RabbitMqProviderHolder(
                tempDir.resolve("unused-managed-dir").toString(),
                SPIKE_ERLANG_HOME.toString(), SPIKE_RABBITMQ_SBIN.toString());
        SpringWorkspaceControlPort port = newPort(tempDir, holder);

        Workspace workspace = port.createWorkspace("conflict-workspace");
        port.createRabbitMqInstance(workspace.id(), "first-instance", SPIKE_ERLANG_HOME, SPIKE_RABBITMQ_SBIN);

        DetectedCandidate conflicting = new DetectedCandidate(
                "conflicting", Path.of("C:\\other\\erlang"), Path.of("C:\\other\\rabbitmq\\sbin"),
                "4.3.5", "27.0.0", "test", true);

        assertThatThrownBy(() -> port.createRabbitMqInstance(workspace.id(), "second-instance", conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already configured");
    }
}
