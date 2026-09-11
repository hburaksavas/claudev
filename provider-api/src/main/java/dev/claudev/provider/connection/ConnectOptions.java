package dev.claudev.provider.connection;

import java.util.Optional;

/**
 * Everything a {@link ConnectionProvider} needs to open one connection — see
 * docs/adr/ADR-011-connectionprovider-explicit-connect-lifecycle.md for why this exists as its own
 * call rather than repeated on every method. {@code password}, if present, is already a resolved
 * plaintext secret — resolving a domain {@code SecretRef} via {@code SecretStore} is the caller's
 * job, not this port's (D2).
 */
public record ConnectOptions(String connectionId, String host, int port, Optional<String> password) {
}
