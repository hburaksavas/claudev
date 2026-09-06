package dev.claudev.provider;

/**
 * Closed error taxonomy every port method fails with — never an unchecked/opaque exception type —
 * so a future out-of-process protocol can serialize failures without inventing a taxonomy
 * retroactively (D2).
 */
public sealed interface ProviderError {
    record NotFound(String message) implements ProviderError {}

    record InvalidConfig(String message) implements ProviderError {}

    record Timeout(String message) implements ProviderError {}

    record PermissionDenied(String message) implements ProviderError {}

    record Conflict(String message) implements ProviderError {}

    record Underlying(String code, String message) implements ProviderError {}
}
