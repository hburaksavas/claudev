package dev.claudev.adapter.rabbitmq;

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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real (not mocked): spawns actual RabbitMQ nodes through this adapter's own Job-Object-based
 * launcher — reusing the exact Erlang/OTP 27.3.4.17 + RabbitMQ 4.3.5 binaries the WP6 feasibility
 * spike downloaded and verified (see docs/RABBITMQ_RUNTIME.md), cached outside the repo at
 * {@code D:\dev\workspace\claudev-spike} so this test doesn't re-download ~250MB. Skips itself
 * (rather than failing) when that cache isn't present, since it's a spike artifact, not a build
 * output — same pattern as the platform-windows OS assumptions.
 *
 * <p>This specifically proves something the manual spike (which used plain {@code Start-Process},
 * no Job Object) did not: that EPMD survives node stop/kill even when the node runs inside <em>this
 * adapter's own</em> {@code KILL_ON_JOB_CLOSE} Job Object — the actual risk {@link EpmdSupervisor}
 * exists to avoid, not just the Start-Process case.
 */
class RabbitMqRuntimeProviderTest {

    private static final Path SPIKE_ERLANG_HOME = Path.of("D:\\dev\\workspace\\claudev-spike\\otp27");
    private static final Path SPIKE_RABBITMQ_SBIN =
            Path.of("D:\\dev\\workspace\\claudev-spike\\rabbitmq\\rabbitmq_server-4.3.5\\sbin");

    @BeforeEach
    void requireSpikeCache() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(SPIKE_ERLANG_HOME.resolve("bin").resolve("erl.exe")),
                "WP6 spike cache not present on this machine — skipping (see RABBITMQ_RUNTIME.md)");
        Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(SPIKE_RABBITMQ_SBIN.resolve("rabbitmq-server.bat")),
                "WP6 spike cache not present on this machine — skipping (see RABBITMQ_RUNTIME.md)");
    }

    private RabbitMqRuntimeProvider newProvider() {
        return new RabbitMqRuntimeProvider(new RabbitMqInstallation(SPIKE_ERLANG_HOME, SPIKE_RABBITMQ_SBIN));
    }

    @Test
    void startsHealthChecksAndStopsARealNode(@TempDir Path tempDir) {
        RabbitMqRuntimeProvider provider = newProvider();
        String instanceId = "test-" + UUID.randomUUID();

        ProviderResult<StartInstanceOutcome> started = provider.start(new StartInstanceCommand(
                instanceId, "unused", tempDir.resolve("data"), tempDir.resolve("logs"),
                Set.of(6001, 26001), Map.of()));

        assertThat(started).isInstanceOf(ProviderResult.Ok.class);
        StartInstanceOutcome outcome = ((ProviderResult.Ok<StartInstanceOutcome>) started).value();
        assertThat(ProcessHandle.of(outcome.pid())).isPresent();

        ProviderResult<InstanceHealth> health = provider.healthCheck(instanceId);
        assertThat(health).isInstanceOf(ProviderResult.Ok.class);
        assertThat(((ProviderResult.Ok<InstanceHealth>) health).value().state()).isEqualTo("running");

        ProviderResult<dev.claudev.provider.Ack> stopped = provider.stop(new StopInstanceCommand(
                instanceId, outcome.pid(), outcome.processCreationTime(), outcome.instanceToken(), true));
        assertThat(stopped).isInstanceOf(ProviderResult.Ok.class);
        assertThat(ProcessHandle.of(outcome.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void stoppingOneOfTwoNodesLeavesTheOtherAndEpmdUndisturbed(@TempDir Path tempDir) {
        RabbitMqRuntimeProvider provider = newProvider();
        String idA = "test-a-" + UUID.randomUUID();
        String idB = "test-b-" + UUID.randomUUID();

        StartInstanceOutcome outcomeA = startOk(provider, idA, tempDir.resolve("a-data"), tempDir.resolve("a-logs"), Set.of(6011, 26011));
        StartInstanceOutcome outcomeB = startOk(provider, idB, tempDir.resolve("b-data"), tempDir.resolve("b-logs"), Set.of(6012, 26012));

        assertThat(isEpmdListening()).as("epmd should be listening once any node is up").isTrue();

        ProviderResult<dev.claudev.provider.Ack> stoppedA = provider.stop(new StopInstanceCommand(
                idA, outcomeA.pid(), outcomeA.processCreationTime(), outcomeA.instanceToken(), true));
        assertThat(stoppedA).isInstanceOf(ProviderResult.Ok.class);
        assertThat(ProcessHandle.of(outcomeA.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();

        // The real point of this test: node A's Job Object was just closed (KILL_ON_JOB_CLOSE) —
        // confirm that didn't take epmd or node B down with it.
        ProviderResult<InstanceHealth> healthB = provider.healthCheck(idB);
        assertThat(((ProviderResult.Ok<InstanceHealth>) healthB).value().state()).isEqualTo("running");
        assertThat(isEpmdListening()).as("epmd must survive a sibling node's stop").isTrue();

        provider.stop(new StopInstanceCommand(idB, outcomeB.pid(), outcomeB.processCreationTime(), outcomeB.instanceToken(), true));
    }

    @Test
    void nonAsciiTurkishDataDirectoryWorks(@TempDir Path tempDir) {
        RabbitMqRuntimeProvider provider = newProvider();
        String instanceId = "test-tr-" + UUID.randomUUID();
        Path turkishDataDir = tempDir.resolve("örnek-çalışma-alanı").resolve("data");
        Path turkishLogDir = tempDir.resolve("örnek-çalışma-alanı").resolve("logs");

        StartInstanceOutcome outcome = startOk(provider, instanceId, turkishDataDir, turkishLogDir, Set.of(6021, 26021));

        assertThat(turkishDataDir).isDirectoryContaining(p -> true);
        provider.stop(new StopInstanceCommand(instanceId, outcome.pid(), outcome.processCreationTime(), outcome.instanceToken(), true));
    }

    private static StartInstanceOutcome startOk(
            RabbitMqRuntimeProvider provider, String instanceId, Path dataDir, Path logDir, Set<Integer> ports) {
        ProviderResult<StartInstanceOutcome> result = provider.start(new StartInstanceCommand(
                instanceId, "unused", dataDir, logDir, ports, Map.of()));
        assertThat(result).as("start() result").isInstanceOf(ProviderResult.Ok.class);
        return ((ProviderResult.Ok<StartInstanceOutcome>) result).value();
    }

    private static boolean isEpmdListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 4369), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
