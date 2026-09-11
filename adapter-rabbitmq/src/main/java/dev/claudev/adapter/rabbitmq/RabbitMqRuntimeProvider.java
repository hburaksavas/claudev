package dev.claudev.adapter.rabbitmq;

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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real {@code adapter-rabbitmq} (WP6): spawns a real RabbitMQ node against the {@link
 * RabbitMqPinnedPair} Erlang/OTP + RabbitMQ zip pair, per-instance-isolated exactly as the WP6
 * feasibility spike proved works (see docs/RABBITMQ_RUNTIME.md): explicit nodename/ports/cookie/
 * data-dir, EPMD started once as an unsupervised machine-wide dependency via {@link
 * EpmdSupervisor} (never inside a per-instance Job Object), readiness confirmed via {@code
 * rabbitmqctl await_startup} rather than port occupancy.
 *
 * <p><b>Port convention</b>: {@link StartInstanceCommand#requestedPorts()} must contain exactly two
 * values — the lower one is used as the AMQP listener port, the higher one as the Erlang
 * distribution port. This isn't part of {@code provider-api} (which stays domain-agnostic, D2), so
 * it's documented here and in docs/RABBITMQ_RUNTIME.md rather than encoded in a type.
 *
 * <p><b>Known simplification</b>: the {@code pid}/{@code exeFingerprintSha256} identity recorded in
 * {@link StartInstanceOutcome} belongs to the spawned {@code cmd.exe} (required to run the
 * {@code .bat} launcher — see {@code rabbitmq-server.bat} calling {@code erl.exe} directly, not via
 * {@code start}, so it blocks and stays alive for exactly as long as the node does), not the
 * {@code erl.exe} BEAM process itself. This is the same limitation every {@code cmd.exe}-wrapped
 * spawn in this codebase has (see {@code MavenBuildExecutor}); the reconciler's PID/creation-time/
 * fingerprint check against that {@code cmd.exe} process is still a faithful liveness signal for as
 * long as this parent/child relationship holds.
 */
public final class RabbitMqRuntimeProvider implements RuntimeProvider {

    private final RabbitMqInstallation installation;
    private final Map<String, NodeHandle> nodes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public RabbitMqRuntimeProvider(RabbitMqInstallation installation) {
        this.installation = installation;
    }

    /** Provisions (downloading/verifying if needed) the pinned pair under {@code managedDir} and returns a ready-to-use provider. */
    public static RabbitMqRuntimeProvider provision(Path managedDir) throws IOException, InterruptedException {
        RabbitMqInstallation installation = new RabbitMqBinaryProvisioner().ensureProvisioned(managedDir);
        return new RabbitMqRuntimeProvider(installation);
    }

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "adapter-rabbitmq", RabbitMqPinnedPair.RABBITMQ_VERSION,
                List.of(
                        new Capability("runtime", "rabbitmq"),
                        new Capability("status", "implemented"),
                        new Capability("erlang", RabbitMqPinnedPair.ERLANG_VERSION)),
                "{}");
    }

    @Override
    public ProviderResult<StartInstanceOutcome> start(StartInstanceCommand command) {
        Set<Integer> ports = new TreeSet<>(command.requestedPorts());
        if (ports.size() != 2) {
            return ProviderResult.err(new ProviderError.InvalidConfig(
                    "RabbitMQ instances require exactly 2 requestedPorts (amqp, dist); got " + ports));
        }
        int amqpPort = ports.iterator().next();
        int distPort = ports.stream().skip(1).findFirst().orElseThrow();

        String nodename = "rmq" + sanitize(command.instanceId()) + "@localhost";
        String cookie = randomCookie();

        try {
            EpmdSupervisor.ensureRunning(installation.erlangHome());
        } catch (IOException e) {
            return ProviderResult.err(new ProviderError.Underlying("EPMD_START_FAILED", e.getMessage()));
        }

        try {
            Files.createDirectories(command.dataDir());
            Files.createDirectories(command.logDir());
        } catch (IOException e) {
            return ProviderResult.err(new ProviderError.Underlying("DIR_CREATE_FAILED", e.getMessage()));
        }

        Map<String, String> env = new HashMap<>(command.environment());
        RabbitMqEnvironment.addBaseVars(env);
        env.put("ERLANG_HOME", installation.erlangHome().toString());
        env.put("RABBITMQ_MNESIA_BASE", command.dataDir().toString());
        env.put("RABBITMQ_LOG_BASE", command.logDir().toString());
        env.put("RABBITMQ_NODENAME", nodename);
        env.put("RABBITMQ_NODE_PORT", String.valueOf(amqpPort));
        env.put("RABBITMQ_DIST_PORT", String.valueOf(distPort));
        env.put("RABBITMQ_SERVER_START_ARGS", "-setcookie " + cookie);

        Path cmdExe = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe");
        Path serverBat = installation.rabbitmqSbin().resolve("rabbitmq-server.bat");
        List<String> argv = List.of(cmdExe.toString(), "/c", serverBat.toString());

        LaunchSpec spec = new LaunchSpec(
                cmdExe, argv, installation.rabbitmqSbin(), env,
                "claudev-rabbitmq-" + command.instanceId(), true,
                Optional.of(command.logDir().resolve("stdout.log")),
                Optional.of(command.logDir().resolve("stderr.log")));

        LaunchResult launch;
        try {
            launch = WindowsProcessLauncher.launch(spec);
        } catch (RuntimeException e) {
            return ProviderResult.err(new ProviderError.Underlying("SPAWN_FAILED", e.getMessage()));
        }

        NodeHandle handle = new NodeHandle(launch, nodename, cookie);
        nodes.put(command.instanceId(), handle);

        RabbitMqCtl.Result readiness = awaitStartupWithRetry(nodename, cookie, Duration.ofSeconds(60));
        if (!readiness.succeeded()) {
            launch.job().terminate(1);
            launch.job().close();
            nodes.remove(command.instanceId());
            return ProviderResult.err(new ProviderError.Timeout(
                    "RabbitMQ node did not become ready: " + truncate(readiness.output())));
        }

        return ProviderResult.ok(new StartInstanceOutcome(
                command.instanceId(), launch.pid(), launch.fingerprintSha256(),
                launch.creationTime(), UUID.randomUUID(), Set.of(amqpPort)));
    }

    @Override
    public ProviderResult<Ack> stop(StopInstanceCommand command) {
        NodeHandle handle = nodes.remove(command.instanceId());
        if (handle == null) {
            return ProviderResult.ok(Ack.INSTANCE);
        }

        if (command.graceful()) {
            RabbitMqCtl.Result stopped = RabbitMqCtl.run(
                    installation, handle.nodename(), handle.cookie(), Duration.ofSeconds(20), "stop");
            if (stopped.succeeded() && waitForExit(handle.launch().pid(), Duration.ofSeconds(15))) {
                handle.launch().job().close();
                return ProviderResult.ok(Ack.INSTANCE);
            }
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

        RabbitMqCtl.Result status = RabbitMqCtl.run(installation, handle.nodename(), handle.cookie(), Duration.ofSeconds(10), "status");
        return ProviderResult.ok(new InstanceHealth(
                instanceId, status.succeeded() ? "running" : "unreachable", truncate(status.output())));
    }

    /**
     * A real race, found via a flaky test failure rather than assumed: immediately after {@code
     * WindowsProcessLauncher.launch} resumes the suspended process, the {@code cmd.exe -> erl.exe}
     * chain has not necessarily registered the node with EPMD yet. A single {@code rabbitmqctl
     * await_startup} invocation issued at that instant reports "node ... not running at all" —
     * epmd's answer for "I have never heard of this name" — and returns immediately rather than
     * retrying, since from its perspective there is nothing yet to wait on. Retrying the whole
     * invocation ourselves (not relying on {@code await_startup} to internally retry through that
     * specific state) is what actually closes the race.
     */
    private RabbitMqCtl.Result awaitStartupWithRetry(String nodename, String cookie, Duration overallTimeout) {
        java.time.Instant deadline = java.time.Instant.now().plus(overallTimeout);
        RabbitMqCtl.Result last;
        do {
            last = RabbitMqCtl.run(installation, nodename, cookie, Duration.ofSeconds(15), "await_startup");
            if (last.succeeded()) {
                return last;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        } while (java.time.Instant.now().isBefore(deadline));
        return last;
    }

    private static boolean waitForExit(long pid, Duration timeout) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return true;
        }
        java.time.Instant deadline = java.time.Instant.now().plus(timeout);
        while (java.time.Instant.now().isBefore(deadline)) {
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

    private String randomCookie() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String sanitize(String instanceId) {
        return instanceId.replaceAll("[^a-zA-Z0-9]", "");
    }

    private static String truncate(String text) {
        int max = 2000;
        return text.length() > max ? text.substring(text.length() - max) : text;
    }

    private record NodeHandle(LaunchResult launch, String nodename, String cookie) {
    }
}
