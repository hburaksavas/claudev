package dev.claudev.adapter.redis;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.Capability;
import dev.claudev.provider.ProviderError;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.connection.BulkMutationRequest;
import dev.claudev.provider.connection.ConnectOptions;
import dev.claudev.provider.connection.ConnectionProvider;
import dev.claudev.provider.connection.MutationPreview;
import dev.claudev.provider.connection.MutationRequest;
import dev.claudev.provider.connection.ScanPage;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real {@code adapter-redis} (WP7) — connection-only, no managed local Redis (docs/REDIS_SCOPE.md
 * is explicit that bundling an unofficial Windows Redis fork the way {@code adapter-rabbitmq} bundles
 * RabbitMQ would repeat the exact mistake that document rejects). {@link #connect} opens a real
 * Lettuce connection to a Redis the caller already resolved the address of.
 *
 * <p><b>Scope actually implemented</b> (see docs/MILESTONES.md WP7 for what isn't): the String and
 * Key/TTL rows of docs/REDIS_SCOPE.md's typed edit table — {@code GET}/{@code SET}/{@code APPEND}/
 * {@code DEL}/{@code EXPIRE}/{@code PERSIST} — plus {@code SCAN} paging and a real bulk pattern-DEL
 * preview/commit token flow. Hash/List/Set/ZSet operations need a field/member parameter {@link
 * MutationRequest} has no slot for; not built here, not silently faked.
 */
public final class RedisConnectionProvider implements ConnectionProvider {

    private static final int BULK_PREVIEW_SCAN_LIMIT = 10_000;
    private static final Duration MUTATION_TOKEN_TTL = Duration.ofMinutes(2);

    private final Map<String, ConnectionHandle> connections = new ConcurrentHashMap<>();
    private final Map<String, PendingBulkDelete> pendingBulkDeletes = new ConcurrentHashMap<>();

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "adapter-redis", "0.1.0",
                List.of(new Capability("connection", "redis"), new Capability("status", "partial")),
                "{}");
    }

    @Override
    public ProviderResult<Ack> connect(ConnectOptions options) {
        RedisURI.Builder uriBuilder = RedisURI.Builder.redis(options.host(), options.port());
        options.password().ifPresent(pw -> uriBuilder.withPassword(pw.toCharArray()));

        RedisClient client = RedisClient.create(uriBuilder.build());
        try {
            StatefulRedisConnection<String, String> connection = client.connect();
            connections.put(options.connectionId(), new ConnectionHandle(client, connection));
            return ProviderResult.ok(Ack.INSTANCE);
        } catch (RedisConnectionException e) {
            client.shutdown();
            return ProviderResult.err(new ProviderError.Underlying("CONNECT_FAILED", e.getMessage()));
        }
    }

    @Override
    public ProviderResult<Ack> disconnect(String connectionId) {
        ConnectionHandle handle = connections.remove(connectionId);
        if (handle != null) {
            handle.connection().close();
            handle.client().shutdown();
        }
        return ProviderResult.ok(Ack.INSTANCE);
    }

    @Override
    public ProviderResult<ScanPage> scan(String connectionId, String cursor, int pageSize) {
        return withCommands(connectionId, sync -> {
            ScanCursor scanCursor = (cursor == null || cursor.isBlank()) ? ScanCursor.INITIAL : ScanCursor.of(cursor);
            KeyScanCursor<String> result = sync.scan(scanCursor, ScanArgs.Builder.limit(pageSize));
            return ProviderResult.ok(new ScanPage(result.getCursor(), result.getKeys(), result.isFinished()));
        });
    }

    @Override
    public ProviderResult<String> getString(String connectionId, String key) {
        return withCommands(connectionId, sync -> {
            String value = sync.get(key);
            return value == null
                    ? ProviderResult.err(new ProviderError.NotFound("no such key: " + key))
                    : ProviderResult.ok(value);
        });
    }

    @Override
    public ProviderResult<Ack> authorizeMutation(MutationRequest request) {
        return withCommands(request.connectionId(), sync -> {
            switch (request.operation()) {
                case "SET" -> sync.set(request.key(), request.value());
                case "APPEND" -> sync.append(request.key(), request.value());
                case "DEL" -> sync.del(request.key());
                case "EXPIRE" -> sync.expire(request.key(), Long.parseLong(request.value()));
                case "PERSIST" -> sync.persist(request.key());
                default -> {
                    return ProviderResult.<Ack>err(new ProviderError.InvalidConfig(
                            "operation not in the V1 allow-list (docs/REDIS_SCOPE.md): " + request.operation()));
                }
            }
            return ProviderResult.ok(Ack.INSTANCE);
        });
    }

    /**
     * Only {@code DEL} is implemented — the one bulk/glob operation docs/REDIS_SCOPE.md actually
     * defines a preview shape for. Not exposed in any UI in V1 (the same document explicitly
     * excludes pattern-based multi-key delete from what users can reach) — built and tested for
     * real anyway, so the port method isn't a stub, but genuinely unreachable end to end today.
     */
    @Override
    public ProviderResult<MutationPreview> prepareMutation(BulkMutationRequest request) {
        if (!"DEL".equals(request.operation())) {
            return ProviderResult.err(new ProviderError.InvalidConfig(
                    "only DEL is supported as a bulk operation in V1: " + request.operation()));
        }
        return withCommands(request.connectionId(), sync -> {
            Set<String> matched = scanAllMatching(sync, request.keyPattern());
            String token = UUID.randomUUID().toString();
            Instant expiresAt = Instant.now().plus(MUTATION_TOKEN_TTL);
            pendingBulkDeletes.put(token, new PendingBulkDelete(request.connectionId(), request.keyPattern(), matched, expiresAt));
            return ProviderResult.ok(new MutationPreview(token, matched.size(), expiresAt));
        });
    }

    @Override
    public ProviderResult<Ack> commitMutation(String mutationToken) {
        PendingBulkDelete pending = pendingBulkDeletes.remove(mutationToken);
        if (pending == null) {
            return ProviderResult.err(new ProviderError.NotFound("no such mutation token (already used, expired, or unknown): " + mutationToken));
        }
        if (Instant.now().isAfter(pending.expiresAt())) {
            return ProviderResult.err(new ProviderError.Timeout("mutation token expired"));
        }
        return withCommands(pending.connectionId(), sync -> {
            Set<String> current = scanAllMatching(sync, pending.keyPattern());
            if (!current.equals(pending.matchedKeys())) {
                return ProviderResult.<Ack>err(new ProviderError.Conflict(
                        "underlying keys changed since preview — re-run prepareMutation"));
            }
            if (!pending.matchedKeys().isEmpty()) {
                sync.del(pending.matchedKeys().toArray(new String[0]));
            }
            return ProviderResult.ok(Ack.INSTANCE);
        });
    }

    private static Set<String> scanAllMatching(RedisCommands<String, String> sync, String pattern) {
        Set<String> matched = new TreeSet<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs args = ScanArgs.Builder.limit(1000).match(pattern);
        while (matched.size() < BULK_PREVIEW_SCAN_LIMIT) {
            KeyScanCursor<String> result = sync.scan(cursor, args);
            matched.addAll(result.getKeys());
            if (result.isFinished()) {
                break;
            }
            cursor = ScanCursor.of(result.getCursor());
        }
        return matched;
    }

    private <T> ProviderResult<T> withCommands(String connectionId, java.util.function.Function<RedisCommands<String, String>, ProviderResult<T>> action) {
        ConnectionHandle handle = connections.get(connectionId);
        if (handle == null) {
            return ProviderResult.err(new ProviderError.NotFound("no open connection: " + connectionId));
        }
        return action.apply(handle.connection().sync());
    }

    private record ConnectionHandle(RedisClient client, StatefulRedisConnection<String, String> connection) {
    }

    private record PendingBulkDelete(String connectionId, String keyPattern, Set<String> matchedKeys, Instant expiresAt) {
    }
}
