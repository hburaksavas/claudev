package dev.claudev.provider.runtime;

/**
 * {@code state} is a wire-level string ("running", "degraded", "unreachable", ...) rather than a
 * shared enum with domain-core's {@code InstanceState} — the reconciler maps it, it does not
 * assume identity with the domain type (D2).
 */
public record InstanceHealth(String instanceId, String state, String detail) {}
