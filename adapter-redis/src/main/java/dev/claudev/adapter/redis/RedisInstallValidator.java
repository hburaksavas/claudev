package dev.claudev.adapter.redis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared "does this look like a real redis-server.exe" check, used by both manual import and auto-detection. */
public final class RedisInstallValidator {

    private RedisInstallValidator() {
    }

    public static Path redisServerExeAt(Path redisServerExe) throws IOException {
        if (!Files.isRegularFile(redisServerExe)) {
            throw new IOException("redis-server.exe not found at " + redisServerExe);
        }
        return redisServerExe;
    }

    public static boolean looksLikeRedisServerExe(Path redisServerExe) {
        return Files.isRegularFile(redisServerExe);
    }
}
