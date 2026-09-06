package dev.claudev.provider.connection;

import java.time.Instant;

/**
 * A short-lived, single-use {@code mutationToken}: {@link ConnectionProvider#commitMutation}
 * must reject it if the underlying data changed since preview, or once {@code expiresAt} passes.
 */
public record MutationPreview(String mutationToken, long estimatedAffectedKeys, Instant expiresAt) {}
