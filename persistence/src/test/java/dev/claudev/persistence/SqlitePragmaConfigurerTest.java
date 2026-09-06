package dev.claudev.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class SqlitePragmaConfigurerTest {

    @Test
    void appliesWalModeAndBusyTimeout(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("claudev-test.db");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            new SqlitePragmaConfigurer(5000).apply(connection);

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA busy_timeout")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(5000);
            }
        }
    }
}
