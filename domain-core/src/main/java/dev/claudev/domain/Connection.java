package dev.claudev.domain;

import java.util.Optional;

public record Connection(
        ConnectionId id,
        ConnectionKind kind,
        EnvironmentClass environmentClass,
        Optional<SecretRef> secretRef,
        RedisSafetyPolicy policy
) {}
