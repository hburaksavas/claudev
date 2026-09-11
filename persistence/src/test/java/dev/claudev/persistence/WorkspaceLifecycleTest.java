package dev.claudev.persistence;

import dev.claudev.domain.DesiredState;
import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.InstanceState;
import dev.claudev.domain.OwnerSid;
import dev.claudev.domain.PortSet;
import dev.claudev.domain.RuntimeDefinition;
import dev.claudev.domain.RuntimeDefinitionId;
import dev.claudev.domain.RuntimeKind;
import dev.claudev.domain.RuntimeSource;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real SQLite, not mocked — covers WP5's new WorkspaceRepository/InstanceRepository/RuntimeDefinitionRepository methods. */
class WorkspaceLifecycleTest {

    @Test
    void runtimeDefinitionRoundTripsThroughARealDatabase(@TempDir Path tempDir) {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("rd.db"));
        RuntimeDefinitionRepository repository = new RuntimeDefinitionRepository(new JdbcTemplate(dataSource));

        RuntimeDefinition definition = new RuntimeDefinition(
                RuntimeDefinitionId.newId(), RuntimeKind.DUMMY,
                new RuntimeSource.Imported(Path.of("C:\\claudev\\dummy")), 1);
        repository.insert(definition);

        Optional<RuntimeDefinition> found = repository.findById(definition.id());
        assertThat(found).contains(definition);
    }

    @Test
    void findAllListsEveryWorkspace(@TempDir Path tempDir) {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("ws.db"));
        WorkspaceRepository repository = new WorkspaceRepository(new JdbcTemplate(dataSource));

        Workspace a = newWorkspace("workspace-a");
        Workspace b = newWorkspace("workspace-b");
        repository.insert(a);
        repository.insert(b);

        assertThat(repository.findAll().stream().map(v -> v.value().id())).containsExactlyInAnyOrder(a.id(), b.id());
    }

    @Test
    void deletingAWorkspaceCascadesInstancesAndLaunchRecords(@TempDir Path tempDir) {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("cascade.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        InstanceRepository instanceRepository = new InstanceRepository(jdbcTemplate);
        LaunchRecordRepository launchRecordRepository = new LaunchRecordRepository(jdbcTemplate);
        RuntimeDefinitionRepository runtimeDefinitionRepository = new RuntimeDefinitionRepository(jdbcTemplate);

        RuntimeDefinition definition = new RuntimeDefinition(
                RuntimeDefinitionId.newId(), RuntimeKind.DUMMY,
                new RuntimeSource.Imported(Path.of("C:\\claudev\\dummy")), 1);
        runtimeDefinitionRepository.insert(definition);

        Workspace workspace = newWorkspace("to-delete");
        workspaceRepository.insert(workspace);

        Instance instance = new Instance(
                InstanceId.newId(), workspace.id(), definition.id(), "dummy-1",
                PortSet.none(), tempDir.resolve("data"), tempDir.resolve("logs"),
                DesiredState.STOPPED, Optional.empty(), InstanceState.STOPPED);
        instanceRepository.insert(instance);
        launchRecordRepository.save(instance.id(), new dev.claudev.domain.LaunchRecord(
                1234, "C:\\ping.exe", "deadbeef", Instant.now(), java.util.UUID.randomUUID(), 1, "C:\\", "hash"));

        workspaceRepository.delete(workspace.id());

        assertThat(workspaceRepository.findById(workspace.id())).isEmpty();
        assertThat(instanceRepository.findById(instance.id())).isEmpty();
        assertThat(launchRecordRepository.findByInstanceId(instance.id())).isEmpty();
    }

    @Test
    void deletingAnInstanceRemovesOnlyThatRow(@TempDir Path tempDir) {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("inst-del.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        InstanceRepository instanceRepository = new InstanceRepository(jdbcTemplate);
        RuntimeDefinitionRepository runtimeDefinitionRepository = new RuntimeDefinitionRepository(jdbcTemplate);

        RuntimeDefinition definition = new RuntimeDefinition(
                RuntimeDefinitionId.newId(), RuntimeKind.DUMMY,
                new RuntimeSource.Imported(Path.of("C:\\claudev\\dummy")), 1);
        runtimeDefinitionRepository.insert(definition);
        Workspace workspace = newWorkspace("keep-me");
        workspaceRepository.insert(workspace);

        Instance instance = new Instance(
                InstanceId.newId(), workspace.id(), definition.id(), "dummy-1",
                PortSet.none(), tempDir.resolve("data"), tempDir.resolve("logs"),
                DesiredState.STOPPED, Optional.empty(), InstanceState.STOPPED);
        instanceRepository.insert(instance);

        instanceRepository.delete(instance.id());

        assertThat(instanceRepository.findById(instance.id())).isEmpty();
        assertThat(workspaceRepository.findById(workspace.id())).isPresent();
    }

    private static Workspace newWorkspace(String name) {
        return new Workspace(WorkspaceId.newId(), name, Instant.now(), new OwnerSid("S-1-5-21-1-2-3-1001"), List.of(), List.of());
    }
}
