package dev.claudev.adapter.dummy;

import dev.claudev.platform.windows.LaunchResult;
import dev.claudev.platform.windows.LaunchSpec;
import dev.claudev.platform.windows.WindowsProcessLauncher;
import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.Capability;
import dev.claudev.provider.ProviderError;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.runtime.InstanceHealth;
import dev.claudev.provider.runtime.RuntimeProvider;
import dev.claudev.provider.runtime.StartInstanceCommand;
import dev.claudev.provider.runtime.StartInstanceOutcome;
import dev.claudev.provider.runtime.StopInstanceCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A real {@link RuntimeProvider}: spawns a copy of {@code ping.exe} (a stand-in for a real managed
 * binary) via {@link WindowsProcessLauncher}, under a real Job Object, with a real verified PID +
 * creation-time + SHA-256 fingerprint — exactly the same primitive a real adapter would use, minus
 * any actual RabbitMQ/Redis protocol. This exists so WP5's workspace UI and the reconciler can be
 * built and verified against real OS process lifecycle before WP6/WP7 land (see docs/MILESTONES.md).
 *
 * <p>{@link #launches} is in-memory only — a JVM restart loses track of the {@link WindowsJobObject}
 * handles here (the reconciler still detects the underlying process via the persisted
 * {@code LaunchRecord} and flips it to {@code ORPHANED}, since verification is PID/creation-time/
 * fingerprint-based, not dependent on this map). Reattaching to a still-open Job Object by name
 * across a restart is not implemented — out of scope for this adapter's purpose.
 */
public final class DummyRuntimeProvider implements RuntimeProvider {

    private final Map<String, LaunchResult> launches = new ConcurrentHashMap<>();

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "adapter-dummy-runtime", "0.1.0",
                List.of(new Capability("runtime", "dummy"), new Capability("status", "verification-only")),
                "{}");
    }

    @Override
    public ProviderResult<StartInstanceOutcome> start(StartInstanceCommand command) {
        try {
            Path systemRoot = Path.of(System.getenv("SystemRoot"));
            Path pingExe = systemRoot.resolve("System32").resolve("ping.exe");

            Files.createDirectories(command.dataDir());
            Path copied = command.dataDir().resolve("dummy-" + UUID.randomUUID() + ".exe");
            Files.copy(pingExe, copied, StandardCopyOption.REPLACE_EXISTING);

            LaunchSpec spec = new LaunchSpec(
                    copied, List.of(copied.toString(), "-n", "3600", "127.0.0.1"),
                    null, Map.of("SystemRoot", systemRoot.toString()),
                    "claudev-dummy-" + command.instanceId(), true);
            LaunchResult result = WindowsProcessLauncher.launch(spec);
            launches.put(command.instanceId(), result);

            return ProviderResult.ok(new StartInstanceOutcome(
                    command.instanceId(), result.pid(), result.fingerprintSha256(),
                    result.creationTime(), UUID.randomUUID(), Set.of()));
        } catch (IOException | RuntimeException e) {
            return ProviderResult.err(new ProviderError.Underlying("SPAWN_FAILED", String.valueOf(e.getMessage())));
        }
    }

    @Override
    public ProviderResult<Ack> stop(StopInstanceCommand command) {
        LaunchResult result = launches.remove(command.instanceId());
        if (result != null) {
            result.job().terminate(1);
            result.job().close();
            deleteWithRetry(Path.of(result.imagePath()));
        }
        return ProviderResult.ok(Ack.INSTANCE);
    }

    @Override
    public ProviderResult<InstanceHealth> healthCheck(String instanceId) {
        LaunchResult result = launches.get(instanceId);
        boolean alive = result != null && ProcessHandle.of(result.pid()).map(ProcessHandle::isAlive).orElse(false);
        return ProviderResult.ok(new InstanceHealth(instanceId, alive ? "running" : "stopped", "dummy"));
    }

    private static void deleteWithRetry(Path file) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
