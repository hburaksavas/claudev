package dev.claudev.app;

import dev.claudev.adapter.dummy.DummyRuntimeProvider;
import dev.claudev.adapter.rabbitmq.detect.RabbitMqInstallDetector;
import dev.claudev.adapter.redis.detect.RedisInstallDetector;
import dev.claudev.domain.Instance;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.detect.DetectedRedisInstall;
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
 * WP10f: {@link SpringWorkspaceControlPort#scanForRedisInstallCandidates()} delegation and
 * {@link RedisProviderHolder#configureImported} accept/reject behavior through the full port.
 */
class SpringWorkspaceControlPortRedisRuntimeTest {

    private static final Path REDIS_SERVER_EXE =
            Path.of("D:\\dev\\workspace\\claudev-spike\\redis-test\\extracted\\redis-server.exe");

    private static DataSource migratedDb(Path dbFile) {
        DriverManagerDataSource driver = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        driver.setDriverClassName("org.sqlite.JDBC");
        DataSource pragmaApplied = new PragmaAppliedDataSource(driver, new SqlitePragmaConfigurer(5000));
        new MigrationRunner().migrate(pragmaApplied);
        return pragmaApplied;
    }

    private SpringWorkspaceControlPort newPort(Path tempDir, RedisProviderHolder redisProviderHolder) {
        DataSource dataSource = migratedDb(tempDir.resolve("redis-runtime-port.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        InstanceRepository instanceRepository = new InstanceRepository(jdbcTemplate);
        LaunchRecordRepository launchRecordRepository = new LaunchRecordRepository(jdbcTemplate);
        RuntimeDefinitionRepository runtimeDefinitionRepository = new RuntimeDefinitionRepository(jdbcTemplate);
        RuntimeProvider dummyRuntimeProvider = new DummyRuntimeProvider();
        RabbitMqProviderHolder rabbitMqProviderHolder = new RabbitMqProviderHolder(
                tempDir.resolve("unused-managed-dir").toString(), "", "");
        OperationEngine operationEngine = new InMemoryOperationEngine(
                new OperationRepository(jdbcTemplate), new OperationEventRepository(jdbcTemplate), 4);
        Reconciler reconciler = new Reconciler(instanceRepository, launchRecordRepository, tempDir.resolve("managed-binaries"));

        return new SpringWorkspaceControlPort(
                workspaceRepository, instanceRepository, launchRecordRepository, runtimeDefinitionRepository,
                dummyRuntimeProvider, rabbitMqProviderHolder, new RabbitMqInstallDetector(),
                redisProviderHolder, new RedisInstallDetector(), operationEngine, reconciler,
                tempDir.resolve("instances").toString());
    }

    @Test
    void scanForRedisInstallCandidatesRunsTheRealDetectorWithoutThrowing(@TempDir Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        SpringWorkspaceControlPort port = newPort(tempDir, new RedisProviderHolder(""));

        List<DetectedRedisInstall> candidates = port.scanForRedisInstallCandidates();

        assertThat(candidates).isNotNull();
    }

    @Test
    void createRedisInstanceWithACandidateConfiguresTheProviderHolder(@TempDir Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(REDIS_SERVER_EXE),
                "Redis test binary not present on this machine — skipping (see docs/REDIS_SCOPE.md)");

        RedisProviderHolder holder = new RedisProviderHolder("");
        SpringWorkspaceControlPort port = newPort(tempDir, holder);

        Workspace workspace = port.createWorkspace("redis-runtime-workspace");
        DetectedRedisInstall candidate = new DetectedRedisInstall(REDIS_SERVER_EXE, "test");

        Instance instance = port.createRedisInstance(workspace.id(), "detected-redis-instance", candidate);

        assertThat(instance).isNotNull();
        assertThat(holder.isImportedConfigured()).isTrue();
        assertThat(holder.describedSourcePath()).isEqualTo(REDIS_SERVER_EXE);
    }

    @Test
    void createRedisInstanceRejectsAConflictingCandidateOnceAProviderIsActive(@TempDir Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(REDIS_SERVER_EXE),
                "Redis test binary not present on this machine — skipping (see docs/REDIS_SCOPE.md)");

        RedisProviderHolder holder = new RedisProviderHolder(REDIS_SERVER_EXE.toString());
        SpringWorkspaceControlPort port = newPort(tempDir, holder);

        Workspace workspace = port.createWorkspace("redis-conflict-workspace");
        port.createRedisInstance(workspace.id(), "first-redis-instance", REDIS_SERVER_EXE);

        DetectedRedisInstall conflicting = new DetectedRedisInstall(Path.of("C:\\other\\redis-server.exe"), "test");

        assertThatThrownBy(() -> port.createRedisInstance(workspace.id(), "second-redis-instance", conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already configured");
    }
}
