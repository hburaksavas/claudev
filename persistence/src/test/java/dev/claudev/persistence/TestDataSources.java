package dev.claudev.persistence;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

/** Builds a fresh, fully migrated {@link DataSource} against a given file — used to simulate "a new app process opens the same on-disk database." */
final class TestDataSources {

    private TestDataSources() {
    }

    static DataSource migrated(Path dbFile) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        DataSource pragmaApplied = new PragmaAppliedDataSource(dataSource, new SqlitePragmaConfigurer(5000));
        new MigrationRunner().migrate(pragmaApplied);
        return pragmaApplied;
    }
}
