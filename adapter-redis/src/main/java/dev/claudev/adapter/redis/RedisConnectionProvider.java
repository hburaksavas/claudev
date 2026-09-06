package dev.claudev.adapter.redis;

import dev.claudev.provider.Ack;
import dev.claudev.provider.AdapterManifest;
import dev.claudev.provider.Capability;
import dev.claudev.provider.ProviderError;
import dev.claudev.provider.ProviderResult;
import dev.claudev.provider.connection.BulkMutationRequest;
import dev.claudev.provider.connection.ConnectionProvider;
import dev.claudev.provider.connection.MutationPreview;
import dev.claudev.provider.connection.MutationRequest;
import dev.claudev.provider.connection.ScanPage;

import java.util.List;

/**
 * Milestone M1/M2 backlog item — see docs/REDIS_SCOPE.md for the exact allow-listed operation set
 * (SCAN/read/TTL + small typed edit set) and the authorization+audit gate every mutation must pass
 * regardless of size. Deliberately not implemented yet.
 */
public final class RedisConnectionProvider implements ConnectionProvider {

    @Override
    public AdapterManifest manifest() {
        return new AdapterManifest(
                "adapter-redis", "0.1.0",
                List.of(new Capability("connection", "redis")),
                "{}");
    }

    @Override
    public ProviderResult<ScanPage> scan(String connectionId, String cursor, int pageSize) {
        return notImplemented();
    }

    @Override
    public ProviderResult<String> getString(String connectionId, String key) {
        return notImplemented();
    }

    @Override
    public ProviderResult<Ack> authorizeMutation(MutationRequest request) {
        return notImplemented();
    }

    @Override
    public ProviderResult<MutationPreview> prepareMutation(BulkMutationRequest request) {
        return notImplemented();
    }

    @Override
    public ProviderResult<Ack> commitMutation(String mutationToken) {
        return notImplemented();
    }

    private static <T> ProviderResult<T> notImplemented() {
        return ProviderResult.err(new ProviderError.Underlying(
                "NOT_IMPLEMENTED", "adapter-redis is M1/M2 backlog, not yet built"));
    }
}
