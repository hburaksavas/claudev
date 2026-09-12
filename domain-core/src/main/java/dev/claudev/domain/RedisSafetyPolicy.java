package dev.claudev.domain;

import java.util.Set;

/**
 * Every mutation — single-key or bulk — passes the base {@code readOnlyDefault}/environment-class/
 * audit gate. Only bulk/glob/flush-class operations in {@code typedConfirmationRequiredFor}
 * additionally require the prepare_mutation/commit_mutation bounded-impact-preview-and-token flow;
 * impact preview is meaningless for a single key the user already typed (docs/REDIS_SCOPE.md).
 */
public record RedisSafetyPolicy(
        boolean readOnlyDefault,
        Set<String> commandAllowList,
        Set<String> commandDenyList,
        Set<String> typedConfirmationRequiredFor,
        String keyNamespaceRestriction,
        boolean auditRequired
) {
    public RedisSafetyPolicy {
        commandAllowList = Set.copyOf(commandAllowList);
        commandDenyList = Set.copyOf(commandDenyList);
        typedConfirmationRequiredFor = Set.copyOf(typedConfirmationRequiredFor);
    }

    public static RedisSafetyPolicy restrictiveDefault() {
        return new RedisSafetyPolicy(
                true,
                Set.of(),
                Set.of("FLUSHDB", "FLUSHALL", "KEYS", "CONFIG", "CLUSTER", "EVAL", "SCRIPT"),
                Set.of("FLUSHDB", "FLUSHALL", "DEL_GLOB", "UNLINK_GLOB"),
                null,
                true
        );
    }

    /**
     * The base authorization+audit gate every mutation must pass, single-key or bulk, before it may
     * reach {@code ConnectionProvider#authorizeMutation} — docs/SECURITY.md's "Remote Redis
     * destructive-operation guard". Deny-list and a non-empty allow-list are checked first (an
     * operation the policy names explicitly, in either direction, is decided by that name, not by
     * connection state); {@code readOnlyDefault} is the last check, so a connection unlocked for
     * writes still can't run a deny-listed command.
     */
    public MutationAuthorization authorize(String operation, boolean unlockedForWrites) {
        if (commandDenyList.contains(operation)) {
            return MutationAuthorization.denied("operation is deny-listed for this connection: " + operation);
        }
        if (!commandAllowList.isEmpty() && !commandAllowList.contains(operation)) {
            return MutationAuthorization.denied("operation is not in this connection's allow-list: " + operation);
        }
        if (readOnlyDefault && !unlockedForWrites) {
            return MutationAuthorization.denied("connection is read-only — unlock it for writes first");
        }
        return MutationAuthorization.allowed();
    }
}
