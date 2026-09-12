package dev.claudev.adapter.rabbitmq;

import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.runtime.StartInstanceCommand;
import dev.claudev.provider.runtime.StartInstanceOutcome;
import dev.claudev.provider.runtime.StopInstanceCommand;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP10b: {@code RuntimeSource.Imported} — a user's own Erlang/RabbitMQ binaries, no download, no
 * checksum. Reuses the same spike-cached binaries as {@link RabbitMqRuntimeProviderTest} to stand
 * in for "a user's own install"; skips itself if that cache isn't present on this machine.
 */
class RabbitMqRuntimeProviderFromImportedTest {

    private static final Path SPIKE_ERLANG_HOME = Path.of("D:\\dev\\workspace\\claudev-spike\\otp27");
    private static final Path SPIKE_RABBITMQ_SBIN =
            Path.of("D:\\dev\\workspace\\claudev-spike\\rabbitmq\\rabbitmq_server-4.3.5\\sbin");

    @BeforeEach
    void requireSpikeCache() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(SPIKE_ERLANG_HOME.resolve("bin").resolve("erl.exe")),
                "WP6 spike cache not present on this machine — skipping");
        Assumptions.assumeTrue(Files.isRegularFile(SPIKE_RABBITMQ_SBIN.resolve("rabbitmq-server.bat")),
                "WP6 spike cache not present on this machine — skipping");
    }

    @Test
    void rejectsAnErlangHomeMissingErlExe(@TempDir Path tempDir) {
        assertThatThrownBy(() -> RabbitMqRuntimeProvider.fromImported(tempDir, SPIKE_RABBITMQ_SBIN))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("erl.exe not found");
    }

    @Test
    void rejectsARabbitmqSbinMissingTheServerScript(@TempDir Path tempDir) {
        assertThatThrownBy(() -> RabbitMqRuntimeProvider.fromImported(SPIKE_ERLANG_HOME, tempDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("rabbitmq-server.bat not found");
    }

    @Test
    void aValidImportedPairActuallySpawnsAndStopsARealNode(@TempDir Path tempDir) throws IOException {
        RabbitMqRuntimeProvider provider = RabbitMqRuntimeProvider.fromImported(SPIKE_ERLANG_HOME, SPIKE_RABBITMQ_SBIN);
        String instanceId = "imported-" + UUID.randomUUID();

        ProviderResult<StartInstanceOutcome> started = provider.start(new StartInstanceCommand(
                instanceId, "unused", tempDir.resolve("data"), tempDir.resolve("logs"),
                Set.of(6031, 26031), Map.of()));

        assertThat(started).isInstanceOf(ProviderResult.Ok.class);
        StartInstanceOutcome outcome = ((ProviderResult.Ok<StartInstanceOutcome>) started).value();
        assertThat(ProcessHandle.of(outcome.pid())).isPresent();

        provider.stop(new StopInstanceCommand(instanceId, outcome.pid(), outcome.processCreationTime(), outcome.instanceToken(), true));
        assertThat(ProcessHandle.of(outcome.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
}
