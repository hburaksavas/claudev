package dev.claudev.adapter.fepipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigPatchExecutorTest {

    private final ConfigPatchExecutor executor = new ConfigPatchExecutor();

    @Test
    void substitutesPlaceholdersInTheDeclaredFileOnly(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("app.properties");
        Files.writeString(target, "server.host=${HOST}\nserver.port=${PORT}\nunrelated=${NOT_SUBSTITUTED}\n");

        executor.execute(Map.of(
                "targetFile", target.toString(),
                "substitutions", Map.of("HOST", "10.0.0.5", "PORT", "8443")));

        String result = Files.readString(target);
        assertThat(result).isEqualTo("server.host=10.0.0.5\nserver.port=8443\nunrelated=${NOT_SUBSTITUTED}\n");
    }

    @Test
    void failsClearlyWhenTargetFileDoesNotExist(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.properties");

        assertThatThrownBy(() -> executor.execute(Map.of("targetFile", missing.toString())))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("does not exist");
    }
}
