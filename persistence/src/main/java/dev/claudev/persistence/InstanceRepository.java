package dev.claudev.persistence;

import dev.claudev.domain.DesiredState;
import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.InstanceState;
import dev.claudev.domain.PortSet;
import dev.claudev.domain.RuntimeDefinitionId;
import dev.claudev.domain.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link Instance#launchRecord()} is always {@link Optional#empty()} on rows returned from this
 * repository — the {@code launch_record} table is a separate 1-to-0/1 relationship, fetched via
 * {@link LaunchRecordRepository} and combined by the caller when needed, not joined here.
 */
@Repository
public class InstanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Versioned<Instance> insert(Instance instance) {
        jdbcTemplate.update(
                """
                INSERT INTO instance (id, workspace_id, runtime_definition_id, name,
                    requested_ports, bound_ports, data_dir, log_dir, desired_state, observed_state, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                instance.id().value().toString(),
                instance.workspaceId().value().toString(),
                instance.runtimeDefinitionId().value().toString(),
                instance.name(),
                joinPorts(instance.ports().requested()),
                joinPorts(instance.ports().bound()),
                instance.dataDir().toString(),
                instance.logDir().toString(),
                instance.desiredState().name(),
                instance.observedState().name());
        return new Versioned<>(instance, 0);
    }

    public Versioned<Instance> update(Instance instance, long expectedRevision) {
        int updated = jdbcTemplate.update(
                """
                UPDATE instance SET name = ?, requested_ports = ?, bound_ports = ?,
                    desired_state = ?, observed_state = ?, revision = revision + 1
                WHERE id = ? AND revision = ?
                """,
                instance.name(),
                joinPorts(instance.ports().requested()),
                joinPorts(instance.ports().bound()),
                instance.desiredState().name(),
                instance.observedState().name(),
                instance.id().value().toString(),
                expectedRevision);
        if (updated == 0) {
            throw new OptimisticLockException("instance", instance.id().value().toString(), expectedRevision);
        }
        return new Versioned<>(instance, expectedRevision + 1);
    }

    public Optional<Versioned<Instance>> findById(InstanceId id) {
        return jdbcTemplate.query(
                        "SELECT * FROM instance WHERE id = ?",
                        (rs, rowNum) -> mapRow(rs),
                        id.value().toString())
                .stream()
                .findFirst();
    }

    /** Every instance across every workspace — what the reconciler's startup/timer pass sweeps over (docs/PROCESS_SAFETY.md). */
    public List<Versioned<Instance>> findAll() {
        return jdbcTemplate.query("SELECT * FROM instance", (rs, rowNum) -> mapRow(rs));
    }

    public List<Versioned<Instance>> findByWorkspaceId(WorkspaceId workspaceId) {
        return jdbcTemplate.query(
                "SELECT * FROM instance WHERE workspace_id = ?",
                (rs, rowNum) -> mapRow(rs),
                workspaceId.value().toString());
    }

    private Versioned<Instance> mapRow(ResultSet rs) throws SQLException {
        Instance instance = new Instance(
                new InstanceId(UUID.fromString(rs.getString("id"))),
                new WorkspaceId(UUID.fromString(rs.getString("workspace_id"))),
                new RuntimeDefinitionId(UUID.fromString(rs.getString("runtime_definition_id"))),
                rs.getString("name"),
                new PortSet(splitPorts(rs.getString("requested_ports")), splitPorts(rs.getString("bound_ports"))),
                Path.of(rs.getString("data_dir")),
                Path.of(rs.getString("log_dir")),
                DesiredState.valueOf(rs.getString("desired_state")),
                Optional.empty(),
                InstanceState.valueOf(rs.getString("observed_state")));
        return new Versioned<>(instance, rs.getLong("revision"));
    }

    private static String joinPorts(Set<Integer> ports) {
        return ports.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Set<Integer> splitPorts(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(Integer::parseInt).collect(Collectors.toSet());
    }
}
