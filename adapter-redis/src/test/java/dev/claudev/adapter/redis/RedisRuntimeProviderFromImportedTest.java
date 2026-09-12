package dev.claudev.adapter.redis;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP10f: {@code RuntimeSource.Imported} — a user's own {@code redis-server.exe}, no download, no
 * checksum. Reuses the same test binary {@code RedisConnectionProviderTest}/
 * {@code SpringConnectionControlPortTest} already use; skips itself if that binary isn't present.
 */
class RedisRuntimeProviderFromImportedTest {

    private static final Path REDIS_SERVER_EXE =
            Path.of("D:\\dev\\workspace\\claudev-spike\\redis-test\\extracted\\redis-server.exe");

    @BeforeEach
    void requireRedisTestBinary() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Assumptions.assumeTrue(Files.isRegularFile(REDIS_SERVER_EXE),
                "Redis test binary not present on this machine — skipping (see docs/REDIS_SCOPE.md)");
    }

    @Test
    void rejectsAMissingExe(@TempDir Path tempDir) {
        assertThatThrownBy(() -> RedisRuntimeProvider.fromImported(tempDir.resolve("no-such-redis-server.exe")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("redis-server.exe not found");
    }

    @Test
    void aValidImportedExeIsAccepted() throws IOException {
        RedisRuntimeProvider provider = RedisRuntimeProvider.fromImported(REDIS_SERVER_EXE);

        org.assertj.core.api.Assertions.assertThat(provider.redisServerExe()).isEqualTo(REDIS_SERVER_EXE);
    }
}
