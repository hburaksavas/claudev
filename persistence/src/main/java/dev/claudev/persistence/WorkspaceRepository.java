package dev.claudev.persistence;

import dev.claudev.domain.InstanceId;
import dev.claudev.domain.OwnerSid;
import dev.claudev.domain.PipelineId;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code instanceIds}/{@code pipelineIds} on the returned {@link Workspace} are derived by
 * querying the {@code instance}/{@code pipeline} tables by {@code workspace_id} — they are not
 * stored redundantly on the workspace row, so they can never drift out of sync with those tables.
 */
@Repository
public class WorkspaceRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Versioned<Workspace> insert(Workspace workspace) {
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, created_at, owner_sid, revision) VALUES (?, ?, ?, ?, 0)",
                workspace.id().value().toString(),
                workspace.name(),
                workspace.createdAt().toString(),
                workspace.ownerSid().value());
        return new Versioned<>(workspace, 0);
    }

    public Versioned<Workspace> update(Workspace workspace, long expectedRevision) {
        int updated = jdbcTemplate.update(
                "UPDATE workspace SET name = ?, owner_sid = ?, revision = revision + 1 "
                        + "WHERE id = ? AND revision = ?",
                workspace.name(),
                workspace.ownerSid().value(),
                workspace.id().value().toString(),
                expectedRevision);
        if (updated == 0) {
            throw new OptimisticLockException("workspace", workspace.id().value().toString(), expectedRevision);
        }
        return new Versioned<>(workspace, expectedRevision + 1);
    }

    public Optional<Versioned<Workspace>> findById(WorkspaceId id) {
        List<Versioned<Workspace>> rows = jdbcTemplate.query(
                "SELECT * FROM workspace WHERE id = ?",
                (rs, rowNum) -> mapRowWithoutChildren(rs),
                id.value().toString());
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Versioned<Workspace> row = rows.get(0);
        List<InstanceId> instanceIds = jdbcTemplate.query(
                "SELECT id FROM instance WHERE workspace_id = ?",
                (rs, rowNum) -> new InstanceId(UUID.fromString(rs.getString("id"))),
                id.value().toString());
        List<PipelineId> pipelineIds = jdbcTemplate.query(
                "SELECT id FROM pipeline WHERE workspace_id = ?",
                (rs, rowNum) -> new PipelineId(UUID.fromString(rs.getString("id"))),
                id.value().toString());

        Workspace populated = new Workspace(
                row.value().id(), row.value().name(), row.value().createdAt(),
                row.value().ownerSid(), instanceIds, pipelineIds);
        return Optional.of(new Versioned<>(populated, row.revision()));
    }

    private Versioned<Workspace> mapRowWithoutChildren(ResultSet rs) throws SQLException {
        Workspace workspace = new Workspace(
                new WorkspaceId(UUID.fromString(rs.getString("id"))),
                rs.getString("name"),
                Instant.parse(rs.getString("created_at")),
                new OwnerSid(rs.getString("owner_sid")),
                List.of(),
                List.of());
        return new Versioned<>(workspace, rs.getLong("revision"));
    }
}
