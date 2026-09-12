package dev.claudev.app;

import dev.claudev.adapter.redis.RedisConnectionProvider;
import dev.claudev.domain.Connection;
import dev.claudev.domain.ConnectionId;
import dev.claudev.persistence.ConnectionRepository;
import dev.claudev.persistence.MigrationRunner;
import dev.claudev.persistence.PragmaAppliedDataSource;
import dev.claudev.persistence.SqlitePragmaConfigurer;
import dev.claudev.provider.connection.ScanPage;
import dev.claudev.secret.dpapi.DpapiSecretStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real (not mocked): a real SQLite-backed {@link ConnectionRepository}, a real DPAPI-backed
 * {@link DpapiSecretStore} round trip for the password, and a real {@link RedisConnectionProvider}
 * against the same genuine Windows Redis test build {@code RedisConnectionProviderTest} uses. Skips
 * itself if that binary isn't present on this machine (see docs/REDIS_SCOPE.md).
 */
class SpringConnectionControlPortTest {

    private static final Path REDIS_SERVER_EXE =
            Path.of("D:\\dev\\workspace\\claudev-spike\\redis-test\\extracted\\redis-server.exe");
    private static final int PORT = 6397;
    private static Process serverProcess;

    @BeforeAll
    static void startRealRedisServer() throws IOException, InterruptedException {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(REDIS_SERVER_EXE),
                "Redis test binary not present on this machine — skipping (see docs/REDIS_SCOPE.md)");

        serverProcess = new ProcessBuilder(REDIS_SERVER_EXE.toString(), "--port", String.valueOf(PORT), "--bind", "127.0.0.1")
                .directory(REDIS_SERVER_EXE.getParent().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        boolean up = false;
        for (int attempt = 0; attempt < 30 && !up; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 200);
                up = true;
            } catch (IOException e) {
                Thread.sleep(200);
            }
        }
        Assumptions.assumeTrue(up, "real Redis server did not come up in time");
    }

    @AfterAll
    static void stopRealRedisServer() {
        if (serverProcess != null) {
            serverProcess.destroyForcibly();
        }
    }

    private static DataSource migratedDb(Path dbFile) {
        DriverManagerDataSource driver = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
        driver.setDriverClassName("org.sqlite.JDBC");
        DataSource pragmaApplied = new PragmaAppliedDataSource(driver, new SqlitePragmaConfigurer(5000));
        new MigrationRunner().migrate(pragmaApplied);
        return pragmaApplied;
    }

    private SpringConnectionControlPort newPort(Path tempDir) {
        DataSource dataSource = migratedDb(tempDir.resolve("conn-port.db"));
        ConnectionRepository repository = new ConnectionRepository(new JdbcTemplate(dataSource));
        return new SpringConnectionControlPort(repository, new RedisConnectionProvider(), new DpapiSecretStore());
    }

    @Test
    void connectPersistsAndListsARealConnection(@TempDir Path tempDir) {
        SpringConnectionControlPort port = newPort(tempDir);

        Connection connection = port.connectToRedis("127.0.0.1", PORT, Optional.empty());

        assertThat(port.listConnections()).extracting(Connection::id).containsExactly(connection.id());
    }

    @Test
    void connectingToAnUnreachableAddressThrowsAndPersistsNothing(@TempDir Path tempDir) {
        SpringConnectionControlPort port = newPort(tempDir);

        assertThatThrownBy(() -> port.connectToRedis("127.0.0.1", 1, Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(port.listConnections()).isEmpty();
    }

    @Test
    void passwordRoundTripsThroughRealDpapiAndReconnectsAfterASimulatedRestart(@TempDir Path tempDir) throws IOException, InterruptedException {
        // A real password-*requiring* server is needed here — pointing a password at a server with
        // none configured makes Lettuce's connect handshake send an unsolicited AUTH, which the real
        // server rejects, failing the whole connect() (a real behavior this test found while being
        // written, not assumed). A dedicated authenticated instance proves the actual AUTH path.
        int authPort = 6396;
        String password = "s3cret-" + UUID.randomUUID();
        Process authServer = new ProcessBuilder(
                REDIS_SERVER_EXE.toString(), "--port", String.valueOf(authPort), "--bind", "127.0.0.1", "--requirepass", password)
                .directory(REDIS_SERVER_EXE.getParent().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            waitForPort(authPort);

            DataSource dataSource = migratedDb(tempDir.resolve("conn-restart.db"));
            ConnectionRepository repository = new ConnectionRepository(new JdbcTemplate(dataSource));
            RedisConnectionProvider provider = new RedisConnectionProvider();
            DpapiSecretStore secretStore = new DpapiSecretStore();

            SpringConnectionControlPort firstProcess = new SpringConnectionControlPort(repository, provider, secretStore);
            Connection connection = firstProcess.connectToRedis("127.0.0.1", authPort, Optional.of(password));

            // A genuinely separate port instance sharing only the persisted repository — simulates a
            // restart where the in-memory "connectedInThisSession" set is empty again, forcing
            // ensureConnected() to resolve the DPAPI-stored password and re-authenticate for real.
            SpringConnectionControlPort secondProcess = new SpringConnectionControlPort(repository, provider, secretStore);
            String key = "claudev:test:reconnect:" + UUID.randomUUID();

            secondProcess.scanKeys(connection.id(), "", 10); // forces ensureConnected()/AUTH before any data exists
            provider.authorizeMutation(new dev.claudev.provider.connection.MutationRequest(
                    connection.id().value().toString(), "SET", key, "value-after-restart"));

            Optional<String> value = secondProcess.getValue(connection.id(), key);
            assertThat(value).contains("value-after-restart");
        } finally {
            authServer.destroyForcibly();
        }
    }

    private static void waitForPort(int port) throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                Thread.sleep(200);
            }
        }
        Assumptions.assumeTrue(false, "authenticated Redis test server did not come up in time");
    }

    @Test
    void deleteConnectionRemovesItAndClosesTheRealConnection(@TempDir Path tempDir) {
        SpringConnectionControlPort port = newPort(tempDir);
        Connection connection = port.connectToRedis("127.0.0.1", PORT, Optional.empty());

        port.deleteConnection(connection.id());

        assertThat(port.listConnections()).isEmpty();
    }

    @Test
    void scanAndGetValueWorkThroughTheRealPort(@TempDir Path tempDir) {
        SpringConnectionControlPort port = newPort(tempDir);
        Connection connection = port.connectToRedis("127.0.0.1", PORT, Optional.empty());

        String prefix = "claudev:test:port-scan:" + UUID.randomUUID() + ":";
        // The port's internal RedisConnectionProvider instance isn't exposed, so keys are seeded
        // through a short-lived direct connection to the same real server instead.
        RedisConnectionProvider seedProvider = new RedisConnectionProvider();
        String seedId = "seed-" + UUID.randomUUID();
        seedProvider.connect(new dev.claudev.provider.connection.ConnectOptions(seedId, "127.0.0.1", PORT, Optional.empty()));
        Set<String> inserted = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            String key = prefix + i;
            inserted.add(key);
            seedProvider.authorizeMutation(new dev.claudev.provider.connection.MutationRequest(seedId, "SET", key, "v" + i));
        }
        seedProvider.disconnect(seedId);

        Set<String> seen = new HashSet<>();
        String cursor = "";
        boolean complete = false;
        int guard = 0;
        while (!complete && guard++ < 50) {
            ScanPage page = port.scanKeys(connection.id(), cursor, 10);
            seen.addAll(page.keys().stream().filter(k -> k.startsWith(prefix)).toList());
            cursor = page.cursor();
            complete = page.complete();
        }
        assertThat(seen).containsAll(inserted);

        Optional<String> value = port.getValue(connection.id(), prefix + "0");
        assertThat(value).contains("v0");
    }
}
