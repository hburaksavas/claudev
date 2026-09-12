package dev.claudev.adapter.redis;

import dev.claudev.provider.Ack;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.runtime.InstanceHealth;
import dev.claudev.provider.runtime.StartInstanceCommand;
import dev.claudev.provider.runtime.StartInstanceOutcome;
import dev.claudev.provider.runtime.StopInstanceCommand;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real (not mocked): spawns and kills an actual redis-server.exe via the live Win32 launcher, and pings it for real. */
class RedisRuntimeProviderTest {

    private static final Path REDIS_SERVER_EXE =
            Path.of("D:\\dev\\workspace\\claudev-spike\\redis-test\\extracted\\redis-server.exe");

    @BeforeEach
    void requireRedisTestBinary() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(REDIS_SERVER_EXE),
                "Redis test binary not present on this machine — skipping (see docs/REDIS_SCOPE.md)");
    }

    @Test
    void startsHealthChecksAndStopsARealRedisServer(@TempDir Path tempDir) throws IOException {
        RedisRuntimeProvider provider = RedisRuntimeProvider.fromImported(REDIS_SERVER_EXE);
        String instanceId = "test-" + UUID.randomUUID();
        int port = freePort();

        ProviderResult<StartInstanceOutcome> started = provider.start(new StartInstanceCommand(
                instanceId, "unused", tempDir.resolve("data"), tempDir.resolve("logs"), Set.of(port), Map.of()));

        assertThat(started).isInstanceOf(ProviderResult.Ok.class);
        StartInstanceOutcome outcome = ((ProviderResult.Ok<StartInstanceOutcome>) started).value();
        assertThat(ProcessHandle.of(outcome.pid())).isPresent();
        assertThat(outcome.boundPorts()).containsExactly(port);

        ProviderResult<InstanceHealth> health = provider.healthCheck(instanceId);
        assertThat(health).isInstanceOf(ProviderResult.Ok.class);
        assertThat(((ProviderResult.Ok<InstanceHealth>) health).value().state()).isEqualTo("running");

        ProviderResult<Ack> stopped = provider.stop(new StopInstanceCommand(
                instanceId, outcome.pid(), outcome.processCreationTime(), outcome.instanceToken(), true));
        assertThat(stopped).isInstanceOf(ProviderResult.Ok.class);

        waitUntilNotAlive(outcome.pid());
        assertThat(ProcessHandle.of(outcome.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();

        // Windows can briefly hold the exited process's log-file handles open past the moment
        // ProcessHandle.isAlive() reports false — the same class of race RabbitMQ's tests already
        // document. @TempDir's own (non-retrying) cleanup would otherwise fail; retry-delete here
        // first, mirroring the deleteWithRetry convention used by RunningProcessScannerTest et al.
        deleteRecursivelyWithRetry(tempDir);
    }

    @Test
    void startRejectsAnythingOtherThanExactlyOnePort(@TempDir Path tempDir) throws IOException {
        RedisRuntimeProvider provider = RedisRuntimeProvider.fromImported(REDIS_SERVER_EXE);

        ProviderResult<StartInstanceOutcome> result = provider.start(new StartInstanceCommand(
                "unused", "unused", tempDir.resolve("data"), tempDir.resolve("logs"), Set.of(), Map.of()));

        assertThat(result).isInstanceOf(ProviderResult.Err.class);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursivelyWithRetry(Path root) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // retried below
                    }
                });
                if (Files.notExists(root)) {
                    return;
                }
            } catch (IOException ignored) {
                // retried below
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void waitUntilNotAlive(long pid) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
