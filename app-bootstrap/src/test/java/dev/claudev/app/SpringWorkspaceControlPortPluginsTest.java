package dev.claudev.app;

import dev.claudev.adapter.dummy.DummyRuntimeProvider;
import dev.claudev.domain.Instance;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.OperationStatus;
import dev.claudev.domain.Workspace;
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
import dev.claudev.adapter.rabbitmq.detect.RabbitMqInstallDetector;
import dev.claudev.ui.WorkspaceControlPort;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP10c end-to-end, real: creates a RabbitMQ instance through the same {@link WorkspaceControlPort}
 * the UI calls, starts it for real (reusing the WP6 spike's cached binaries via {@code
 * RabbitMqProviderHolder}'s imported-path mode, WP10b), then manages a real plugin on it — proving
 * {@link SpringWorkspaceControlPort#requireRabbitMqProvider}'s dispatch/cast logic actually works
 * against a live node, not just {@code RabbitMqRuntimeProvider} in isolation.
 */
class SpringWorkspaceControlPortPluginsTest {

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

    private SpringWorkspaceControlPort newPort(Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(SPIKE_ERLANG_HOME.resolve("bin").resolve("erl.exe")),
                "WP6 spike cache not present on this machine — skipping");

        DataSource dataSource = migratedDb(tempDir.resolve("plugins-port.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        InstanceRepository instanceRepository = new InstanceRepository(jdbcTemplate);
        LaunchRecordRepository launchRecordRepository = new LaunchRecordRepository(jdbcTemplate);
        RuntimeDefinitionRepository runtimeDefinitionRepository = new RuntimeDefinitionRepository(jdbcTemplate);
        RuntimeProvider dummyRuntimeProvider = new DummyRuntimeProvider();
        RabbitMqProviderHolder rabbitMqProviderHolder = new RabbitMqProviderHolder(
                tempDir.resolve("unused-managed-dir").toString(),
                SPIKE_ERLANG_HOME.toString(), SPIKE_RABBITMQ_SBIN.toString());
        OperationEngine operationEngine = new InMemoryOperationEngine(
                new OperationRepository(jdbcTemplate), new OperationEventRepository(jdbcTemplate), 4);
        Reconciler reconciler = new Reconciler(instanceRepository, launchRecordRepository, tempDir.resolve("managed-binaries"));

        return new SpringWorkspaceControlPort(
                workspaceRepository, instanceRepository, launchRecordRepository, runtimeDefinitionRepository,
                dummyRuntimeProvider, rabbitMqProviderHolder, new RabbitMqInstallDetector(), operationEngine, reconciler,
                tempDir.resolve("instances").toString());
    }

    @Test
    void managingPluginsOnARealRabbitMqInstanceThroughTheFullPortWorks(@TempDir Path tempDir) throws InterruptedException {
        SpringWorkspaceControlPort port = newPort(tempDir);

        Workspace workspace = port.createWorkspace("plugin-test-workspace");
        Instance instance = port.createRabbitMqInstance(
                workspace.id(), "plugin-test-instance", SPIKE_ERLANG_HOME, SPIKE_RABBITMQ_SBIN);

        OperationId startOperation = port.startInstance(instance.id());
        awaitTerminal(port, startOperation);

        try {
            List<String> initial = port.listEnabledPlugins(instance.id());
            assertThat(initial).doesNotContain("rabbitmq_shovel");

            port.enablePlugin(instance.id(), "rabbitmq_shovel");
            assertThat(port.listEnabledPlugins(instance.id())).contains("rabbitmq_shovel");

            port.disablePlugin(instance.id(), "rabbitmq_shovel");
            assertThat(port.listEnabledPlugins(instance.id())).doesNotContain("rabbitmq_shovel");
        } finally {
            // Awaited (not fire-and-forget) so the node's log file handles are actually released
            // before this method returns — otherwise @TempDir's cleanup races the still-exiting
            // cmd.exe/erl.exe process for those files, the same Windows file-lock class documented
            // elsewhere in this codebase (see ReconcilerTest, RunningProcessScannerTest).
            awaitTerminal(port, port.stopInstance(instance.id()));
        }
    }

    @Test
    void pluginManagementOnADummyInstanceIsRejected(@TempDir Path tempDir) throws InterruptedException {
        SpringWorkspaceControlPort port = newPort(tempDir);
        Workspace workspace = port.createWorkspace("dummy-workspace");
        Instance instance = port.createDummyInstance(workspace.id(), "dummy-instance");

        assertThatThrownBy(() -> port.listEnabledPlugins(instance.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only supported for RabbitMQ");
    }

    private static void awaitTerminal(SpringWorkspaceControlPort port, OperationId operationId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            var found = port.findOperation(operationId);
            if (found.isPresent() && isTerminal(found.get().status())) {
                assertThat(found.get().status()).isEqualTo(OperationStatus.SUCCEEDED);
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("operation " + operationId.value() + " did not reach a terminal state in time");
    }

    private static boolean isTerminal(OperationStatus status) {
        return status == OperationStatus.SUCCEEDED || status == OperationStatus.FAILED
                || status == OperationStatus.PARTIALLY_FAILED || status == OperationStatus.CANCELLED;
    }
}
