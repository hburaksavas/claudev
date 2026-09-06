package dev.claudev.provider.connection;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.ProviderResult;

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

    ProviderResult<ScanPage> scan(String connectionId, String cursor, int pageSize);

    ProviderResult<String> getString(String connectionId, String key);

    ProviderResult<Ack> authorizeMutation(MutationRequest request);

    ProviderResult<MutationPreview> prepareMutation(BulkMutationRequest request);

    ProviderResult<Ack> commitMutation(String mutationToken);
}
