package dev.claudev.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationRunnerTest {

    @Test
    void appliesTheInitialSchemaAndIsIdempotent(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = sqliteDataSource(tempDir.resolve("migration-test.db"));

        new MigrationRunner().migrate(dataSource);
        assertThat(appliedVersions(dataSource)).containsExactly(1);
        assertThat(tableExists(dataSource, "workspace")).isTrue();
        assertThat(tableExists(dataSource, "audit_entry")).isTrue();

        // Running again must not fail, re-apply, or duplicate the schema_version row.
        new MigrationRunner().migrate(dataSource);
        assertThat(appliedVersions(dataSource)).containsExactly(1);
    }

    @Test
    void rejectsAMigrationWhoseAppliedChecksumNoLongerMatches(@TempDir Path tempDir) throws Exception {
        DataSource dataSource = sqliteDataSource(tempDir.resolve("checksum-test.db"));
        new MigrationRunner().migrate(dataSource);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE schema_version SET checksum = 'tampered' WHERE version = 1");
        }

        assertThatThrownBy(() -> new MigrationRunner().migrate(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
    }

    private static DataSource sqliteDataSource(Path dbFile) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        return dataSource;
    }

    private static java.util.List<Integer> appliedVersions(DataSource dataSource) throws SQLException {
        java.util.List<Integer> versions = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version FROM schema_version ORDER BY version")) {
            while (rs.next()) {
                versions.add(rs.getInt(1));
            }
        }
        return versions;
    }

    private static boolean tableExists(DataSource dataSource, String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
