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
}
