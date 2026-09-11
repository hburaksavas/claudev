package dev.claudev.persistence;

import dev.claudev.domain.DesiredState;
import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.InstanceState;
import dev.claudev.domain.LaunchRecord;
import dev.claudev.domain.PortSet;
import dev.claudev.domain.RuntimeDefinitionId;
import dev.claudev.domain.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link LaunchRecordRepository#save} is synchronous and durable: once the call returns, a
 * completely separate JDBC connection — opened directly via {@link DriverManager}, bypassing the
 * repository/DataSource under test entirely — can already see the row. That is the property the D5
 * ordering invariant actually depends on: if the JVM crashed the instant after {@code save()}
 * returned, the row would already be committed to the WAL file on disk.
 */
class LaunchRecordDurabilityTest {

    @Test
    void savedRecordIsImmediatelyVisibleOnACompletelySeparateConnection(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("launch-record-durability.db");
        DataSource dataSource = TestDataSources.migrated(dbFile);

        InstanceId instanceId = seedWorkspaceAndInstance(dataSource);

        LaunchRecord record = new LaunchRecord(
                4242, "C:\\claudev\\bin\\rabbitmq-server.exe", "deadbeef".repeat(8),
                Instant.now(), UUID.randomUUID(), 99, "C:\\claudev\\work", "commandhash123");

        new LaunchRecordRepository(new JdbcTemplate(dataSource)).save(instanceId, record);

        // Bypasses the DataSource under test entirely — a raw driver connection to the same file.
        try (Connection rawConnection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             PreparedStatement statement =
                     rawConnection.prepareStatement("SELECT pid, exe_path, instance_token FROM launch_record WHERE instance_id = ?")) {
            statement.setString(1, instanceId.value().toString());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("row must already be committed and visible").isTrue();
                assertThat(rs.getLong("pid")).isEqualTo(4242);
                assertThat(rs.getString("exe_path")).isEqualTo("C:\\claudev\\bin\\rabbitmq-server.exe");
                assertThat(rs.getString("instance_token")).isEqualTo(record.instanceToken().toString());
            }
        }

        // And through the repository's own read path.
        var loaded = new LaunchRecordRepository(new JdbcTemplate(dataSource)).findByInstanceId(instanceId);
        assertThat(loaded).contains(record);
    }

    @Test
    void savingASecondLaunchReplacesTheFirstAtomically(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("launch-record-replace.db"));
        InstanceId instanceId = seedWorkspaceAndInstance(dataSource);
        LaunchRecordRepository repository = new LaunchRecordRepository(new JdbcTemplate(dataSource));

        LaunchRecord first = new LaunchRecord(1111, "exe1", "fp1", Instant.now(), UUID.randomUUID(), 1, "wd1", "ch1");
        LaunchRecord second = new LaunchRecord(2222, "exe2", "fp2", Instant.now(), UUID.randomUUID(), 2, "wd2", "ch2");

        repository.save(instanceId, first);
        repository.save(instanceId, second);

        var loaded = repository.findByInstanceId(instanceId);
        assertThat(loaded).contains(second);

        // Exactly one row for this instance — REPLACE, not an accumulating history.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("SELECT COUNT(*) FROM launch_record WHERE instance_id = ?")) {
            statement.setString(1, instanceId.value().toString());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    private static InstanceId seedWorkspaceAndInstance(DataSource dataSource) throws Exception {
        RuntimeDefinitionId runtimeDefinitionId = RuntimeDefinitionId.newId();
        WorkspaceId workspaceId = WorkspaceId.newId();
        InstanceId instanceId = InstanceId.newId();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO runtime_definition (id, kind, source_json, config_schema_version) VALUES ('"
                            + runtimeDefinitionId.value() + "', 'RABBIT_MQ', '{}', 1)");
            statement.execute(
                    "INSERT INTO workspace (id, name, created_at, owner_sid, revision) VALUES ('"
                            + workspaceId.value() + "', 'ws', '" + Instant.now() + "', 'S-1-5-21-1-2-3-1001', 0)");
        }

        Instance instance = new Instance(
                instanceId, workspaceId, runtimeDefinitionId, "rabbit-1",
                new PortSet(Set.of(), Set.of()), Path.of("C:\\data"), Path.of("C:\\logs"),
                DesiredState.STARTED, java.util.Optional.empty(), InstanceState.STOPPED);
        new InstanceRepository(new JdbcTemplate(dataSource)).insert(instance);

        return instanceId;
    }
}
