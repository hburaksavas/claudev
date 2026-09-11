package dev.claudev.persistence;

import dev.claudev.domain.AuditEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only by construction: this class has no update or delete method, matching
 * docs/PERSISTENCE.md's rule that the absence of the method is the enforcement, not a convention
 * callers are trusted to follow.
 */
@Repository
public class AuditEntryRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditEntryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(AuditEntry entry) {
        jdbcTemplate.update(
                "INSERT INTO audit_entry (id, actor, action, target, timestamp, result, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                entry.id().toString(),
                entry.actor(),
                entry.action(),
                entry.target(),
                entry.timestamp().toString(),
                entry.result(),
                entry.detail());
    }

    public List<AuditEntry> findMostRecent(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM audit_entry ORDER BY timestamp DESC LIMIT ?",
                (rs, rowNum) -> mapRow(rs),
                limit);
    }

    private AuditEntry mapRow(ResultSet rs) throws SQLException {
        return new AuditEntry(
                UUID.fromString(rs.getString("id")),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target"),
                Instant.parse(rs.getString("timestamp")),
                rs.getString("result"),
                rs.getString("detail"));
    }
}
