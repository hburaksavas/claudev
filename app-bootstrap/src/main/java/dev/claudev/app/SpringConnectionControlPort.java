package dev.claudev.app;

import dev.claudev.domain.Connection;
import dev.claudev.domain.ConnectionId;
import dev.claudev.domain.ConnectionKind;
import dev.claudev.domain.EnvironmentClass;
import dev.claudev.domain.RedisSafetyPolicy;
import dev.claudev.domain.SecretRef;
import dev.claudev.persistence.ConnectionRepository;
import dev.claudev.provider.Ack;
import dev.claudev.provider.ProviderError;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.connection.ConnectOptions;
import dev.claudev.provider.connection.ConnectionProvider;
import dev.claudev.provider.connection.ScanPage;
import dev.claudev.provider.secret.SecretHandle;
import dev.claudev.provider.secret.SecretStore;
import dev.claudev.ui.ConnectionControlPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real {@link ConnectionControlPort} (WP10a): {@code adapter-redis}'s {@code connect} already
 * works for real (WP7) — this wires it to the UI, persists the connection via {@link
 * ConnectionRepository}, and resolves/stores the password through {@link SecretStore} (never in
 * SQLite as plaintext, per docs/SECURITY.md).
 *
 * <p>{@link #connectedInThisSession} tracks which connection ids this JVM run has actually opened
 * on the (memory-only) {@link ConnectionProvider} — after a restart that set is empty, so {@link
 * #ensureConnected} transparently reconnects using the persisted host/port and resolved secret
 * before the first browse action, rather than assuming the in-memory Lettuce connection survived.
 */
@Component
public class SpringConnectionControlPort implements ConnectionControlPort {

    private final ConnectionRepository connectionRepository;
    private final ConnectionProvider connectionProvider;
    private final SecretStore secretStore;
    private final Set<String> connectedInThisSession = ConcurrentHashMap.newKeySet();

    public SpringConnectionControlPort(
            ConnectionRepository connectionRepository, ConnectionProvider connectionProvider, SecretStore secretStore) {
        this.connectionRepository = connectionRepository;
        this.connectionProvider = connectionProvider;
        this.secretStore = secretStore;
    }

    @Override
    public List<Connection> listConnections() {
        return connectionRepository.findAll();
    }

    @Override
    public Connection connectToRedis(String host, int port, Optional<String> password) {
        ConnectionId id = ConnectionId.newId();
        String connectionId = id.value().toString();

        ProviderResult<Ack> connected = connectionProvider.connect(new ConnectOptions(connectionId, host, port, password));
        if (connected instanceof ProviderResult.Err<Ack> err) {
            throw new IllegalStateException(describeError(err.error()));
        }
        connectedInThisSession.add(connectionId);

        Optional<SecretRef> secretRef = password.map(this::storeSecret);
        Connection connection = new Connection(
                id, new ConnectionKind.Remote(host, port, false), EnvironmentClass.UNKNOWN,
                secretRef, RedisSafetyPolicy.restrictiveDefault());
        connectionRepository.insert(connection);
        return connection;
    }

    @Override
    public void deleteConnection(ConnectionId id) {
        String connectionId = id.value().toString();
        connectionProvider.disconnect(connectionId);
        connectedInThisSession.remove(connectionId);
        connectionRepository.findById(id).flatMap(Connection::secretRef)
                .ifPresent(ref -> secretStore.delete(new SecretHandle(ref.opaqueHandle())));
        connectionRepository.delete(id);
    }

    @Override
    public ScanPage scanKeys(ConnectionId id, String cursor, int pageSize) {
        ensureConnected(id);
        ProviderResult<ScanPage> result = connectionProvider.scan(id.value().toString(), cursor, pageSize);
        if (result instanceof ProviderResult.Err<ScanPage> err) {
            throw new IllegalStateException(describeError(err.error()));
        }
        return ((ProviderResult.Ok<ScanPage>) result).value();
    }

    @Override
    public Optional<String> getValue(ConnectionId id, String key) {
        ensureConnected(id);
        ProviderResult<String> result = connectionProvider.getString(id.value().toString(), key);
        return switch (result) {
            case ProviderResult.Ok<String> ok -> Optional.of(ok.value());
            case ProviderResult.Err<String> err when err.error() instanceof ProviderError.NotFound -> Optional.empty();
            case ProviderResult.Err<String> err -> throw new IllegalStateException(describeError(err.error()));
        };
    }

    private void ensureConnected(ConnectionId id) {
        String connectionId = id.value().toString();
        if (connectedInThisSession.contains(connectionId)) {
            return;
        }
        Connection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("connection not found: " + id));
        if (!(connection.kind() instanceof ConnectionKind.Remote remote)) {
            throw new IllegalStateException("only Remote connections are reconnectable in V1: " + connection.kind());
        }

        Optional<String> password = connection.secretRef().map(ref -> {
            ProviderResult<String> resolved = secretStore.resolve(new SecretHandle(ref.opaqueHandle()));
            if (resolved instanceof ProviderResult.Err<String> err) {
                throw new IllegalStateException("could not resolve stored password: " + describeError(err.error()));
            }
            return ((ProviderResult.Ok<String>) resolved).value();
        });

        ProviderResult<Ack> result = connectionProvider.connect(new ConnectOptions(connectionId, remote.host(), remote.port(), password));
        if (result instanceof ProviderResult.Err<Ack> err) {
            throw new IllegalStateException("reconnect failed: " + describeError(err.error()));
        }
        connectedInThisSession.add(connectionId);
    }

    private SecretRef storeSecret(String plaintext) {
        ProviderResult<SecretHandle> stored = secretStore.store(plaintext);
        if (stored instanceof ProviderResult.Err<SecretHandle> err) {
            throw new IllegalStateException("could not store password: " + describeError(err.error()));
        }
        SecretHandle handle = ((ProviderResult.Ok<SecretHandle>) stored).value();
        return new SecretRef(java.util.UUID.randomUUID(), secretStore.manifest().id(), handle.opaqueValue());
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
