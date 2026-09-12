package dev.claudev.provider.connection;

import java.util.Optional;

/**
 * A single-key, already-typed-and-confirmed edit (GET/SET/DEL/EXPIRE/... on one exact key).
 *
 * <p>{@code field}'s meaning depends on {@code operation} (see
 * docs/adr/ADR-012-redis-typed-edit-set-dto-shape.md): the hash field name for {@code HSET}/
 * {@code HDEL}, the score (as a string) for {@code ZADD}, and unused (must be {@link
 * Optional#empty()}) for every other operation.
 */
public record MutationRequest(String connectionId, String operation, String key, String value, Optional<String> field) {

    /** Convenience for every operation that doesn't need {@code field} — the pre-ADR-012 shape. */
    public MutationRequest(String connectionId, String operation, String key, String value) {
        this(connectionId, operation, key, value, Optional.empty());
    }
}
