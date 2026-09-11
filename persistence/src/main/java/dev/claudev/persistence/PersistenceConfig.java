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
 * Minimal M0 SQLite wiring: a single-file, user-space database under {@code ~/.claudev} (no admin
 * install path assumed), with {@link SqlitePragmaConfigurer}'s WAL/busy_timeout pragmas applied on
 * the first connection. Providing this bean makes Spring Boot's own {@code DataSourceAutoConfiguration}
 * back off (it is {@code @ConditionalOnMissingBean(DataSource.class)}), so no
 * {@code spring.datasource.url} property is needed.
 *
 * <p>Not yet a pooled/production DataSource (see docs/PERSISTENCE.md) — schema/migrations and a
 * proper connection pool are still M0 backlog. This exists so the application actually starts.
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public DataSource dataSource(@Value("${claudev.db-path:${user.home}/.claudev/claudev.db}") String dbPath) throws IOException, SQLException {
        Path path = Path.of(dbPath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + path);
        dataSource.setDriverClassName("org.sqlite.JDBC");

        try (Connection connection = dataSource.getConnection()) {
            new SqlitePragmaConfigurer(5000).apply(connection);
        }

        return dataSource;
    }
}
