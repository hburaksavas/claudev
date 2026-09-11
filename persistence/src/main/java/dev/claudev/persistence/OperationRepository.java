package dev.claudev.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.claudev.domain.Operation;
import dev.claudev.domain.OperationDag;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.OperationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link Operation#events()} is always empty on rows returned from this repository — the
 * append-only event history is a separate 1-to-many relationship, fetched via
 * {@link OperationEventRepository} and combined by the caller, mirroring the same split already
 * used for {@code Instance}/{@code LaunchRecord}.
 *
 * <p>{@code kind}/{@code targets}/{@code dag} are immutable once inserted (docs/PERSISTENCE.md) —
 * only {@link #updateStatus} exists for mutation, and it touches {@code status}/{@code endedAt}
 * alone. {@code dag}/{@code targets} are encoded as JSON text columns; the mapping is hand-written
 * to/from plain {@code Map}/{@code List} shapes rather than letting Jackson reflect over the
 * domain-core record directly, so domain-core stays free of any Jackson coupling.
 */
@Repository
public class OperationRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public OperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Operation operation) {
        jdbcTemplate.update(
                """
                INSERT INTO operation (id, kind, targets_json, dag_json, status, started_at, ended_at, cancel_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                operation.id().value().toString(),
                operation.kind(),
                writeJson(operation.targets()),
                writeDag(operation.dag()),
                operation.status().name(),
                operation.startedAt().toString(),
                operation.endedAt().map(Instant::toString).orElse(null),
                operation.cancelToken().toString());
    }

    public void updateStatus(OperationId id, OperationStatus status, Optional<Instant> endedAt) {
        jdbcTemplate.update(
                "UPDATE operation SET status = ?, ended_at = ? WHERE id = ?",
                status.name(), endedAt.map(Instant::toString).orElse(null), id.value().toString());
    }

    public Optional<Operation> findById(OperationId id) {
        return jdbcTemplate.query(
                        "SELECT * FROM operation WHERE id = ?",
                        (rs, rowNum) -> mapRow(rs),
                        id.value().toString())
                .stream()
                .findFirst();
    }

    private Operation mapRow(ResultSet rs) throws SQLException {
        String endedAtRaw = rs.getString("ended_at");
        return new Operation(
                new OperationId(UUID.fromString(rs.getString("id"))),
                rs.getString("kind"),
                readTargets(rs.getString("targets_json")),
                readDag(rs.getString("dag_json")),
                OperationStatus.valueOf(rs.getString("status")),
                List.of(),
                Instant.parse(rs.getString("started_at")),
                endedAtRaw == null ? Optional.empty() : Optional.of(Instant.parse(endedAtRaw)),
                UUID.fromString(rs.getString("cancel_token")));
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize operation field", e);
        }
    }

    private static String writeDag(OperationDag dag) {
        return writeJson(Map.of("nodeIds", dag.nodeIds(), "dependsOn", dag.dependsOn()));
    }

    @SuppressWarnings("unchecked")
    private static List<String> readTargets(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize operation targets", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static OperationDag readDag(String json) {
        try {
            Map<String, Object> raw = OBJECT_MAPPER.readValue(json, Map.class);
            List<String> nodeIds = (List<String>) raw.get("nodeIds");
            Map<String, List<String>> dependsOn = (Map<String, List<String>>) raw.get("dependsOn");
            return new OperationDag(nodeIds, dependsOn);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize operation dag", e);
        }
    }
}
