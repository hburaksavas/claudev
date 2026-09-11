package dev.claudev.adapter.redis;

import dev.claudev.provider.Ack;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.connection.BulkMutationRequest;
import dev.claudev.provider.connection.ConnectOptions;
import dev.claudev.provider.connection.MutationPreview;
import dev.claudev.provider.connection.MutationRequest;
import dev.claudev.provider.connection.ScanPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real (not mocked): connects to an actual {@code redis-server.exe} — a genuine Windows build of
 * upstream Redis 5.0.14.1 (the tporadowski/redis community fork; see docs/REDIS_SCOPE.md on why
 * this is test-only infrastructure and never bundled/managed by the adapter itself, unlike
 * {@code adapter-rabbitmq}). Skips itself when the cache isn't present on this machine, same pattern
 * as {@code RabbitMqRuntimeProviderTest}.
 */
class RedisConnectionProviderTest {

    private static final Path REDIS_SERVER_EXE =
            Path.of("D:\\dev\\workspace\\claudev-spike\\redis-test\\extracted\\redis-server.exe");
    private static final int PORT = 6398;

    private static Process serverProcess;
    private final RedisConnectionProvider provider = new RedisConnectionProvider();

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
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 200);
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

    private String connectionId;

    private String connect() {
        connectionId = "test-" + UUID.randomUUID();
        ProviderResult<Ack> result = provider.connect(new ConnectOptions(connectionId, "127.0.0.1", PORT, Optional.empty()));
        assertThat(result).isInstanceOf(ProviderResult.Ok.class);
        return connectionId;
    }

    @AfterEach
    void disconnect() {
        if (connectionId != null) {
            provider.disconnect(connectionId);
        }
    }

    @Test
    void connectingToAnUnreachableHostFails() {
        ProviderResult<Ack> result = provider.connect(new ConnectOptions("unreachable", "127.0.0.1", 1, Optional.empty()));
        assertThat(result).isInstanceOf(ProviderResult.Err.class);
    }

    @Test
    void setThenGetRoundTripsThroughARealServer() {
        String id = connect();
        String key = "claudev:test:" + UUID.randomUUID();

        ProviderResult<Ack> set = provider.authorizeMutation(new MutationRequest(id, "SET", key, "hello"));
        assertThat(set).isInstanceOf(ProviderResult.Ok.class);

        ProviderResult<String> get = provider.getString(id, key);
        assertThat(get).isInstanceOf(ProviderResult.Ok.class);
        assertThat(((ProviderResult.Ok<String>) get).value()).isEqualTo("hello");
    }

    @Test
    void getMissingKeyReturnsNotFound() {
        String id = connect();
        ProviderResult<String> result = provider.getString(id, "claudev:test:does-not-exist:" + UUID.randomUUID());
        assertThat(result).isInstanceOf(ProviderResult.Err.class);
    }

    @Test
    void appendExpireAndPersistWorkAgainstARealServer() {
        String id = connect();
        String key = "claudev:test:" + UUID.randomUUID();

        assertThat(provider.authorizeMutation(new MutationRequest(id, "SET", key, "a"))).isInstanceOf(ProviderResult.Ok.class);
        assertThat(provider.authorizeMutation(new MutationRequest(id, "APPEND", key, "b"))).isInstanceOf(ProviderResult.Ok.class);
        assertThat(((ProviderResult.Ok<String>) provider.getString(id, key)).value()).isEqualTo("ab");

        assertThat(provider.authorizeMutation(new MutationRequest(id, "EXPIRE", key, "100"))).isInstanceOf(ProviderResult.Ok.class);
        assertThat(provider.authorizeMutation(new MutationRequest(id, "PERSIST", key, ""))).isInstanceOf(ProviderResult.Ok.class);

        assertThat(provider.authorizeMutation(new MutationRequest(id, "DEL", key, ""))).isInstanceOf(ProviderResult.Ok.class);
        assertThat(provider.getString(id, key)).isInstanceOf(ProviderResult.Err.class);
    }

    @Test
    void unsupportedOperationIsRejectedByTheAllowList() {
        String id = connect();
        ProviderResult<Ack> result = provider.authorizeMutation(new MutationRequest(id, "FLUSHALL", "irrelevant", ""));
        assertThat(result).isInstanceOf(ProviderResult.Err.class);
    }

    @Test
    void scanPagesThroughRealKeysAndEventuallyCompletes() {
        String id = connect();
        String prefix = "claudev:test:scan:" + UUID.randomUUID() + ":";
        Set<String> inserted = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            String key = prefix + i;
            inserted.add(key);
            provider.authorizeMutation(new MutationRequest(id, "SET", key, "v"));
        }

        Set<String> seen = new HashSet<>();
        String cursor = "";
        boolean complete = false;
        int guard = 0;
        while (!complete && guard++ < 50) {
            ProviderResult<ScanPage> page = provider.scan(id, cursor, 10);
            assertThat(page).isInstanceOf(ProviderResult.Ok.class);
            ScanPage scanPage = ((ProviderResult.Ok<ScanPage>) page).value();
            seen.addAll(scanPage.keys().stream().filter(k -> k.startsWith(prefix)).toList());
            cursor = scanPage.cursor();
            complete = scanPage.complete();
        }

        assertThat(seen).containsAll(inserted);
        for (String key : inserted) {
            provider.authorizeMutation(new MutationRequest(id, "DEL", key, ""));
        }
    }

    @Test
    void bulkDeletePreviewAndCommitRemovesExactlyTheMatchedKeys() {
        String id = connect();
        String prefix = "claudev:test:bulk:" + UUID.randomUUID() + ":";
        for (int i = 0; i < 5; i++) {
            provider.authorizeMutation(new MutationRequest(id, "SET", prefix + i, "v"));
        }

        ProviderResult<MutationPreview> preview = provider.prepareMutation(new BulkMutationRequest(id, "DEL", prefix + "*"));
        assertThat(preview).isInstanceOf(ProviderResult.Ok.class);
        MutationPreview mutationPreview = ((ProviderResult.Ok<MutationPreview>) preview).value();
        assertThat(mutationPreview.estimatedAffectedKeys()).isEqualTo(5);

        ProviderResult<Ack> committed = provider.commitMutation(mutationPreview.mutationToken());
        assertThat(committed).isInstanceOf(ProviderResult.Ok.class);

        for (int i = 0; i < 5; i++) {
            assertThat(provider.getString(id, prefix + i)).isInstanceOf(ProviderResult.Err.class);
        }
    }

    @Test
    void bulkDeleteCommitIsRejectedIfKeysChangedSincePreview() {
        String id = connect();
        String prefix = "claudev:test:bulk-race:" + UUID.randomUUID() + ":";
        provider.authorizeMutation(new MutationRequest(id, "SET", prefix + "0", "v"));

        ProviderResult<MutationPreview> preview = provider.prepareMutation(new BulkMutationRequest(id, "DEL", prefix + "*"));
        MutationPreview mutationPreview = ((ProviderResult.Ok<MutationPreview>) preview).value();

        // A key matching the same pattern appears after preview but before commit — TOCTOU.
        provider.authorizeMutation(new MutationRequest(id, "SET", prefix + "1", "v"));

        ProviderResult<Ack> committed = provider.commitMutation(mutationPreview.mutationToken());
        assertThat(committed).isInstanceOf(ProviderResult.Err.class);

        provider.authorizeMutation(new MutationRequest(id, "DEL", prefix + "0", ""));
        provider.authorizeMutation(new MutationRequest(id, "DEL", prefix + "1", ""));
    }

    @Test
    void commitMutationIsSingleUse() {
        String id = connect();
        String key = "claudev:test:single-use:" + UUID.randomUUID();
        provider.authorizeMutation(new MutationRequest(id, "SET", key, "v"));

        ProviderResult<MutationPreview> preview = provider.prepareMutation(new BulkMutationRequest(id, "DEL", key));
        String token = ((ProviderResult.Ok<MutationPreview>) preview).value().mutationToken();

        assertThat(provider.commitMutation(token)).isInstanceOf(ProviderResult.Ok.class);
        assertThat(provider.commitMutation(token)).isInstanceOf(ProviderResult.Err.class);
    }

    @Test
    void everyOperationAgainstAnUnconnectedIdIsRejected() {
        String unknownId = "never-connected-" + UUID.randomUUID();
        assertThat(provider.scan(unknownId, "", 10)).isInstanceOf(ProviderResult.Err.class);
        assertThat(provider.getString(unknownId, "k")).isInstanceOf(ProviderResult.Err.class);
        assertThat(provider.authorizeMutation(new MutationRequest(unknownId, "SET", "k", "v"))).isInstanceOf(ProviderResult.Err.class);
    }
}
