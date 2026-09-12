package dev.claudev.ui;

import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.Operation;
import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.WorkspaceId;
import dev.claudev.domain.detect.DetectedCandidate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The workspace/instance half of the application layer, seen from {@code ui-shell}. Implemented in
 * {@code app-bootstrap} (which can see the repositories, the runtime provider, and the operation
 * engine) — mirrors the {@link DiagnosticsSource} split so {@code ui-shell} stays free of
 * persistence/provider dependencies (D2-style layering).
 *
 * <p>Returns real domain-core types directly rather than inventing parallel UI DTOs: {@code
 * ui-shell} already depends on {@code domain-core} for this reason (see its pom), and a workspace/
 * instance list has no UI-specific shape a repository row doesn't already have.
 */
public interface WorkspaceControlPort {

    List<Workspace> listWorkspaces();

    Workspace createWorkspace(String name);

    void deleteWorkspace(WorkspaceId id);

    List<Instance> listInstances(WorkspaceId workspaceId);

    /** Creates an instance backed by {@code adapter-dummy-runtime} (WP5) — a real spawned process, just not a real RabbitMQ/Redis one. */
    Instance createDummyInstance(WorkspaceId workspaceId, String name);

    /**
     * Scans the local machine for already-installed, mutually-compatible RabbitMQ+Erlang pairs
     * (WP10d) — never asks the user to type a path unless the scan finds nothing. Runs real I/O
     * (registry/PATH/filesystem), so callers must invoke this off the UI thread.
     */
    List<DetectedCandidate> scanForRabbitMqCandidates();

    /** Creates a real RabbitMQ instance (WP6) using an auto-detected candidate from {@link #scanForRabbitMqCandidates()}. */
    Instance createRabbitMqInstance(WorkspaceId workspaceId, String name, DetectedCandidate candidate);

    /** Creates a real RabbitMQ instance from explicitly-typed/browsed paths — the fallback when auto-detection finds nothing. */
    Instance createRabbitMqInstance(WorkspaceId workspaceId, String name, Path erlangHome, Path rabbitmqSbin);

    void deleteInstance(InstanceId id);

    OperationId startInstance(InstanceId id);

    OperationId stopInstance(InstanceId id);

    void subscribeToOperationEvents(Consumer<OperationEvent> listener);

    Optional<Operation> findOperation(OperationId id);

    /**
     * WP10c: RabbitMQ plugin management (Shovel, management, federation, ...) — a RabbitMQ-specific
     * escape hatch, not a generic capability every instance kind has (see docs/MILESTONES.md WP10c).
     * Throws for a non-RabbitMQ instance, or one not currently tracked (started in this app run).
     */
    List<String> listEnabledPlugins(InstanceId id);

    void enablePlugin(InstanceId id, String pluginName);

    void disablePlugin(InstanceId id, String pluginName);

    /** Fallback used when the shell is launched without a backend wired (e.g. directly from an IDE). */
    static WorkspaceControlPort unavailable() {
        return new WorkspaceControlPort() {
            @Override
            public List<Workspace> listWorkspaces() {
                return List.of();
            }

            @Override
            public Workspace createWorkspace(String name) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public void deleteWorkspace(WorkspaceId id) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public List<Instance> listInstances(WorkspaceId workspaceId) {
                return List.of();
            }

            @Override
            public Instance createDummyInstance(WorkspaceId workspaceId, String name) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public List<DetectedCandidate> scanForRabbitMqCandidates() {
                return List.of();
            }

            @Override
            public Instance createRabbitMqInstance(WorkspaceId workspaceId, String name, DetectedCandidate candidate) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public Instance createRabbitMqInstance(WorkspaceId workspaceId, String name, Path erlangHome, Path rabbitmqSbin) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public void deleteInstance(InstanceId id) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public OperationId startInstance(InstanceId id) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public OperationId stopInstance(InstanceId id) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public void subscribeToOperationEvents(Consumer<OperationEvent> listener) {
            }

            @Override
            public Optional<Operation> findOperation(OperationId id) {
                return Optional.empty();
            }

            @Override
            public List<String> listEnabledPlugins(InstanceId id) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public void enablePlugin(InstanceId id, String pluginName) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public void disablePlugin(InstanceId id, String pluginName) {
                throw new IllegalStateException("no backend wired");
            }
        };
    }
}
