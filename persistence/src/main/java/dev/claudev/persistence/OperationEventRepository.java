package dev.claudev.persistence;

import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only — no update/delete method exists, the same enforcement-by-absence rule as
 * {@link AuditEntryRepository}. This is the durable half of the event bus (docs/ARCHITECTURE.md):
 * the same {@link OperationEvent} the scheduler emits live is appended here, so the row history
 * alone is enough to reconstruct what happened after a crash.
 */
@Repository
public class OperationEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public OperationEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(OperationEvent event) {
        jdbcTemplate.update(
                "INSERT INTO operation_events (operation_id, node_id, kind, message, timestamp) VALUES (?, ?, ?, ?, ?)",
                event.operationId().value().toString(),
                event.nodeId(),
                event.kind().name(),
                event.message(),
                event.timestamp().toString());
    }

    public List<OperationEvent> findByOperationId(OperationId operationId) {
        return jdbcTemplate.query(
                "SELECT * FROM operation_events WHERE operation_id = ? ORDER BY id",
                (rs, rowNum) -> mapRow(rs),
                operationId.value().toString());
    }

    private OperationEvent mapRow(ResultSet rs) throws SQLException {
        return new OperationEvent(
                new OperationId(UUID.fromString(rs.getString("operation_id"))),
                rs.getString("node_id"),
                OperationEvent.Kind.valueOf(rs.getString("kind")),
                rs.getString("message"),
                Instant.parse(rs.getString("timestamp")));
    }
}
