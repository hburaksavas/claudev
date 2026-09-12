package dev.claudev.app;

import dev.claudev.adapter.redis.RedisRuntimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * WP10f: unlike {@link RabbitMqProviderHolder}, this holder is {@code Imported}-only — Redis V1 has
 * no {@code Managed}/pinned-pair fallback at all (docs/REDIS_SCOPE.md: no bundled local Redis), so
 * {@link #get} throws a clear error if no {@code redis-server.exe} was ever configured, rather than
 * silently provisioning something.
 */
@Component
class RedisProviderHolder {

    private String importedRedisServerExe;
    private volatile RedisRuntimeProvider provider;

    RedisProviderHolder(@Value("${claudev.redis.imported-redis-server-exe:}") String importedRedisServerExe) {
        this.importedRedisServerExe = importedRedisServerExe;
    }

    synchronized RedisRuntimeProvider get() throws IOException {
        if (provider == null) {
            if (!isImportedConfigured()) {
                throw new IllegalStateException(
                        "no redis-server.exe configured — pick one via the New Redis instance dialog first");
            }
            provider = RedisRuntimeProvider.fromImported(Path.of(importedRedisServerExe));
        }
        return provider;
    }

    boolean isImportedConfigured() {
        return !importedRedisServerExe.isBlank();
    }

    /**
     * WP10f: the user picked (via auto-detection or manual entry) a specific {@code redis-server.exe}
     * for a Redis instance they're creating right now. {@link #get} is a single process-wide
     * lazily-built provider (one binary for the whole app run, same limitation as {@link
     * RabbitMqProviderHolder}), so a second, different binary can't silently take over an
     * already-built provider. A no-op when the path matches what is already configured/built.
     */
    synchronized void configureImported(Path redisServerExe) {
        if (provider != null) {
            if (!provider.redisServerExe().equals(redisServerExe)) {
                throw new IllegalStateException(
                        "A Redis install is already active for this session: "
                                + provider.redisServerExe()
                                + ". Restart the app to choose a different install.");
            }
            return;
        }
        if (isImportedConfigured() && !Path.of(importedRedisServerExe).equals(redisServerExe)) {
            throw new IllegalStateException(
                    "A Redis install is already configured for this session: "
                            + importedRedisServerExe + ". Restart the app to choose a different install.");
        }
        this.importedRedisServerExe = redisServerExe.toString();
    }

    /** For {@code RuntimeDefinition.source} bookkeeping only — not used to decide behavior (see {@link #get}). */
    Path describedSourcePath() {
        return Path.of(importedRedisServerExe);
    }
}
