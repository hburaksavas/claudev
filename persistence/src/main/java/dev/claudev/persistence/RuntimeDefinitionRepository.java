package dev.claudev.persistence;

import dev.claudev.domain.RuntimeDefinition;
import dev.claudev.domain.RuntimeDefinitionId;
import dev.claudev.domain.RuntimeKind;
import dev.claudev.domain.RuntimeSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code source_json} is hand-mapped (not Jackson-annotated on the sealed {@link RuntimeSource}
 * itself) to keep domain-core free of any serialization-library coupling, matching the pattern
 * already used by {@code OperationRepository} for {@code OperationDag} (see docs/PERSISTENCE.md).
 * Only {@link RuntimeSource.Imported} is round-tripped today — {@code Managed}/{@code System} are
 * not yet driven by any real caller (WP6/WP7 backlog).
 */
@Repository
public class RuntimeDefinitionRepository {

    private final JdbcTemplate jdbcTemplate;

    public RuntimeDefinitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(RuntimeDefinition definition) {
        jdbcTemplate.update(
                "INSERT INTO runtime_definition (id, kind, source_json, config_schema_version) VALUES (?, ?, ?, ?)",
                definition.id().value().toString(),
                definition.kind().name(),
                encodeSource(definition.source()),
                definition.configSchemaVersion());
    }

    public Optional<RuntimeDefinition> findById(RuntimeDefinitionId id) {
        return jdbcTemplate.query(
                        "SELECT * FROM runtime_definition WHERE id = ?",
                        (rs, rowNum) -> mapRow(rs),
                        id.value().toString())
                .stream()
                .findFirst();
    }

    private RuntimeDefinition mapRow(ResultSet rs) throws SQLException {
        return new RuntimeDefinition(
                new RuntimeDefinitionId(UUID.fromString(rs.getString("id"))),
                RuntimeKind.valueOf(rs.getString("kind")),
                decodeSource(rs.getString("source_json")),
                rs.getInt("config_schema_version"));
    }

    /** Only {@code Imported} is supported — {@code Managed}/{@code System} get a real wire format once WP6/WP7 has an actual caller to design it against. */
    private static String encodeSource(RuntimeSource source) {
        if (source instanceof RuntimeSource.Imported imported) {
            return "imported:" + imported.installPath();
        }
        throw new IllegalArgumentException("RuntimeSource " + source.getClass().getSimpleName() + " has no persisted encoding yet");
    }

    private static RuntimeSource decodeSource(String encoded) {
        String[] parts = encoded.split(":", 2);
        if ("imported".equals(parts[0])) {
            return new RuntimeSource.Imported(Path.of(parts[1]));
        }
        throw new IllegalStateException("unsupported persisted RuntimeSource kind: " + parts[0]);
    }
}
