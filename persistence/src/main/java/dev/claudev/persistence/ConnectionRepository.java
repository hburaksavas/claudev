package dev.claudev.persistence;

import dev.claudev.domain.Connection;
import dev.claudev.domain.ConnectionId;
import dev.claudev.domain.ConnectionKind;
import dev.claudev.domain.EnvironmentClass;
import dev.claudev.domain.RedisSafetyPolicy;
import dev.claudev.domain.SecretRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V1 scope only supports {@link ConnectionKind.Remote} (WP10a — a manual "point at my own Redis"
 * connection; no {@code Local} instance-backed connection exists yet, since no runtime provider
 * exposes a Redis instance to bind one to). {@code policy_json} is not yet individually
 * configurable — every row uses {@link RedisSafetyPolicy#restrictiveDefault()}, encoded as a fixed
 * marker rather than a real per-connection policy; this is a deliberate V1 simplification, not a
 * bug, matching {@link RuntimeDefinitionRepository}'s "only what has a real caller today" approach.
 */
@Repository
public class ConnectionRepository {

    private static final String POLICY_RESTRICTIVE_DEFAULT_MARKER = "restrictive-default";

    private final JdbcTemplate jdbcTemplate;

    public ConnectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Connection connection) {
        jdbcTemplate.update(
                "INSERT INTO connection (id, kind_json, environment_class, secret_ref_json, policy_json) VALUES (?, ?, ?, ?, ?)",
                connection.id().value().toString(),
                encodeKind(connection.kind()),
                connection.environmentClass().name(),
                connection.secretRef().map(ConnectionRepository::encodeSecretRef).orElse(null),
                POLICY_RESTRICTIVE_DEFAULT_MARKER);
    }

    public void delete(ConnectionId id) {
        jdbcTemplate.update("DELETE FROM connection WHERE id = ?", id.value().toString());
    }

    public Optional<Connection> findById(ConnectionId id) {
        return jdbcTemplate.query(
                        "SELECT * FROM connection WHERE id = ?",
                        (rs, rowNum) -> mapRow(rs),
                        id.value().toString())
                .stream()
                .findFirst();
    }

    public List<Connection> findAll() {
        return jdbcTemplate.query("SELECT * FROM connection", (rs, rowNum) -> mapRow(rs));
    }

    private Connection mapRow(ResultSet rs) throws SQLException {
        String secretRefJson = rs.getString("secret_ref_json");
        return new Connection(
                new ConnectionId(UUID.fromString(rs.getString("id"))),
                decodeKind(rs.getString("kind_json")),
                EnvironmentClass.valueOf(rs.getString("environment_class")),
                secretRefJson == null ? Optional.empty() : Optional.of(decodeSecretRef(secretRefJson)),
                RedisSafetyPolicy.restrictiveDefault());
    }

    /** Only {@code Remote} is supported today — see the class javadoc. */
    private static String encodeKind(ConnectionKind kind) {
        if (kind instanceof ConnectionKind.Remote remote) {
            return "remote:" + remote.host() + ":" + remote.port() + ":" + remote.tls();
        }
        throw new IllegalArgumentException("ConnectionKind " + kind.getClass().getSimpleName() + " has no persisted encoding yet");
    }

    private static ConnectionKind decodeKind(String encoded) {
        String[] parts = encoded.split(":", 4);
        if ("remote".equals(parts[0])) {
            return new ConnectionKind.Remote(parts[1], Integer.parseInt(parts[2]), Boolean.parseBoolean(parts[3]));
        }
        throw new IllegalStateException("unsupported persisted ConnectionKind: " + parts[0]);
    }

    private static String encodeSecretRef(SecretRef ref) {
        return ref.id() + "|" + ref.storeAdapter() + "|" + ref.opaqueHandle();
    }

    private static SecretRef decodeSecretRef(String encoded) {
        String[] parts = encoded.split("\\|", 3);
        return new SecretRef(UUID.fromString(parts[0]), parts[1], parts[2]);
    }
}
