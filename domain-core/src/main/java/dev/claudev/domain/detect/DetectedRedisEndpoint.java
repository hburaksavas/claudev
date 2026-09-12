package dev.claudev.domain.detect;

/**
 * An already-running Redis endpoint found by a TCP-connect + {@code PING} probe — never
 * lifecycle-owned by the app (docs/REDIS_SCOPE.md: "no managed local Redis"). Crosses the
 * UI/backend port boundary in place of the user typing host/port by hand.
 */
public record DetectedRedisEndpoint(String host, int port, String label) {
}
