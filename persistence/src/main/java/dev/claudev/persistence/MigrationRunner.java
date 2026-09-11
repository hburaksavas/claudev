package dev.claudev.persistence;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, self-contained ordered-SQL migration runner. Chosen over Flyway after checking Maven
 * Central directly: as of this writing there is no {@code flyway-database-sqlite} artifact, and no
 * other published Flyway module claims SQLite support — Flyway is not an option for this database,
 * not merely a heavier one.
 *
 * <p>Migrations are classpath resources named {@code V<version>__<description>.sql} under
 * {@code db/migration/}, applied in ascending version order, each in its own transaction, tracked
 * in a {@code schema_version} table this class creates on first use. A migration whose file
 * content no longer matches the checksum recorded when it was applied fails the run loudly —
 * editing an already-applied migration is a bug, not a valid schema change; add a new migration
 * instead.
 */
public final class MigrationRunner {

    private static final Pattern FILENAME_PATTERN = Pattern.compile("V(\\d+)__(.+)\\.sql");
    private static final String MIGRATIONS_LOCATION = "classpath*:db/migration/*.sql";

    public void migrate(DataSource dataSource) {
        List<Migration> migrations = discoverMigrations();
        try (Connection connection = dataSource.getConnection()) {
            ensureSchemaVersionTable(connection);
            for (Migration migration : migrations) {
                apply(connection, migration);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Migration failed", e);
        }
    }

    private void ensureSchemaVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        description TEXT NOT NULL,
                        checksum TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        String existingChecksum = readAppliedChecksum(connection, migration.version());
        if (existingChecksum != null) {
            if (!existingChecksum.equals(migration.checksum())) {
                throw new IllegalStateException(
                        "Migration V" + migration.version() + " (" + migration.description() + ") has "
                                + "changed since it was applied (checksum mismatch). Never edit an "
                                + "already-applied migration; add a new one instead.");
            }
            return;
        }

        connection.setAutoCommit(false);
        try {
            // Verified directly against the driver (not assumed): xerial's sqlite-jdbc silently
            // executes only the FIRST statement of a ";"-joined multi-statement script passed to a
            // single execute() call and drops the rest without error — so each statement is
            // executed individually via one reused Statement object.
            try (Statement statement = connection.createStatement()) {
                for (String statementSql : splitStatements(migration.sql())) {
                    statement.execute(statementSql);
                }
            }
            recordApplied(connection, migration);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw new IllegalStateException("Migration V" + migration.version() + " failed", e);
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private String readAppliedChecksum(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT checksum FROM schema_version WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void recordApplied(Connection connection, Migration migration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_version (version, description, checksum, applied_at) VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.setString(3, migration.checksum());
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private List<Migration> discoverMigrations() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(MIGRATIONS_LOCATION);
            List<Migration> migrations = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                Matcher matcher = FILENAME_PATTERN.matcher(filename);
                if (!matcher.matches()) {
                    throw new IllegalStateException(
                            "Migration file does not match V<version>__<description>.sql: " + filename);
                }
                int version = Integer.parseInt(matcher.group(1));
                String description = matcher.group(2).replace('_', ' ');
                String sql = readAll(resource);
                migrations.add(new Migration(version, description, sql, sha256(sql)));
            }
            migrations.sort((a, b) -> Integer.compare(a.version(), b.version()));
            validateNoDuplicateVersions(migrations);
            return migrations;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover migrations", e);
        }
    }

    private void validateNoDuplicateVersions(List<Migration> migrations) {
        for (int i = 1; i < migrations.size(); i++) {
            if (migrations.get(i).version() == migrations.get(i - 1).version()) {
                throw new IllegalStateException("Duplicate migration version: V" + migrations.get(i).version());
            }
        }
    }

    private static String readAll(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Strips {@code --} line comments first, then splits the remainder on statement-terminating
     * semicolons. Comments are removed <em>before</em> splitting specifically because a semicolon
     * can legitimately appear inside comment prose (a migration file documenting "see
     * docs/MILESTONES.md WP4/WP7/WP8); their tables..." is real content in this codebase) — a
     * naive split-first approach breaks mid-comment on that semicolon, and the resulting fragment
     * (comment tail + the next real statement concatenated) is not valid SQL.
     *
     * <p>Passing a comment-only fragment to xerial's sqlite-jdbc {@code Statement.execute()} was
     * separately, directly confirmed (isolated repro, not guessed) to throw
     * {@code "The prepared statement has been finalized"} — a real driver bug in how it handles a
     * SQL string that compiles to no actual statement, which then poisons every subsequent
     * {@code execute()} call reusing the same {@code Statement} object. Stripping comments first
     * also means no fragment is ever comment-only to begin with, so that failure mode cannot occur
     * here regardless.
     *
     * <p>Sufficient for this project's DDL-only migrations; does not attempt to handle a
     * {@code --} or {@code ;} embedded inside a string literal — none of the current migrations
     * contain one.
     */
    private static List<String> splitStatements(String sql) {
        String withoutComments = sql.lines()
                .map(MigrationRunner::stripLineComment)
                .collect(java.util.stream.Collectors.joining("\n"));

        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    private static String stripLineComment(String line) {
        int index = line.indexOf("--");
        return index >= 0 ? line.substring(0, index) : line;
    }

    private record Migration(int version, String description, String sql, String checksum) {
    }
}
