package dev.claudev.persistence;

import dev.claudev.domain.Connection;
import dev.claudev.domain.ConnectionId;
import dev.claudev.domain.ConnectionKind;
import dev.claudev.domain.EnvironmentClass;
import dev.claudev.domain.RedisSafetyPolicy;
import dev.claudev.domain.SecretRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real SQLite — covers WP10a's new ConnectionRepository. */
class ConnectionRepositoryTest {

    @Test
    void remoteConnectionWithSecretRoundTripsThroughARealDatabase(@TempDir Path tempDir) {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("conn.db"));
        ConnectionRepository repository = new ConnectionRepository(new JdbcTemplate(dataSource));

        Connection connection = new Connection(
                ConnectionId.newId(),
                new ConnectionKind.Remote("127.0.0.1", 6379, false),
                EnvironmentClass.DEV,
                Optional.of(new SecretRef(UUID.randomUUID(), "secret-store-dpapi", "b64:opaque==")),
                RedisSafetyPolicy.restrictiveDefault());
        repository.insert(connection);

        Optional<Connection> found = repository.findById(connection.id());
        assertThat(found).contains(connection);
    }

    @Test
    void remoteConnectionWithoutSecretRoundTrips(@TempDir Path tempDir) {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("conn-nosecret.db"));
        ConnectionRepository repository = new ConnectionRepository(new JdbcTemplate(dataSource));

        Connection connection = new Connection(
                ConnectionId.newId(),
                new ConnectionKind.Remote("redis.example.com", 6380, true),
                EnvironmentClass.UNKNOWN,
                Optional.empty(),
                RedisSafetyPolicy.restrictiveDefault());
        repository.insert(connection);

        Optional<Connection> found = repository.findById(connection.id());
        assertThat(found).contains(connection);
    }

    @Test
    void findAllAndDeleteWork(@TempDir Path tempDir) {
        DataSource dataSource = TestDataSources.migrated(tempDir.resolve("conn-list.db"));
        ConnectionRepository repository = new ConnectionRepository(new JdbcTemplate(dataSource));

        Connection a = new Connection(ConnectionId.newId(), new ConnectionKind.Remote("a", 1, false), EnvironmentClass.DEV, Optional.empty(), RedisSafetyPolicy.restrictiveDefault());
        Connection b = new Connection(ConnectionId.newId(), new ConnectionKind.Remote("b", 2, false), EnvironmentClass.DEV, Optional.empty(), RedisSafetyPolicy.restrictiveDefault());
        repository.insert(a);
        repository.insert(b);

        assertThat(repository.findAll()).hasSize(2);

        repository.delete(a.id());
        assertThat(repository.findAll()).extracting(Connection::id).containsExactly(b.id());
    }
}
