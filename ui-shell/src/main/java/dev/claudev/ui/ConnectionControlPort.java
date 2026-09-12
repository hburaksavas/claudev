package dev.claudev.ui;

import dev.claudev.domain.Connection;
import dev.claudev.domain.ConnectionId;
import dev.claudev.domain.detect.DetectedRedisEndpoint;
import dev.claudev.provider.connection.ScanPage;

import java.util.List;
import java.util.Optional;

/**
 * WP10a: a manual "point at my own Redis" connection — {@code adapter-redis} already supports this
 * for real (WP7), this port only exposes it to the UI. Mirrors the {@link WorkspaceControlPort}/
 * {@link DiagnosticsSource} split: implemented in {@code app-bootstrap}, which can see the
 * repository and provider beans; {@code ui-shell} stays free of persistence/provider dependencies.
 *
 * <p>V1 scope only: remote connections (host/port/optional password), no {@code Local}
 * instance-backed connection (see {@code ConnectionRepository}'s javadoc).
 */
public interface ConnectionControlPort {

    List<Connection> listConnections();

    /**
     * Probes for already-running Redis endpoints (native, WSL2, or port-published Docker — never
     * lifecycle-owned, see docs/REDIS_SCOPE.md) via a real TCP-connect + {@code PING}. Runs real
     * I/O, so callers must invoke this off the UI thread.
     */
    List<DetectedRedisEndpoint> scanForRedisEndpoints();

    /** Attempts a real connection before persisting anything — a connection row is never created for an address that couldn't actually be reached. */
    Connection connectToRedis(String host, int port, Optional<String> password);

    void deleteConnection(ConnectionId id);

    /** Reconnects transparently (using the persisted host/port/resolved secret) if the in-memory connection was lost, e.g. after an app restart. */
    ScanPage scanKeys(ConnectionId id, String cursor, int pageSize);

    Optional<String> getValue(ConnectionId id, String key);

    /** Read-only until this is called for a given connection — see docs/SECURITY.md's "Remote Redis destructive-operation guard". Session-scoped: reverts to locked on app restart. */
    void unlockForWrites(ConnectionId id);

    /**
     * Runs the base authorization+audit gate (docs/SECURITY.md) before dispatching to the adapter:
     * an unlocked-but-otherwise-policy-refused mutation throws without ever reaching Redis, and
     * every attempt — allowed or denied — writes an {@code AuditEntry} first.
     */
    void authorizeMutation(ConnectionId id, String operation, String key, String value, Optional<String> field);

    static ConnectionControlPort unavailable() {
        return new ConnectionControlPort() {
            @Override
            public List<Connection> listConnections() {
                return List.of();
            }

            @Override
            public List<DetectedRedisEndpoint> scanForRedisEndpoints() {
                return List.of();
            }

            @Override
            public Connection connectToRedis(String host, int port, Optional<String> password) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public void deleteConnection(ConnectionId id) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public ScanPage scanKeys(ConnectionId id, String cursor, int pageSize) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public Optional<String> getValue(ConnectionId id, String key) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public void unlockForWrites(ConnectionId id) {
                throw new IllegalStateException("no backend wired");
            }

            @Override
            public void authorizeMutation(ConnectionId id, String operation, String key, String value, Optional<String> field) {
                throw new IllegalStateException("no backend wired");
            }
        };
    }
}
