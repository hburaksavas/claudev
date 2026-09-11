package dev.claudev.persistence;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Applies {@link SqlitePragmaConfigurer} to <em>every</em> connection handed out, not just the
 * first one.
 *
 * <p>This distinction is load-bearing rather than cosmetic: {@code journal_mode=WAL} is stored in
 * the database file and survives across connections, but {@code busy_timeout} and
 * {@code foreign_keys} are per-connection settings that silently revert to the driver's defaults
 * on every new connection. Configuring them once at startup produces a database that looks
 * correctly configured (WAL reads back fine) while the lock-contention tolerance the design
 * actually depends on is not in effect — see docs/PERSISTENCE.md.
 */
public final class PragmaAppliedDataSource extends AbstractDataSource {

    private final DataSource delegate;
    private final SqlitePragmaConfigurer configurer;

    public PragmaAppliedDataSource(DataSource delegate, SqlitePragmaConfigurer configurer) {
        this.delegate = delegate;
        this.configurer = configurer;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return configured(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return configured(delegate.getConnection(username, password));
    }

    private Connection configured(Connection connection) throws SQLException {
        try {
            configurer.apply(connection);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
