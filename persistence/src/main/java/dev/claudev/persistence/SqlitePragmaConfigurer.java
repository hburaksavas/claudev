package dev.claudev.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies the D11-mandated pragmas to a raw JDBC connection: WAL journal mode, foreign keys on,
 * and a tuned {@code busy_timeout} so concurrent reconciler-thread writes and UI-thread reads
 * tolerate real-time AV scanning of the DB file without surfacing an unrecovered
 * "database is locked" (docs/PERSISTENCE.md, Spike C).
 */
public final class SqlitePragmaConfigurer {

    private final int busyTimeoutMillis;

    public SqlitePragmaConfigurer(int busyTimeoutMillis) {
        this.busyTimeoutMillis = busyTimeoutMillis;
    }

    public void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
        }
    }
}
