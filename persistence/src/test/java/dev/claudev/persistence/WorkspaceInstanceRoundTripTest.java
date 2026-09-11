package dev.claudev.persistence;

import dev.claudev.domain.DesiredState;
import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.InstanceState;
import dev.claudev.domain.OwnerSid;
import dev.claudev.domain.PortSet;
import dev.claudev.domain.RuntimeDefinitionId;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Restart the app" cannot literally happen inside one JVM, so this simulates it the way it
 * actually matters: everything is written through one {@link DataSource}, then read back through a
 * completely separate {@link DataSource} instance opened fresh against the same on-disk file — no
 * Java object, cache, or connection is shared between the two. If this passes, the data survived
 * purely on disk, which is what a real restart would also depend on.
 */
class WorkspaceInstanceRoundTripTest {

    @Test
    void workspaceAndInstanceSurviveASimulatedRestartIdentically(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("round-trip.db");

        DataSource firstProcess = TestDataSources.migrated(dbFile);
        RuntimeDefinitionId runtimeDefinitionId = insertRuntimeDefinitionRow(firstProcess);

        Workspace workspace = new Workspace(
                WorkspaceId.newId(), "my-workspace", Instant.now(),
                new OwnerSid("S-1-5-21-1-2-3-1001"), List.of(), List.of());
        new WorkspaceRepository(new JdbcTemplate(firstProcess)).insert(workspace);

        Instance instance = new Instance(
                InstanceId.newId(), workspace.id(), runtimeDefinitionId, "rabbit-1",
                new PortSet(Set.of(5672, 15672), Set.of()),
                Path.of("C:\\claudev\\data\\rabbit-1"), Path.of("C:\\claudev\\logs\\rabbit-1"),
                DesiredState.STARTED, java.util.Optional.empty(), InstanceState.STOPPED);
        new InstanceRepository(new JdbcTemplate(firstProcess)).insert(instance);

        // A genuinely separate DataSource, opened fresh against the same file — nothing shared with "firstProcess".
        DataSource secondProcess = TestDataSources.migrated(dbFile);

        var loadedWorkspace = new WorkspaceRepository(new JdbcTemplate(secondProcess)).findById(workspace.id());
        assertThat(loadedWorkspace).isPresent();
        assertThat(loadedWorkspace.get().value().id()).isEqualTo(workspace.id());
        assertThat(loadedWorkspace.get().value().name()).isEqualTo("my-workspace");
        assertThat(loadedWorkspace.get().value().ownerSid()).isEqualTo(workspace.ownerSid());
        assertThat(loadedWorkspace.get().value().instanceIds()).containsExactly(instance.id());
        assertThat(loadedWorkspace.get().value().pipelineIds()).isEmpty();
        assertThat(loadedWorkspace.get().revision()).isEqualTo(0);

        var loadedInstance = new InstanceRepository(new JdbcTemplate(secondProcess)).findById(instance.id());
        assertThat(loadedInstance).isPresent();
        assertThat(loadedInstance.get().value()).isEqualTo(
                new Instance(
                        instance.id(), instance.workspaceId(), instance.runtimeDefinitionId(), instance.name(),
                        instance.ports(), instance.dataDir(), instance.logDir(),
                        instance.desiredState(), java.util.Optional.empty(), instance.observedState()));
    }

    private static RuntimeDefinitionId insertRuntimeDefinitionRow(DataSource dataSource) throws Exception {
        RuntimeDefinitionId id = RuntimeDefinitionId.newId();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO runtime_definition (id, kind, source_json, config_schema_version) VALUES ("
                            + "'" + id.value() + "', 'RABBIT_MQ', '{}', 1)");
        }
        return id;
    }
}
