package dev.claudev.ui;

import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.Operation;
import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.WorkspaceId;

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

    /** Creates an instance backed by {@code adapter-dummy-runtime} — see docs/MILESTONES.md WP5 on why a dummy, not a real RabbitMQ/Redis instance, is what V1's workspace UI can actually drive today. */
    Instance createDummyInstance(WorkspaceId workspaceId, String name);

    void deleteInstance(InstanceId id);

    OperationId startInstance(InstanceId id);

    OperationId stopInstance(InstanceId id);

    void subscribeToOperationEvents(Consumer<OperationEvent> listener);

    Optional<Operation> findOperation(OperationId id);

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
        };
    }
}
