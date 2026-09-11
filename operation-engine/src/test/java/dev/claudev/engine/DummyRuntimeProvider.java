package dev.claudev.engine;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only {@link RuntimeProvider}: spawns a real, trivial, long-lived child process (a copy of
 * {@code ping.exe}) via WP1's {@link WindowsProcessLauncher} — exactly what
 * docs/MILESTONES.md's WP4 backlog calls for, so WP1-WP4 can be exercised end to end without a
 * real RabbitMQ/Redis adapter existing yet. Not shipped in production code.
 */
final class DummyRuntimeProvider implements RuntimeProvider {

    private final Map<String, LaunchResult> launches = new ConcurrentHashMap<>();

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest("dummy-runtime", "0.1.0", List.of(new Capability("test", "dummy")), "{}");
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
                    copied, List.of(copied.toString(), "-n", "30", "127.0.0.1"),
                    null, Map.of("SystemRoot", systemRoot.toString()),
                    "claudev-dummy-" + command.instanceId(), true);
            LaunchResult result = WindowsProcessLauncher.launch(spec);
            launches.put(command.instanceId(), result);

            return ProviderResult.ok(new StartInstanceOutcome(
                    command.instanceId(), result.pid(), result.fingerprintSha256(),
                    result.creationTime(), UUID.randomUUID(), Set.of()));
        } catch (Exception e) {
            return ProviderResult.err(new ProviderError.Underlying("SPAWN_FAILED", String.valueOf(e.getMessage())));
        }
    }

    @Override
    public ProviderResult<Ack> stop(StopInstanceCommand command) {
        LaunchResult result = launches.remove(command.instanceId());
        if (result != null) {
            // Wait for actual exit, then retry-delete the copied executable ourselves — Windows
            // can lag briefly releasing an exited process's file lock even after it's gone (the
            // same AV/EDR-adjacent delay class documented for writes in docs/PROCESS_SAFETY.md),
            // long enough to race a test's @TempDir cleanup otherwise.
            java.util.Optional<ProcessHandle> handle = ProcessHandle.of(result.pid());
            result.job().terminate(1);
            result.job().close();
            handle.ifPresent(DummyRuntimeProvider::waitUntilNotAlive);
            deleteWithRetry(Path.of(result.imagePath()));
        }
        return ProviderResult.ok(Ack.INSTANCE);
    }

    private static void waitUntilNotAlive(ProcessHandle handle) {
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(10);
        while (java.time.Instant.now().isBefore(deadline)) {
            if (!handle.isAlive()) {
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

    private static void deleteWithRetry(Path file) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (java.io.IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public ProviderResult<InstanceHealth> healthCheck(String instanceId) {
        boolean alive = launches.containsKey(instanceId)
                && ProcessHandle.of(launches.get(instanceId).pid()).map(ProcessHandle::isAlive).orElse(false);
        return ProviderResult.ok(new InstanceHealth(instanceId, alive ? "running" : "stopped", "dummy"));
    }

    /** Test-only escape hatch to check for an orphaned process directly, bypassing the provider abstraction. */
    boolean isStillAlive(String instanceId) {
        LaunchResult result = launches.get(instanceId);
        return result != null && ProcessHandle.of(result.pid()).map(ProcessHandle::isAlive).orElse(false);
    }
}
