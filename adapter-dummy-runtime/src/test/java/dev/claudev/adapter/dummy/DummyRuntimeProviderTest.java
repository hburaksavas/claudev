package dev.claudev.adapter.dummy;

import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.runtime.StartInstanceCommand;
import dev.claudev.provider.runtime.StartInstanceOutcome;
import dev.claudev.provider.runtime.StopInstanceCommand;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real (not mocked): spawns and kills an actual OS process via the live Win32 launcher. */
class DummyRuntimeProviderTest {

    @Test
    void startsAndStopsARealProcess(@TempDir Path tempDir) {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));

        DummyRuntimeProvider provider = new DummyRuntimeProvider();
        String instanceId = "test-" + UUID.randomUUID();

        ProviderResult<StartInstanceOutcome> started = provider.start(new StartInstanceCommand(
                instanceId, "unused", tempDir.resolve("data"), tempDir.resolve("logs"), Set.of(), Map.of()));

        assertThat(started).isInstanceOf(ProviderResult.Ok.class);
        StartInstanceOutcome outcome = ((ProviderResult.Ok<StartInstanceOutcome>) started).value();
        assertThat(ProcessHandle.of(outcome.pid())).isPresent();
        assertThat(ProcessHandle.of(outcome.pid()).orElseThrow().isAlive()).isTrue();

        assertThat(provider.healthCheck(instanceId)).isInstanceOf(ProviderResult.Ok.class);

        ProviderResult<dev.claudev.provider.Ack> stopped = provider.stop(new StopInstanceCommand(
                instanceId, outcome.pid(), outcome.processCreationTime(), outcome.instanceToken(), true));
        assertThat(stopped).isInstanceOf(ProviderResult.Ok.class);

        waitUntilNotAlive(outcome.pid());
        assertThat(ProcessHandle.of(outcome.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    private static void waitUntilNotAlive(long pid) {
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(10);
        while (java.time.Instant.now().isBefore(deadline)) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) {
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
