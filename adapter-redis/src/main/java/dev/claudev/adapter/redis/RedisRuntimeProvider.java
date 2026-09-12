package dev.claudev.adapter.redis;

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
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WP10f: a real {@code redis-server.exe} the user already has installed — spawned and lifecycle-
 * owned by this app (Job Object, start/stop/health) exactly like {@code adapter-dummy-runtime}/
 * {@code adapter-rabbitmq} — but never bundled, downloaded, or checksummed here (docs/REDIS_SCOPE.md's
 * "no managed local Redis" is about *bundling*; this is the same {@code RuntimeSource.Imported}
 * trust boundary RabbitMQ already uses: the user is trusted to have gotten their own binary).
 *
 * <p>Unlike {@code rabbitmq-server.bat}, {@code redis-server.exe} is a real Win32 executable — no
 * {@code cmd.exe} wrapper is needed, matching {@code DummyRuntimeProvider}'s direct-exe spawn shape
 * rather than RabbitMQ's batch-script one.
 */
public final class RedisRuntimeProvider implements RuntimeProvider {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    private final java.nio.file.Path redisServerExe;
    private final Map<String, NodeHandle> nodes = new ConcurrentHashMap<>();

    private RedisRuntimeProvider(java.nio.file.Path redisServerExe) {
        this.redisServerExe = redisServerExe;
    }

    /** The {@code redis-server.exe} this provider spawns — for cross-checking a later import request against what's already active. */
    public java.nio.file.Path redisServerExe() {
        return redisServerExe;
    }

    /**
     * WP10f: a user-supplied ({@code RuntimeSource.Imported}) {@code redis-server.exe} — no
     * download, no checksum, the same trust boundary {@code RabbitMqRuntimeProvider.fromImported}
     * already uses for RabbitMQ.
     */
    public static RedisRuntimeProvider fromImported(java.nio.file.Path redisServerExe) throws IOException {
        RedisInstallValidator.redisServerExeAt(redisServerExe);
        return new RedisRuntimeProvider(redisServerExe);
    }

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "adapter-redis-runtime", "0.1.0",
                List.of(new Capability("runtime", "redis"), new Capability("status", "implemented")),
                "{}");
    }

    @Override
    public ProviderResult<StartInstanceOutcome> start(StartInstanceCommand command) {
        if (command.requestedPorts().size() != 1) {
            return ProviderResult.err(new ProviderError.InvalidConfig(
                    "Redis instances require exactly 1 requestedPort; got " + command.requestedPorts()));
        }
        int port = command.requestedPorts().iterator().next();

        try {
            Files.createDirectories(command.dataDir());
            Files.createDirectories(command.logDir());
        } catch (IOException e) {
            return ProviderResult.err(new ProviderError.Underlying("DIR_CREATE_FAILED", e.getMessage()));
        }

        List<String> argv = List.of(
                redisServerExe.toString(), "--port", String.valueOf(port),
                "--bind", "127.0.0.1", "--dir", command.dataDir().toString());
        Map<String, String> env = new java.util.HashMap<>(command.environment());
        addBaseVars(env);
        LaunchSpec spec = new LaunchSpec(
                redisServerExe, argv, command.dataDir(), env,
                "claudev-redis-" + command.instanceId(), true,
                Optional.of(command.logDir().resolve("stdout.log")),
                Optional.of(command.logDir().resolve("stderr.log")));

        LaunchResult launch;
        try {
            launch = WindowsProcessLauncher.launch(spec);
        } catch (RuntimeException e) {
            return ProviderResult.err(new ProviderError.Underlying("SPAWN_FAILED", e.getMessage()));
        }

        nodes.put(command.instanceId(), new NodeHandle(launch, port));

        if (!awaitPongWithRetry(port, STARTUP_TIMEOUT)) {
            launch.job().terminate(1);
            launch.job().close();
            nodes.remove(command.instanceId());
            return ProviderResult.err(new ProviderError.Timeout("redis-server did not answer PING in time"));
        }

        return ProviderResult.ok(new StartInstanceOutcome(
                command.instanceId(), launch.pid(), launch.fingerprintSha256(),
                launch.creationTime(), UUID.randomUUID(), Set.of(port)));
    }

    @Override
    public ProviderResult<Ack> stop(StopInstanceCommand command) {
        NodeHandle handle = nodes.remove(command.instanceId());
        if (handle == null) {
            return ProviderResult.ok(Ack.INSTANCE);
        }

        if (command.graceful() && sendShutdown(handle.port()) && waitForExit(handle.launch().pid(), Duration.ofSeconds(10))) {
            handle.launch().job().close();
            return ProviderResult.ok(Ack.INSTANCE);
        }

        handle.launch().job().terminate(1);
        handle.launch().job().close();
        return ProviderResult.ok(Ack.INSTANCE);
    }

    @Override
    public ProviderResult<InstanceHealth> healthCheck(String instanceId) {
        NodeHandle handle = nodes.get(instanceId);
        if (handle == null) {
            return ProviderResult.ok(new InstanceHealth(instanceId, "stopped", "no tracked node"));
        }
        boolean alive = ping(handle.port());
        return ProviderResult.ok(new InstanceHealth(instanceId, alive ? "running" : "unreachable", alive ? "PONG" : "no PONG"));
    }

    /**
     * Redis's {@code SHUTDOWN} closes the connection without a reply — a real Lettuce behavior, not
     * an error, so any exception from this call is treated as "the shutdown was issued" and the
     * caller separately waits for the process to actually exit rather than trusting this alone.
     */
    private boolean sendShutdown(int port) {
        RedisURI uri = RedisURI.Builder.redis("127.0.0.1", port).withTimeout(PING_TIMEOUT).build();
        RedisClient client = RedisClient.create(uri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            try {
                connection.sync().shutdown(false);
            } catch (RuntimeException expected) {
                // Redis closes the socket without a reply on SHUTDOWN — expected, not a failure.
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        } finally {
            client.shutdown();
        }
    }

    private boolean awaitPongWithRetry(int port, Duration overallTimeout) {
        Instant deadline = Instant.now().plus(overallTimeout);
        do {
            if (ping(port)) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (Instant.now().isBefore(deadline));
        return false;
    }

    private boolean ping(int port) {
        RedisURI uri = RedisURI.Builder.redis("127.0.0.1", port).withTimeout(PING_TIMEOUT).build();
        RedisClient client = RedisClient.create(uri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            return "PONG".equalsIgnoreCase(connection.sync().ping());
        } catch (RuntimeException e) {
            return false;
        } finally {
            client.shutdown();
        }
    }

    private static boolean waitForExit(long pid, Duration timeout) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return true;
        }
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!handle.get().isAlive()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Forwards a small set of well-known variables from this JVM's own environment — mirrors
     * {@code RabbitMqEnvironment.addBaseVars}. Redis itself needs at least {@code SystemRoot}/{@code
     * TEMP} to behave like a normal Windows process (DLL search path, temp files), same as every
     * other real spawn in this codebase — kept even after fixing the actual root cause of the spawn
     * failure this uncovered ({@code WindowsEnvironmentBlock.encode}'s zero-entries case produced a
     * single-, not double-, null-terminated block, which {@code CreateProcessW} rejected) so a
     * genuinely empty environment is never relied on in practice either.
     */
    private static void addBaseVars(Map<String, String> env) {
        putIfPresent(env, "SystemRoot");
        putIfPresent(env, "Path");
        putIfPresent(env, "PATHEXT");
        putIfPresent(env, "USERPROFILE");
        putIfPresent(env, "APPDATA");
        putIfPresent(env, "LOCALAPPDATA");
        putIfPresent(env, "TEMP");
        putIfPresent(env, "TMP");
    }

    private static void putIfPresent(Map<String, String> env, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            env.put(name, value);
        }
    }

    private record NodeHandle(LaunchResult launch, int port) {
    }
}
