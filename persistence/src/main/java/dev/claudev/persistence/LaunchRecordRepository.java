package dev.claudev.persistence;

import dev.claudev.domain.InstanceId;
import dev.claudev.domain.LaunchRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link #save} is the synchronous half of the D5 spawn ordering invariant (docs/PROCESS_SAFETY.md):
 * it must return only once the row is durably committed, so a crash immediately afterward can
 * never produce "process running but record not saved." A single {@code INSERT OR REPLACE} keeps
 * this one atomic statement rather than a delete-then-insert pair that could be interrupted between
 * the two.
 */
@Repository
public class LaunchRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public LaunchRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(InstanceId instanceId, LaunchRecord record) {
        jdbcTemplate.update(
                """
                INSERT OR REPLACE INTO launch_record (instance_id, pid, exe_path, exe_fingerprint_sha256,
                    process_creation_time, instance_token, job_handle_id, working_dir, command_hash, saved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                instanceId.value().toString(),
                record.pid(),
                record.exePath(),
                record.exeFingerprintSha256(),
                record.processCreationTime().toString(),
                record.instanceToken().toString(),
                record.jobHandleId(),
                record.workingDir(),
                record.commandHash(),
                Instant.now().toString());
    }

    public Optional<LaunchRecord> findByInstanceId(InstanceId instanceId) {
        return jdbcTemplate.query(
                        "SELECT * FROM launch_record WHERE instance_id = ?",
                        (rs, rowNum) -> mapRow(rs),
                        instanceId.value().toString())
                .stream()
                .findFirst();
    }

    /** No update method exists — a launch record is replaced wholesale (a new launch) or deleted (stopped), never edited in place. */
    public void delete(InstanceId instanceId) {
        jdbcTemplate.update("DELETE FROM launch_record WHERE instance_id = ?", instanceId.value().toString());
    }

    private LaunchRecord mapRow(ResultSet rs) throws SQLException {
        return new LaunchRecord(
                rs.getLong("pid"),
                rs.getString("exe_path"),
                rs.getString("exe_fingerprint_sha256"),
                Instant.parse(rs.getString("process_creation_time")),
                UUID.fromString(rs.getString("instance_token")),
                rs.getLong("job_handle_id"),
                rs.getString("working_dir"),
                rs.getString("command_hash"));
    }
}
