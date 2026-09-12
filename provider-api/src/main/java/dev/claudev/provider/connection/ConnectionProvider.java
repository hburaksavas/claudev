package dev.claudev.provider.connection;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.ProviderResult;

import java.util.List;
import java.util.Map;

/**
 * Redis connection/explorer port. V1 scope is connection-only (remote + imported/system sources,
 * no managed local Redis) with a small typed edit set — see docs/REDIS_SCOPE.md for the exact
 * per-type command allow-list this interface is expected to grow into.
 *
 * <p>Every mutation, single-key or bulk, must pass {@link #authorizeMutation}; only bulk/glob/
 * flush-class operations additionally go through {@link #prepareMutation}/{@link #commitMutation}
 * for the bounded-impact-preview-and-token flow (D8/D9 refinement).
 */
public interface ConnectionProvider {

    AdapterManifest manifest();

    /** See docs/adr/ADR-011-connectionprovider-explicit-connect-lifecycle.md. Must precede every other call for a given {@code connectionId}. */
    ProviderResult<Ack> connect(ConnectOptions options);

    ProviderResult<Ack> disconnect(String connectionId);

    ProviderResult<ScanPage> scan(String connectionId, String cursor, int pageSize);

    ProviderResult<String> getString(String connectionId, String key);

    /** Size-capped, not a live cursor — see docs/adr/ADR-012-redis-typed-edit-set-dto-shape.md. */
    ProviderResult<Map<String, String>> getHash(String connectionId, String key);

    /** Genuinely bounded by the caller-supplied window, like {@code LRANGE}. */
    ProviderResult<List<String>> getListRange(String connectionId, String key, long start, long stop);

    /** Size-capped, not a live cursor — see docs/adr/ADR-012-redis-typed-edit-set-dto-shape.md. */
    ProviderResult<List<String>> getSetMembers(String connectionId, String key);

    /** Genuinely bounded by the caller-supplied index window, like {@code ZRANGE}. */
    ProviderResult<List<String>> getSortedSetRange(String connectionId, String key, long start, long stop);

    ProviderResult<Ack> authorizeMutation(MutationRequest request);

    ProviderResult<MutationPreview> prepareMutation(BulkMutationRequest request);

    ProviderResult<Ack> commitMutation(String mutationToken);
}
