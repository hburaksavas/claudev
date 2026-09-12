package dev.claudev.app;

import dev.claudev.domain.DesiredState;
import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.InstanceState;
import dev.claudev.domain.LaunchRecord;
import dev.claudev.domain.Operation;
import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.OwnerSid;
import dev.claudev.domain.PortSet;
import dev.claudev.domain.RuntimeDefinition;
import dev.claudev.domain.RuntimeDefinitionId;
import dev.claudev.domain.RuntimeKind;
import dev.claudev.domain.RuntimeSource;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.WorkspaceId;
import dev.claudev.engine.OperationEngine;
import dev.claudev.engine.OperationNode;
import dev.claudev.engine.OperationPlan;
import dev.claudev.engine.Reconciler;
import dev.claudev.persistence.InstanceRepository;
import dev.claudev.persistence.LaunchRecordRepository;
import dev.claudev.persistence.RuntimeDefinitionRepository;
import dev.claudev.persistence.Versioned;
import dev.claudev.persistence.WorkspaceRepository;
import dev.claudev.platform.windows.WindowsCurrentUser;
import dev.claudev.provider.Ack;
import dev.claudev.provider.ProviderError;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.runtime.RuntimeProvider;
import dev.claudev.provider.runtime.StartInstanceCommand;
import dev.claudev.provider.runtime.StartInstanceOutcome;
import dev.claudev.provider.runtime.StopInstanceCommand;
import dev.claudev.ui.WorkspaceControlPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The real {@link WorkspaceControlPort}: wires workspace/instance CRUD to the SQLite repositories
 * and start/stop to a one-node {@link OperationPlan} run through the real {@link OperationEngine} —
 * the same engine the diagnostics self-test in {@link SpringDiagnosticsSource} already exercises,
 * now doing real work instead of a no-op. Every started/stopped instance is a real spawned process
 * ({@code adapter-dummy-runtime}), and each start/stop node also runs a {@link Reconciler} pass
 * before returning, so the UI's next poll reflects reality without waiting for the 30s timer in
 * {@link ReconcilerScheduler}.
 */
@Component
public class SpringWorkspaceControlPort implements WorkspaceControlPort {

    /** One shared, deterministic (not random-per-run) definition row per kind so restarts don't accumulate duplicates. */
    private static final RuntimeDefinitionId DUMMY_RUNTIME_DEFINITION_ID = new RuntimeDefinitionId(
            UUID.nameUUIDFromBytes("adapter-dummy-runtime".getBytes(StandardCharsets.UTF_8)));
    private static final RuntimeDefinitionId RABBITMQ_RUNTIME_DEFINITION_ID = new RuntimeDefinitionId(
            UUID.nameUUIDFromBytes("adapter-rabbitmq".getBytes(StandardCharsets.UTF_8)));

    private final WorkspaceRepository workspaceRepository;
    private final InstanceRepository instanceRepository;
    private final LaunchRecordRepository launchRecordRepository;
    private final RuntimeDefinitionRepository runtimeDefinitionRepository;
    private final RuntimeProvider dummyRuntimeProvider;
    private final RabbitMqProviderHolder rabbitMqProviderHolder;
    private final OperationEngine operationEngine;
    private final Reconciler reconciler;
    private final Path instancesRoot;

    public SpringWorkspaceControlPort(
            WorkspaceRepository workspaceRepository,
            InstanceRepository instanceRepository,
            LaunchRecordRepository launchRecordRepository,
            RuntimeDefinitionRepository runtimeDefinitionRepository,
            RuntimeProvider dummyRuntimeProvider,
            RabbitMqProviderHolder rabbitMqProviderHolder,
            OperationEngine operationEngine,
            Reconciler reconciler,
            @Value("${claudev.managed-binaries-dir:${user.home}/.claudev/runtimes}") String managedBinariesDir) {
        this.workspaceRepository = workspaceRepository;
        this.instanceRepository = instanceRepository;
        this.launchRecordRepository = launchRecordRepository;
        this.runtimeDefinitionRepository = runtimeDefinitionRepository;
        this.dummyRuntimeProvider = dummyRuntimeProvider;
        this.rabbitMqProviderHolder = rabbitMqProviderHolder;
        this.operationEngine = operationEngine;
        this.reconciler = reconciler;
        this.instancesRoot = Path.of(managedBinariesDir);
    }

    @Override
    public List<Workspace> listWorkspaces() {
        return workspaceRepository.findAll().stream().map(Versioned::value).toList();
    }

    @Override
    public Workspace createWorkspace(String name) {
        Workspace workspace = new Workspace(
                WorkspaceId.newId(), name, Instant.now(), new OwnerSid(WindowsCurrentUser.sid()), List.of(), List.of());
        workspaceRepository.insert(workspace);
        return workspace;
    }

    @Override
    public void deleteWorkspace(WorkspaceId id) {
        boolean anyRunning = instanceRepository.findByWorkspaceId(id).stream()
                .anyMatch(v -> launchRecordRepository.findByInstanceId(v.value().id()).isPresent());
        if (anyRunning) {
            throw new IllegalStateException("stop every instance in this workspace before deleting it");
        }
        workspaceRepository.delete(id);
    }

    @Override
    public List<Instance> listInstances(WorkspaceId workspaceId) {
        return instanceRepository.findByWorkspaceId(workspaceId).stream().map(Versioned::value).toList();
    }

    @Override
    public Instance createDummyInstance(WorkspaceId workspaceId, String name) {
        ensureDummyRuntimeDefinition();

        InstanceId instanceId = InstanceId.newId();
        Instance instance = new Instance(
                instanceId, workspaceId, DUMMY_RUNTIME_DEFINITION_ID, name,
                PortSet.none(),
                instancesRoot.resolve(instanceId.value().toString()).resolve("data"),
                instancesRoot.resolve(instanceId.value().toString()).resolve("logs"),
                DesiredState.STOPPED, Optional.empty(), InstanceState.STOPPED);
        instanceRepository.insert(instance);
        return instance;
    }

    @Override
    public Instance createRabbitMqInstance(WorkspaceId workspaceId, String name) {
        ensureRabbitMqRuntimeDefinition();

        InstanceId instanceId = InstanceId.newId();
        Set<Integer> ports = findTwoFreeLoopbackPorts();
        Instance instance = new Instance(
                instanceId, workspaceId, RABBITMQ_RUNTIME_DEFINITION_ID, name,
                new PortSet(ports, Set.of()),
                instancesRoot.resolve(instanceId.value().toString()).resolve("data"),
                instancesRoot.resolve(instanceId.value().toString()).resolve("logs"),
                DesiredState.STOPPED, Optional.empty(), InstanceState.STOPPED);
        instanceRepository.insert(instance);
        return instance;
    }

    @Override
    public void deleteInstance(InstanceId id) {
        if (launchRecordRepository.findByInstanceId(id).isPresent()) {
            throw new IllegalStateException("stop this instance before deleting it");
        }
        instanceRepository.delete(id);
    }

    @Override
    public OperationId startInstance(InstanceId id) {
        OperationPlan plan = new OperationPlan(
                "start-instance", List.of(id.value().toString()),
                List.of(new OperationNode("start", List.of(), ctx -> {
                    Versioned<Instance> versioned = instanceRepository.findById(id)
                            .orElseThrow(() -> new IllegalStateException("instance not found: " + id));
                    Instance instance = versioned.value();
                    ctx.progress("starting " + instance.name());
                    ctx.log("resolving runtime provider (a RabbitMQ instance's first-ever start on this "
                            + "machine downloads and verifies ~250MB — see docs/RABBITMQ_RUNTIME.md)");

                    RuntimeProvider provider = resolveProvider(instance.runtimeDefinitionId());
                    StartInstanceCommand command = new StartInstanceCommand(
                            id.value().toString(), instance.runtimeDefinitionId().value().toString(),
                            instance.dataDir(), instance.logDir(), instance.ports().requested(), java.util.Map.of());
                    ProviderResult<StartInstanceOutcome> result = provider.start(command);
                    if (result instanceof ProviderResult.Err<StartInstanceOutcome> err) {
                        throw new IllegalStateException(describeError(err.error()));
                    }
                    StartInstanceOutcome outcome = ((ProviderResult.Ok<StartInstanceOutcome>) result).value();
                    ctx.log("spawned pid " + outcome.pid());

                    // jobHandleId has no meaningful source: RuntimeProvider deliberately never leaks
                    // a raw Job Object handle across the port boundary (D2), and the reconciler never
                    // reads this field back — it verifies identity via pid/creation-time/fingerprint
                    // only (see Reconciler#deriveState).
                    launchRecordRepository.save(id, new LaunchRecord(
                            outcome.pid(), "", outcome.exeFingerprintSha256(), outcome.processCreationTime(),
                            outcome.instanceToken(), 0, instance.dataDir().toString(), "instance-start"));

                    Instance running = new Instance(
                            instance.id(), instance.workspaceId(), instance.runtimeDefinitionId(), instance.name(),
                            instance.ports(), instance.dataDir(), instance.logDir(),
                            DesiredState.STARTED, Optional.empty(), InstanceState.RUNNING);
                    instanceRepository.update(running, versioned.revision());

                    reconciler.reconcile();
                })));
        return operationEngine.submit(plan);
    }

    @Override
    public OperationId stopInstance(InstanceId id) {
        OperationPlan plan = new OperationPlan(
                "stop-instance", List.of(id.value().toString()),
                List.of(new OperationNode("stop", List.of(), ctx -> {
                    Versioned<Instance> versioned = instanceRepository.findById(id)
                            .orElseThrow(() -> new IllegalStateException("instance not found: " + id));
                    Instance instance = versioned.value();
                    ctx.progress("stopping " + instance.name());

                    Optional<LaunchRecord> launchRecord = launchRecordRepository.findByInstanceId(id);
                    if (launchRecord.isPresent()) {
                        LaunchRecord record = launchRecord.get();
                        RuntimeProvider provider = resolveProvider(instance.runtimeDefinitionId());
                        StopInstanceCommand command = new StopInstanceCommand(
                                id.value().toString(), record.pid(), record.processCreationTime(),
                                record.instanceToken(), true);
                        ProviderResult<Ack> result = provider.stop(command);
                        if (result instanceof ProviderResult.Err<Ack> err) {
                            throw new IllegalStateException(describeError(err.error()));
                        }
                        launchRecordRepository.delete(id);
                        ctx.log("stopped pid " + record.pid());
                    } else {
                        ctx.log("no launch record — already stopped");
                    }

                    Instance stopped = new Instance(
                            instance.id(), instance.workspaceId(), instance.runtimeDefinitionId(), instance.name(),
                            instance.ports(), instance.dataDir(), instance.logDir(),
                            DesiredState.STOPPED, Optional.empty(), InstanceState.STOPPED);
                    instanceRepository.update(stopped, versioned.revision());

                    reconciler.reconcile();
                })));
        return operationEngine.submit(plan);
    }

    @Override
    public void subscribeToOperationEvents(Consumer<OperationEvent> listener) {
        operationEngine.subscribe(listener);
    }

    @Override
    public Optional<Operation> findOperation(OperationId id) {
        return operationEngine.find(id);
    }

    private void ensureDummyRuntimeDefinition() {
        if (runtimeDefinitionRepository.findById(DUMMY_RUNTIME_DEFINITION_ID).isPresent()) {
            return;
        }
        runtimeDefinitionRepository.insert(new RuntimeDefinition(
                DUMMY_RUNTIME_DEFINITION_ID, RuntimeKind.DUMMY,
                new RuntimeSource.Imported(instancesRoot), 1));
    }

    /**
     * The recorded {@code source} path reflects which mode {@link RabbitMqProviderHolder} is
     * actually in (a user's imported binaries, or this app's managed/pinned-pair directory) — it is
     * still always encoded as {@code Imported} ({@link dev.claudev.persistence.RuntimeDefinitionRepository}
     * has no wire format for {@code Managed} yet), so this is bookkeeping/display metadata only, not
     * something {@link #resolveProvider} reads back to decide behavior (that always asks {@code
     * RabbitMqProviderHolder} directly).
     */
    private void ensureRabbitMqRuntimeDefinition() {
        if (runtimeDefinitionRepository.findById(RABBITMQ_RUNTIME_DEFINITION_ID).isPresent()) {
            return;
        }
        runtimeDefinitionRepository.insert(new RuntimeDefinition(
                RABBITMQ_RUNTIME_DEFINITION_ID, RuntimeKind.RABBIT_MQ,
                new RuntimeSource.Imported(rabbitMqProviderHolder.describedSourcePath()), 1));
    }

    /**
     * {@code adapter-rabbitmq} isn't a Spring bean (see docs/MILESTONES.md WP6 — eager provisioning
     * at every app startup would mean an unconditional ~250MB download); it's provisioned lazily,
     * once, on first dispatch to a RABBIT_MQ instance via {@link RabbitMqProviderHolder}.
     */
    private RuntimeProvider resolveProvider(RuntimeDefinitionId runtimeDefinitionId) throws IOException, InterruptedException {
        RuntimeDefinition definition = runtimeDefinitionRepository.findById(runtimeDefinitionId)
                .orElseThrow(() -> new IllegalStateException("runtime definition not found: " + runtimeDefinitionId));
        return switch (definition.kind()) {
            case DUMMY -> dummyRuntimeProvider;
            case RABBIT_MQ -> rabbitMqProviderHolder.get();
            case REDIS -> throw new IllegalStateException(
                    "REDIS is a ConnectionProvider (connection-only, D8) — it has no RuntimeProvider/instance lifecycle");
        };
    }

    /**
     * Binds two loopback sockets simultaneously (not two sequential bind/release calls) so the OS
     * cannot hand back the same ephemeral port for both — a real, if narrow, race on a busy machine.
     */
    private static Set<Integer> findTwoFreeLoopbackPorts() {
        try (ServerSocket first = new ServerSocket(0);
             ServerSocket second = new ServerSocket(0)) {
            return Set.of(first.getLocalPort(), second.getLocalPort());
        } catch (IOException e) {
            throw new IllegalStateException("Could not find free loopback ports: " + e.getMessage(), e);
        }
    }

    private static String describeError(ProviderError error) {
        return switch (error) {
            case ProviderError.NotFound e -> "NOT_FOUND: " + e.message();
            case ProviderError.InvalidConfig e -> "INVALID_CONFIG: " + e.message();
            case ProviderError.Timeout e -> "TIMEOUT: " + e.message();
            case ProviderError.PermissionDenied e -> "PERMISSION_DENIED: " + e.message();
            case ProviderError.Conflict e -> "CONFLICT: " + e.message();
            case ProviderError.Underlying e -> e.code() + ": " + e.message();
        };
    }
}
