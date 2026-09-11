package dev.claudev.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * SQLite wiring: a single-file, user-space database under {@code ~/.claudev} (no admin install
 * path assumed), with {@link SqlitePragmaConfigurer}'s WAL/busy_timeout pragmas applied on every
 * connection and {@link MigrationRunner} bringing the schema up to date before the bean is
 * returned — so every other bean that depends on this {@code DataSource} can assume the schema
 * already exists. Providing this bean makes Spring Boot's own {@code DataSourceAutoConfiguration}
 * back off (it is {@code @ConditionalOnMissingBean(DataSource.class)}), so no
 * {@code spring.datasource.url} property is needed.
 *
 * <p>Not yet a pooled/production DataSource (see docs/PERSISTENCE.md) — this is a plain
 * {@code DriverManagerDataSource}, adequate for a single-process embedded database but not for
 * meaningful concurrent load.
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public DataSource dataSource(@Value("${claudev.db-path:${user.home}/.claudev/claudev.db}") String dbPath) throws IOException, SQLException {
        Path path = Path.of(dbPath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        DriverManagerDataSource driverDataSource = new DriverManagerDataSource("jdbc:sqlite:" + path);
        driverDataSource.setDriverClassName("org.sqlite.JDBC");

        // Pragmas must be applied per connection, not once here — busy_timeout and foreign_keys
        // are per-connection settings (see PragmaAppliedDataSource).
        DataSource dataSource = new PragmaAppliedDataSource(driverDataSource, new SqlitePragmaConfigurer(5000));

        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(5)) {
                throw new IllegalStateException("SQLite connection is not valid: " + path);
            }
        }

        new MigrationRunner().migrate(dataSource);

        return dataSource;
    }
}
