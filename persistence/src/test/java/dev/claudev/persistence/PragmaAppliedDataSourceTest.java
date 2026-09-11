package dev.claudev.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class PragmaAppliedDataSourceTest {

    /**
     * Regression test for a bug found by actually running the app: applying pragmas once at
     * startup left every subsequent connection on the driver's default busy_timeout, while WAL
     * (stored in the file) still read back correctly — so the misconfiguration was invisible.
     */
    @Test
    void everyConnectionGetsTheConfiguredBusyTimeout(@TempDir Path tempDir) throws Exception {
        DriverManagerDataSource driverDataSource =
                new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("pragma-test.db"));
        driverDataSource.setDriverClassName("org.sqlite.JDBC");

        DataSource dataSource = new PragmaAppliedDataSource(driverDataSource, new SqlitePragmaConfigurer(5000));

        for (int attempt = 0; attempt < 3; attempt++) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA busy_timeout")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("connection #%d", attempt).isEqualTo(5000);
            }
        }
    }
}
