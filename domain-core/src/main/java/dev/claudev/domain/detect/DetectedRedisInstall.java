package dev.claudev.domain.detect;

import java.nio.file.Path;

/**
 * An already-installed (but not necessarily running) {@code redis-server.exe} found on the local
 * machine — the user's own binary (Memurai, tporadowski/redis-windows, a Chocolatey package, ...),
 * never bundled or downloaded by this app. Crosses the UI/backend port boundary in place of the
 * user typing a path by hand. Unlike RabbitMQ's {@code DetectedCandidate}, there is no second
 * component to pair against — a single binary is the whole candidate.
 */
public record DetectedRedisInstall(Path redisServerExe, String label) {
}
