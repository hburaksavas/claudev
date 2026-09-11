package dev.claudev.persistence;

/**
 * Pairs a domain aggregate with its optimistic-concurrency revision. Revision is deliberately kept
 * out of {@code domain-core} — it is persistence infrastructure, not a domain concept, the same
 * separation {@code provider-api}'s DTOs already keep from domain types (docs/PLUGIN_CONTRACT.md).
 */
public record Versioned<T>(T value, long revision) {
}
